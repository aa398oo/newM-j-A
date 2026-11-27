#include <jni.h>

#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <sys/prctl.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <malloc.h>
// bionic 的 heap tagging 开关。Android 14+ 默认 async tagging，JRE 里 HotSpot
// 自己的分配/free 路径可能拿到带 tag 的指针，在 VM Thread 触发
// 'Pointer tag for 0x… was truncated'。android_mallopt 在 API 30+ 才有，
// 运行时用 dlsym 查找，避免低版本链接失败。
#ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL 10
#endif
#ifndef M_HEAP_TAGGING_LEVEL_NONE
#define M_HEAP_TAGGING_LEVEL_NONE 0
#endif
#endif

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// Android 12+ 的 PR_SET_TAGGED_ADDR_CTRL（bionic 头文件不一定导出）。
#ifndef PR_SET_TAGGED_ADDR_CTRL
#define PR_SET_TAGGED_ADDR_CTRL 55
#endif

// 注意：导出的符号名必须与 Java 包名、类名逐字对应（dev.mineport.launcher.jvm.JvmLauncher）。

// render_bridge.cpp 里保存的 Android Surface 全局引用，用于把窗口交给 HotSpot 侧的
// CallbackBridge.setupBridgeWindow。
extern "C" jobject mineportHostSurface();
// 这里不依赖 JNI 自动注册，也不要改 Kotlin 侧的包名而不改这里，否则运行时直接
// UnsatisfiedLinkError（表现为「点启动没反应」或进程 abort）。
#define LOG_TAG "MineportJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_mutex;
// B9：main 只允许消费一次（同一 JVM 二次跑 main 必然状态污染）。
std::atomic<bool> g_mainConsumed{false};
void* g_libjvm = nullptr;
JavaVM* g_vm = nullptr;
// 加载主类（net.minecraft.client.main.Main）的 ClassLoader。MC 的类都在它下面，
// 触摸注入的反射必须走同一个 loader，否则拿到的是同名但不同类空间的副本。
jobject g_mcLoader = nullptr;
std::string g_lastError;
std::string g_javaVersion;

// FCL/Pojav 的正统输入通道：libpojavexec 导出的 critical_send_* 系列。
// 这些是普通 C 函数（不是 JNI 符号），dlsym 拿到即可直接调用；内部走 FCL 自己的
// input stack，不需要反射 MC 的 MouseHandler，也不用 URLClassLoader。
// 签名由 llvm-objdump 反汇编确认：
//   critical_send_cursor_pos(float x, float y)
//   critical_send_mouse_button(int button, int action, int mods)
//   critical_send_scroll(float dx, float dy)
//   critical_send_key(int key, int scancode, int action, int mods)
//   critical_send_screen_size(int width, int height)
//   critical_reset_input_state(void)
using CriticalCursorPosFn = void (*)(float, float);
using CriticalMouseButtonFn = void (*)(int, int, int);
using CriticalScrollFn = void (*)(float, float);
using CriticalKeyFn = void (*)(int, int, int, int);
using CriticalScreenSizeFn = void (*)(int, int);
using CriticalResetFn = void (*)(void);
// critical_set_stackqueue(int use)：把「事件直接执行」切成「进栈队列、渲染线程 pump」。
// 反汇编确认实现是 `useStackQueue = (use & 0xff) != 0`，写全局标志。
using CriticalSetStackqueueFn = void (*)(int);

// pojavStartPumping：libpojavexec 导出的 pump 启动函数（无参，反汇编确认未读 x0-x7）。
// 它负责把 critical_* 入队的事件真正派发给 GLFW 回调；不调用则队列永远不被消费。
using PojavStartPumpingFn = void (*)();

CriticalCursorPosFn g_criticalCursorPos = nullptr;
CriticalMouseButtonFn g_criticalMouseButton = nullptr;
CriticalScrollFn g_criticalScroll = nullptr;
CriticalKeyFn g_criticalKey = nullptr;
CriticalScreenSizeFn g_criticalScreenSize = nullptr;
CriticalResetFn g_criticalReset = nullptr;
CriticalSetStackqueueFn g_criticalSetStackqueue = nullptr;
bool g_criticalResolved = false;
bool g_stackQueueEnabled = false;
PojavStartPumpingFn g_pojavStartPumping = nullptr;
bool g_pumpingStarted = false;

/**
 * 解析 libpojavexec 导出的 critical_send_* 输入函数。
 * 拿不到时返回 false，调用方退回旧的反射/回调路径。
 */
bool resolveCriticalInput() {
    if (g_criticalResolved) {
        return g_criticalCursorPos != nullptr;
    }
    g_criticalResolved = true;
    void* handle = dlopen("libpojavexec.so", RTLD_NOW | RTLD_NOLOAD);
    if (handle == nullptr) {
        handle = dlopen("libpojavexec.so", RTLD_NOW);
    }
    if (handle == nullptr) {
        LOGE("critical: dlopen libpojavexec failed: %s", dlerror());
        return false;
    }
    g_criticalCursorPos = reinterpret_cast<CriticalCursorPosFn>(dlsym(handle, "critical_send_cursor_pos"));
    g_criticalMouseButton = reinterpret_cast<CriticalMouseButtonFn>(dlsym(handle, "critical_send_mouse_button"));
    g_criticalScroll = reinterpret_cast<CriticalScrollFn>(dlsym(handle, "critical_send_scroll"));
    g_criticalKey = reinterpret_cast<CriticalKeyFn>(dlsym(handle, "critical_send_key"));
    g_criticalScreenSize = reinterpret_cast<CriticalScreenSizeFn>(dlsym(handle, "critical_send_screen_size"));
    g_criticalReset = reinterpret_cast<CriticalResetFn>(dlsym(handle, "critical_reset_input_state"));
    g_criticalSetStackqueue = reinterpret_cast<CriticalSetStackqueueFn>(
            dlsym(handle, "critical_set_stackqueue"));
    g_pojavStartPumping = reinterpret_cast<PojavStartPumpingFn>(
            dlsym(handle, "pojavStartPumping"));
    LOGI("critical: cursor=%p button=%p key=%p scroll=%p screen=%p reset=%p stackqueue=%p",
         reinterpret_cast<void*>(g_criticalCursorPos),
         reinterpret_cast<void*>(g_criticalMouseButton),
         reinterpret_cast<void*>(g_criticalKey),
         reinterpret_cast<void*>(g_criticalScroll),
         reinterpret_cast<void*>(g_criticalScreenSize),
         reinterpret_cast<void*>(g_criticalReset),
         reinterpret_cast<void*>(g_criticalSetStackqueue));
    // 切到「输入进队列、渲染线程 pump」模式：否则 critical_* 会在调用线程直接执行
    // MouseHandler 回调，和渲染线程写同一批字段（参考文献指出的数据竞争）。
    if (g_criticalSetStackqueue != nullptr && !g_stackQueueEnabled) {
        // A/B 结论：0 = 直接在当前线程调 GLFW 回调，在 MC 里会直接崩/退回首页；
        // 必须保持 1（入队，由渲染线程每帧 glfwPollEvents 派发）。
        g_criticalSetStackqueue(1);
        g_stackQueueEnabled = true;
        LOGI("critical: stack queue enabled");
        // 关键一步：光入队不够，必须有线程持续调 pojavPumpEvents 才会派发给 MC。
        // FCL 原设计就是由 pojavStartPumping 起这个循环，这里补上。
        if (g_pojavStartPumping != nullptr && !g_pumpingStarted) {
            g_pumpingStarted = true;
            g_pojavStartPumping();
            LOGI("critical: pojavStartPumping() called");
        } else if (g_pojavStartPumping == nullptr) {
            LOGI("critical: pojavStartPumping missing, queue will never drain");
        }
    }
    // 免编译 A/B 开关：/data/data/dev.mineport.launcher/files/enable-selfpump 存在时，
    // 自建一个 8ms 循环调用 pojavPumpEvents，用来验证「队列没人 pump」这个假设。
    // 不开这个文件时行为完全不变。
    static bool selfPumpChecked = false;
    if (!selfPumpChecked) {
        selfPumpChecked = true;
        void* pumpHandle = dlopen("libpojavexec.so", RTLD_NOW | RTLD_NOLOAD);
        void* pumpSym = pumpHandle != nullptr ? dlsym(pumpHandle, "pojavPumpEvents") : nullptr;
        if (pumpSym == nullptr) {
            LOGI("selfpump: pojavPumpEvents not resolved");
        } else if (access("/data/data/dev.mineport.launcher/files/enable-selfpump", F_OK) != 0) {
            LOGI("selfpump: disabled (no marker file)");
        } else {
            LOGI("selfpump: thread started");
            auto pumpFn = reinterpret_cast<void (*)(void*)>(pumpSym);
            std::thread([pumpFn]() {
                while (true) {
                    JNIEnv* pumpEnv = nullptr;
                    bool attachedNow = false;
                    if (g_vm != nullptr &&
                        g_vm->GetEnv(reinterpret_cast<void**>(&pumpEnv), JNI_VERSION_1_6) != JNI_OK) {
                        if (g_vm->AttachCurrentThread(&pumpEnv, nullptr) == JNI_OK) {
                            attachedNow = true;
                        }
                    }
                    if (pumpEnv != nullptr) {
                        pumpFn(reinterpret_cast<void*>(pumpEnv));
                    }
                    if (attachedNow) {
                        g_vm->DetachCurrentThread();
                    }
                    std::this_thread::sleep_for(std::chrono::milliseconds(8));
                }
            }).detach();
        }
    }
    return g_criticalCursorPos != nullptr && g_criticalMouseButton != nullptr;
}

using CreateJavaVmFn = jint (*)(JavaVM**, void**, void*);

/**
 * HotSpot 的 Throwable.printStackTrace 写 stderr；Android 上 fd 2 默认没人看。
 * 用管道把 stderr 接到读线程，按行写 logcat（MineportJVM-stderr），
 * 这样 MC 早期（RenderSystem 等静态初始化）崩溃时能看到真正的异常栈。
 */
void startStderrLogPipe() {
    static std::atomic<bool> started{false};
    bool expected = false;
    if (!started.compare_exchange_strong(expected, true)) {
        return;
    }
    int fds[2];
    if (pipe(fds) != 0) {
        LOGE("stderr pipe failed: %s", std::strerror(errno));
        return;
    }
    dup2(fds[1], STDERR_FILENO);
    close(fds[1]);
    std::thread([readFd = fds[0]]() {
        char buffer[512];
        std::string pending;
        for (;;) {
            const ssize_t n = read(readFd, buffer, sizeof(buffer));
            if (n <= 0) {
                break;
            }
            pending.append(buffer, static_cast<size_t>(n));
            size_t pos = 0;
            while ((pos = pending.find('\n')) != std::string::npos) {
                __android_log_print(ANDROID_LOG_ERROR, "MineportJVM-stderr", "%s",
                                    pending.substr(0, pos).c_str());
                pending.erase(0, pos + 1);
            }
            if (pending.size() > 4096) {
                __android_log_print(ANDROID_LOG_ERROR, "MineportJVM-stderr", "%s", pending.c_str());
                pending.clear();
            }
        }
    }).detach();
}

/**
 * 尝试关掉 bionic 的 heap tagging（Android 14+ 默认 async tagging）。
 *
 * 和 prctl(PR_SET_TAGGED_ADDR_CTRL) 不同：这里只影响 malloc/free 的 tag 行为，
 * 不动进程级 tagged address。JRE 的 HotSpot 有自己的一套分配器交互，free() 在
 * tag 校验下 abort 是已知的 Android 16 兼容问题。失败时静默返回，不影响启动。
 */
void tryDisableHeapTagging() {
#ifdef __ANDROID__
    using AndroidMalloptFn = int (*)(int, void*, size_t);
    auto mallopt = reinterpret_cast<AndroidMalloptFn>(dlsym(RTLD_DEFAULT, "android_mallopt"));
    if (mallopt == nullptr) {
        LOGI("android_mallopt not available, heap tagging left untouched");
        return;
    }
    int level = M_HEAP_TAGGING_LEVEL_NONE;
    const int result = mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, &level, sizeof(level));
    LOGI("android_mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, NONE) -> %d", result);

    // 验证开关是否真的生效：heap tagging 开启时 malloc 返回的指针高 8 位带 tag，
    // 关闭后应为 0。返回 0 只说明调用被接受，不代表行为已切换。
    void* probe = malloc(16);
    const uintptr_t probeValue = reinterpret_cast<uintptr_t>(probe);
    LOGI("malloc probe %p tag=0x%llx", probe,
         static_cast<unsigned long long>(probeValue >> 56));
    free(probe);

    // 文档口径：heap tagging level 只对「调用之后新建的线程」生效。JVM 自己的
    // VM Thread 都是新线程，所以这里用 pthread 再验一次，区分「开关无效」与
    // 「只对老线程无效」。
    pthread_t thread{};
    if (pthread_create(&thread, nullptr, [] (void*) -> void* {
            void* p = malloc(16);
            LOGI("probe thread malloc %p tag=0x%llx", p,
                 static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(p) >> 56));
            free(p);
            return nullptr;
        }, nullptr) == 0) {
        pthread_join(thread, nullptr);
    } else {
        LOGI("probe thread create failed");
    }
#endif
}

/**
 * 创建 JVM 前只做最小准备：挂 lib/server 下的 libjsig（信号处理）。
 *
 * 不能预加载 libjvm.so 本身：dlopen 会触发它的静态初始化，随后 JNI_CreateJavaVM
 * 再初始化一次，在 Android 16 上会以 VM Thread SIGABRT 收场
 * （'Pointer tag for 0x… was truncated'）。libjvm 只由 JNI_CreateJavaVM 自己 dlopen。
 *
 * lib/ 下的依赖库（libjava → libjvm，libnet → libjava，libnio → libnet）在
 * JVM 创建之后再补加载，见 preloadAfterCreate。
 */
void preloadCompanions(const std::string& libjvmPath) {
    // 实验结论：libjsig 会在 JVM 之前接管 sigaction，和 HotSpot 自己的信号链
    // 叠加后在 Android 16 上更容易触发 VM Thread abort。这里只记录，不加载。
    // 若将来需要 signal chaining（嵌套 JVM），再按需打开。
    const auto slash = libjvmPath.find_last_of('/');
    const std::string serverDir = slash == std::string::npos ? libjvmPath : libjvmPath.substr(0, slash);
    LOGI("companion preload skipped (libjsig not loaded), serverDir=%s", serverDir.c_str());
}

/**
 * JVM 创建后再补加载 JRE 的 lib/ 依赖库。
 *
 * Android linker 不按「同目录」解析 DT_NEEDED，而 libnio → libnet 这类依赖
 * 不会被 HotSpot 自动带进来，结果是 libnio 打开失败、java.nio.file.FileSystems
 * 初始化失败，连 URLClassLoader 都读不了 jar。
 *
 * 这里按依赖顺序 dlopen(RTLD_GLOBAL)：libjava → libnet → libnio → libzip → libjimage。
 * 此时 libjvm 已由 JNI_CreateJavaVM 加载，libjava 的 NEEDED 能正常解析。
 */
void preloadAfterCreate(const std::string& libjvmPath) {
    const auto slash = libjvmPath.find_last_of('/');
    if (slash == std::string::npos) return;
    const std::string serverDir = libjvmPath.substr(0, slash);
    const auto parentSlash = serverDir.find_last_of('/');
    const std::string libDir =
            (parentSlash == std::string::npos) ? serverDir : serverDir.substr(0, parentSlash);

    static const char* kOrder[] = {
            "libjava.so", "libnet.so",
    };
    int loaded = 0;
    for (const char* name : kOrder) {
        const std::string full = libDir + "/" + name;
        if (access(full.c_str(), R_OK) != 0) continue;
        if (dlopen(full.c_str(), RTLD_LAZY | RTLD_GLOBAL) != nullptr) {
            ++loaded;
        } else {
            LOGI("post-create preload %s failed: %s", name, dlerror());
        }
    }
    LOGI("preloaded %d JRE libraries after create from %s", loaded, libDir.c_str());
}


std::string toStd(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

/** 取出并清掉当前挂起的 Java 异常，转成 "类名: message" 文本，便于日志定位。 */
std::string describePendingException(JNIEnv* env) {
    jthrowable pending = env->ExceptionOccurred();
    if (pending == nullptr) return "(no pending exception)";
    env->ExceptionClear();
    jclass throwableClass = env->FindClass("java/lang/Throwable");
    if (throwableClass == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(pending);
        return "(unprintable throwable)";
    }
    jmethodID toString = env->GetMethodID(throwableClass, "toString", "()Ljava/lang/String;");
    std::string text = "(unprintable throwable)";
    if (toString == nullptr) {
        env->ExceptionClear();
    } else {
        auto description = static_cast<jstring>(env->CallObjectMethod(pending, toString));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        } else if (description != nullptr) {
            text = toStd(env, description);
            env->DeleteLocalRef(description);
        }
    }
    env->DeleteLocalRef(throwableClass);
    env->DeleteLocalRef(pending);
    return text;
}

std::string readSystemProperty(JNIEnv* env, const char* key) {
    jclass systemClass = env->FindClass("java/lang/System");
    if (systemClass == nullptr) {
        env->ExceptionClear();
        return {};
    }
    jmethodID getProperty = env->GetStaticMethodID(
            systemClass, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
    if (getProperty == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(systemClass);
        return {};
    }
    jstring keyString = env->NewStringUTF(key);
    auto* value = static_cast<jstring>(env->CallStaticObjectMethod(systemClass, getProperty, keyString));
    std::string result = toStd(env, value);
    if (value != nullptr) env->DeleteLocalRef(value);
    env->DeleteLocalRef(keyString);
    env->DeleteLocalRef(systemClass);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return {};
    }
    return result;
}

/**
 * 从 JVM 的系统类加载器里加载类。
 *
 * 桌面 JVM 的 system loader 读 -Djava.class.path，但 Android 上集成进来的 JRE
 * 不一定有 launcher 模块，所以这里失败时再退回 URLClassLoader（见下）。
 */
jclass loadClassFromSystemLoader(JNIEnv* env, const std::string& name) {
    LOGI("loadClassFromSystemLoader: %s", name.c_str());
    jclass classLoaderClass = env->FindClass("java/lang/ClassLoader");
    if (classLoaderClass == nullptr) {
        LOGI("system loader: ClassLoader not found: %s", describePendingException(env).c_str());
        return nullptr;
    }
    jmethodID getSystemClassLoader = env->GetStaticMethodID(
            classLoaderClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jmethodID loadClass = env->GetMethodID(
            classLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jclass result = nullptr;
    if (getSystemClassLoader != nullptr && loadClass != nullptr) {
        jobject loader = env->CallStaticObjectMethod(classLoaderClass, getSystemClassLoader);
        if (env->ExceptionCheck()) {
            LOGI("system loader: getSystemClassLoader threw: %s",
                 describePendingException(env).c_str());
            loader = nullptr;
        }
        if (loader == nullptr) LOGI("system loader: getSystemClassLoader returned null");
        if (loader != nullptr) {
            jstring jname = env->NewStringUTF(name.c_str());
            result = static_cast<jclass>(env->CallObjectMethod(loader, loadClass, jname));
            env->DeleteLocalRef(jname);
            if (env->ExceptionCheck()) {
                LOGI("system loader: loadClass threw: %s",
                     describePendingException(env).c_str());
                result = nullptr;
            }
            env->DeleteLocalRef(loader);
        }
    }
    env->DeleteLocalRef(classLoaderClass);
    LOGI("system loader miss: %s", name.c_str());
    return result;
}

/**
 * 兜底：按 System.getProperty("java.class.path") 建一个 URLClassLoader。
 *
 * 完全不依赖 launcher / AppClassLoader 是否初始化——只要 JVM 的 java.base 能用
 * java.net.URLClassLoader 和 java.io.File 就行，这在裁剪过的 JRE 里也都存在。
 */
jclass loadClassFromClassPath(JNIEnv* env, const std::string& name) {
    // 全局复用的 URLClassLoader：MC 的类与反射入口必须共享同一个类空间。
    static jobject g_cachedLoader = nullptr;
    jclass systemClass = env->FindClass("java/lang/System");
    if (systemClass == nullptr) return nullptr;
    jmethodID getProperty = env->GetStaticMethodID(
            systemClass, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
    std::string classPath;
    if (getProperty != nullptr) {
        jstring key = env->NewStringUTF("java.class.path");
        auto value = static_cast<jstring>(env->CallStaticObjectMethod(systemClass, getProperty, key));
        env->DeleteLocalRef(key);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        } else if (value != nullptr) {
            classPath = toStd(env, value);
            env->DeleteLocalRef(value);
        }
    }
    env->DeleteLocalRef(systemClass);
    if (classPath.empty()) return nullptr;

    LOGI("loadClassFromClassPath: %s cpBytes=%zu", name.c_str(), classPath.size());

    std::vector<std::string> entries;
    size_t start = 0;
    while (start <= classPath.size()) {
        const size_t pos = classPath.find(':', start);
        if (pos == std::string::npos) {
            entries.push_back(classPath.substr(start));
            break;
        }
        entries.push_back(classPath.substr(start, pos - start));
        start = pos + 1;
    }
    if (entries.empty()) return nullptr;

    jclass urlClass = env->FindClass("java/net/URL");
    jclass fileClass = env->FindClass("java/io/File");
    jclass uriClass = env->FindClass("java/net/URI");
    if (urlClass == nullptr || fileClass == nullptr || uriClass == nullptr) {
        LOGI("url loader: JDK class lookup failed: %s", describePendingException(env).c_str());
        if (urlClass != nullptr) env->DeleteLocalRef(urlClass);
        if (fileClass != nullptr) env->DeleteLocalRef(fileClass);
        if (uriClass != nullptr) env->DeleteLocalRef(uriClass);
        return nullptr;
    }
    jmethodID fileCtor = env->GetMethodID(fileClass, "<init>", "(Ljava/lang/String;)V");
    jmethodID toUri = env->GetMethodID(fileClass, "toURI", "()Ljava/net/URI;");
    jmethodID toUrl = env->GetMethodID(uriClass, "toURL", "()Ljava/net/URL;");
    jobjectArray urls = env->NewObjectArray(static_cast<jsize>(entries.size()), urlClass, nullptr);
    for (size_t i = 0; i < entries.size(); ++i) {
        jstring path = env->NewStringUTF(entries[i].c_str());
        jobject file = env->NewObject(fileClass, fileCtor, path);
        env->DeleteLocalRef(path);
        jobject uri = file != nullptr ? env->CallObjectMethod(file, toUri) : nullptr;
        if (file != nullptr) env->DeleteLocalRef(file);
        jobject url = uri != nullptr ? env->CallObjectMethod(uri, toUrl) : nullptr;
        if (uri != nullptr) env->DeleteLocalRef(uri);
        if (url != nullptr) {
            env->SetObjectArrayElement(urls, static_cast<jsize>(i), url);
            env->DeleteLocalRef(url);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    // 复用同一个 URLClassLoader：MC 的类（net.minecraft.client.main.Main 及其依赖）
    // 必须和反射入口落在同一个类空间。每次 new 一个 loader 会得到「名字相同但不是
    // 同一个类」的副本，连静态初始化都会因缺依赖抛 NoClassDefFoundError。
    // 只对 HotSpot 调用方缓存：ART 的 URLClassLoader 读不了 jar，缓存它会污染反射入口。
    JavaVM* callerVm = nullptr;
    env->GetJavaVM(&callerVm);
    const bool hotSpotCaller = (callerVm != nullptr && callerVm == g_vm);
    LOGI("loadClassFromClassPath: %s hot=%d cached=%p callerVm=%p gvm=%p", name.c_str(),
         hotSpotCaller ? 1 : 0, g_cachedLoader, static_cast<void*>(callerVm),
         static_cast<void*>(g_vm));
    if (hotSpotCaller && g_cachedLoader != nullptr) {
        jclass loaderClass = env->FindClass("java/net/URLClassLoader");
        jmethodID loadClass = env->GetMethodID(
                loaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        jstring jname = env->NewStringUTF(name.c_str());
        jclass cached = static_cast<jclass>(
                env->CallObjectMethod(g_cachedLoader, loadClass, jname));
        env->DeleteLocalRef(jname);
        env->DeleteLocalRef(loaderClass);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            cached = nullptr;
        }
        LOGI("url loader (cached) result: %s -> %p", name.c_str(), static_cast<void*>(cached));
        return cached;
    }

    jclass loaderClass = env->FindClass("java/net/URLClassLoader");
    jclass result = nullptr;
    if (loaderClass != nullptr) {
        jmethodID ctor = env->GetMethodID(
                loaderClass, "<init>", "([Ljava/net/URL;Ljava/lang/ClassLoader;)V");
        jmethodID loadClass = env->GetMethodID(
                loaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        if (ctor != nullptr && loadClass != nullptr) {
            jobject loader = env->NewObject(loaderClass, ctor, urls, nullptr);
            if (env->ExceptionCheck()) {
                LOGI("url loader: URLClassLoader ctor threw: %s",
                     describePendingException(env).c_str());
            } else if (loader != nullptr) {
                if (hotSpotCaller && g_cachedLoader == nullptr) {
                    g_cachedLoader = env->NewGlobalRef(loader);
                    LOGI("url loader: cached classloader %p", g_cachedLoader);
                }
                jstring jname = env->NewStringUTF(name.c_str());
                result = static_cast<jclass>(env->CallObjectMethod(loader, loadClass, jname));
                env->DeleteLocalRef(jname);
                if (env->ExceptionCheck()) {
                    LOGI("url loader: loadClass threw: %s",
                         describePendingException(env).c_str());
                    result = nullptr;
                }
            }
            if (loader != nullptr) env->DeleteLocalRef(loader);
        } else if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(loaderClass);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(urls);
    env->DeleteLocalRef(urlClass);
    env->DeleteLocalRef(fileClass);
    env->DeleteLocalRef(uriClass);
    LOGI("url loader result: %s -> %p", name.c_str(), static_cast<void*>(result));
    return result;
}

}  // namespace

/**
 * 创建 JavaVM。
 *
 * 只做「建 VM + 读属性」，不回调应用自身的 Kotlin/Java 类：
 * 这个 JVM 的类加载器只认 classpath 上的 jar，不认 APK 里的 dex。
 * 因此第 2 步验收（屏幕显示 JRE 版本）由 nativeRuntimeVersion() 回传字符串完成；
 * 第 4 步接入 FCLCore.jar 时，再用 nativeRunMainClass() 之类的新入口在 jar 内执行。
 */
extern "C" JNIEXPORT jint JNICALL
Java_dev_mineport_launcher_jvm_JvmLauncher_nativeStartJvm(
        JNIEnv* env, jclass, jstring libjvmPath, jobjectArray options, jint codeCacheMB) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_lastError.clear();
    g_javaVersion.clear();

    // 在创建 JVM 之前接管 stderr，把 HotSpot 的异常栈转进 logcat。
    startStderrLogPipe();

    // Pojav 的 libpojavexec.so 在 pojavInitOpenGL 里直接 strcmp(getenv("FORCE_VSYNC"), ...)
    // 与 strncmp(getenv("POJAV_RENDERER"), ...)：变量缺失时 getenv 返回 NULL，strcmp 立刻
    // 段错误（SIGSEGV at 0x0，pojavInitOpenGL+36）。FCL/Pojav 的 Java 侧都会在启动前 setenv，
    // 我们直接走启动器，必须在创建 JVM 前补上这两个环境变量。
    //   POJAV_RENDERER=opengles3 → RENDERER_GL4ES（走 libgl4es 翻译层，Mali 设备可用）
    //   FORCE_VSYNC=false        → 不强制垂直同步，交给游戏交换间隔
    setenv("FORCE_VSYNC", "false", 1);
    setenv("POJAV_RENDERER", "opengles3", 1);
    // GL4ES 的日志开关：MC 1.20.1 的 Framebuffer 初始化报 GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT，
    // 需要看到 GL4ES 侧具体拒绝的 format/type/internalformat 组合（日志走 stderr，
    // 被 startStderrLogPipe 捕获为 MineportJVM-stderr）。
    setenv("LIBGL_DEBUG", "verbose", 1);

    if (g_vm != nullptr) {
        g_lastError = "JVM 已在运行（同一进程只允许创建一次）";
        return -6;
    }

    const std::string path = toStd(env, libjvmPath);
    if (path.empty()) {
        g_lastError = "libjvm.so 路径为空";
        return -1;
    }

    // Android 12+ 会给进程启用 tagged address。试过 prctl(PR_SET_TAGGED_ADDR_CTRL, 0)
    // 关闭它，结果 JVM 初始化卡死 50 秒后仍在 DefaultDispatch 线程 abort，比不关更糟。
    // 最终方案是 AndroidManifest 的 android:allowNativeHeapPointerTagging="false"：
    // 进程启动时由系统读属性，malloc 返回的指针高 8 位固定为 0。
    //
    // 这里不再调用 android_mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL)：实测它返回 0
    // 但行为不变（新线程 malloc 仍带 tag），而且在 Android 16 上调用之后 ART 的
    // hwui 线程会出现 "pthread_mutex_lock called on a destroyed mutex"，怀疑该调用
    // 会动到 bionic malloc 的全局锁状态，保留为实验记录，启动路径不再走。
    if (access(path.c_str(), R_OK) != 0) {
        g_lastError = "libjvm.so 不可读：" + path;
        return -1;
    }

    // RTLD_GLOBAL：让 libjava/libnio 等依赖库在 dlopen 时能通过全局作用域找到 libjvm。
    g_libjvm = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (g_libjvm == nullptr) {
        const char* reason = dlerror();
        g_lastError = reason != nullptr ? reason : "dlopen 返回空";
        LOGE("dlopen %s failed: %s", path.c_str(), g_lastError.c_str());
        return -2;
    }
    preloadCompanions(path);

    auto createJavaVm = reinterpret_cast<CreateJavaVmFn>(dlsym(g_libjvm, "JNI_CreateJavaVM"));
    if (createJavaVm == nullptr) {
        g_lastError = "libjvm.so 中找不到 JNI_CreateJavaVM（是不是拿到了 only-headless 或错误 ABI 的库？）";
        dlclose(g_libjvm);
        g_libjvm = nullptr;
        return -2;
    }

    const jsize count = options != nullptr ? env->GetArrayLength(options) : 0;
    // JavaVMOption.optionString 是 char*，必须在 JNI_CreateJavaVM 返回前保持有效，
    // 所以先把 jstring 全转成 std::string 稳定存放，再拼 vector。
    std::vector<std::string> optionStrings;
    optionStrings.reserve(static_cast<size_t>(count) + 2);
    for (jsize i = 0; i < count; ++i) {
        auto* item = static_cast<jstring>(env->GetObjectArrayElement(options, i));
        optionStrings.push_back(toStd(env, item));
        env->DeleteLocalRef(item);
    }
    optionStrings.push_back("-XX:ReservedCodeCacheSize=" + std::to_string(codeCacheMB) + "m");

    std::vector<JavaVMOption> vmOptions;
    vmOptions.reserve(optionStrings.size());
    for (auto& text : optionStrings) {
        JavaVMOption option{};
        option.optionString = text.data();
        option.extraInfo = nullptr;
        vmOptions.push_back(option);
    }

    JavaVMInitArgs args{};
    args.version = JNI_VERSION_1_6;
    args.nOptions = static_cast<jint>(vmOptions.size());
    args.options = vmOptions.data();
    args.ignoreUnrecognized = JNI_FALSE;

    JNIEnv* jvmEnv = nullptr;
    const jint created = createJavaVm(&g_vm, reinterpret_cast<void**>(&jvmEnv), &args);
    if (created != JNI_OK) {
        g_lastError = "JNI_CreateJavaVM 返回 " + std::to_string(created);
        LOGE("%s", g_lastError.c_str());
        g_vm = nullptr;
        return -3;
    }

    // JVM 已创建、libjvm 已在全局作用域：现在补加载 lib/ 下的依赖库。
    preloadAfterCreate(path);

    g_javaVersion = readSystemProperty(jvmEnv, "java.version");
    LOGI("JVM created: java.version=%s vm=%p", g_javaVersion.c_str(), static_cast<void*>(g_vm));
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_mineport_launcher_jvm_JvmLauncher_nativeRuntimeVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_javaVersion.c_str());
}

/**
 * 结束 JVM。
 *
 * 这里刻意不调用 DestroyJavaVM：创建 JVM 的那个线程就是 JVM 的 main 线程，
 * 它仍在 Kotlin 里跑；DestroyJavaVM 会一直等它退出，在 Android 上等于挂死 UI 线程。
 * 现阶段让进程退出时由系统回收，等接入游戏主循环后再按「专用线程 + 线程内 Destroy」重做。
 */
extern "C" JNIEXPORT void JNICALL
Java_dev_mineport_launcher_jvm_JvmLauncher_nativeStopJvm(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_vm == nullptr) return;
    LOGI("nativeStopJvm: JVM 保留在进程内（见函数注释），不做 DestroyJavaVM");
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_mineport_launcher_jvm_JvmLauncher_nativeLastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_lastError.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_mineport_launcher_jvm_JvmLauncher_nativeAbiInfo(JNIEnv* env, jclass) {
    std::string info = "arm64-v8a / JNI ";
#if defined(__aarch64__)
    info += "aarch64";
#else
    info += "non-aarch64";
#endif
    info += " / page " + std::to_string(sysconf(_SC_PAGESIZE)) + "B";
    return env->NewStringUTF(info.c_str());
}

/**
 * 在已创建的 JVM 里调用某个类的 main(String[])。
 *
 * 这是「不依赖 FCLCore 也能进游戏」的入口：直接调用 version JSON 里的 mainClass
 * （原版是 net.minecraft.client.main.Main）。main 会阻塞到游戏退出，
 * 调用方必须在后台线程（Kotlin 侧用 Dispatchers.IO），绝不能放 UI 线程。
 *
 * 注意：调用这个 native 方法的线程来自 ART，它的 JNIEnv 是 ART 的；
 * 必须 AttachCurrentThread 拿到 HotSpot 的 JNIEnv，否则 FindClass 找不到 jar 里的类。
 */
/**
 * FCLBridge.initializeHooks()：libpojavexec 接管 JVM 内部方法（exit / System.load /
 * 环境块替换等）的入口。FCL 在 JVM 创建后调用它；不调用时 hookExec 的函数表是空的，
 * MC 启动早期一旦触发 hook 就会跳到地址 0（SIGSEGV）。
 *
 * 注意：GameActivity 是 Android(ART) 代码，Class.forName 只能查 dex，查不到
 * HotSpot classpath 里的 FCLBridge；必须在 JVM 侧加载并调用。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_mineport_launcher_jvm_JvmLauncher_nativeInitializeHooks(
        JNIEnv* callerEnv, jclass, jstring nativeLibraryDir, jstring runtimeLibDir) {
    const std::string libDir = toStd(callerEnv, nativeLibraryDir);
    const std::string jreLibDir = toStd(callerEnv, runtimeLibDir);
    JavaVM* vm = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        vm = g_vm;
    }
    if (vm == nullptr) {
        g_lastError = "JVM 尚未创建";
        return JNI_FALSE;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    const jint state = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            g_lastError = "无法把当前线程附加到 JVM";
            return JNI_FALSE;
        }
        attached = true;
    } else if (state != JNI_OK) {
        g_lastError = "GetEnv 失败";
        return JNI_FALSE;
    }

    // 不在 HotSpot 里 System.load：ART 已经用同一个 dlopen 句柄加载过这些库，
    // HotSpot 再 load 会报 "already loaded in another classloader"。
    jclass bridge = loadClassFromSystemLoader(env, "com.tungsten.fclauncher.bridge.FCLBridge");
    if (bridge == nullptr) {
        bridge = loadClassFromClassPath(env, "com.tungsten.fclauncher.bridge.FCLBridge");
    }
    if (bridge == nullptr) {
        g_lastError = "找不到 com.tungsten.fclauncher.bridge.FCLBridge";
        LOGE("%s", g_lastError.c_str());
        if (attached) vm->DetachCurrentThread();
        return JNI_FALSE;
    }

    // libpojavexec 是 ART 用 System.loadLibrary 加载的，HotSpot 的 JNI 链接
    // 找不到它的符号（ClassLoader 关联不上）。这里直接 dlopen + dlsym 调
    // JNI 入口函数，绕过 ClassLoader 的 native library 注册表。
    //
    // 另外 initializeHooks 内部的 hookExec 会 dlsym(RTLD_DEFAULT,
    // "Java_java_lang_ProcessImpl_forkAndExec")；HotSpot 加载 libjava.so 时
    // 是局部符号表，必须先自己 RTLD_GLOBAL 打开一次，否则 dlsym 得到 NULL，
    // hookExec 之后 blr 空指针（libpojavexec +0x1018c）。
    const std::string libjavaPath = jreLibDir + "/libjava.so";
    void* javaHandle = dlopen(libjavaPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (javaHandle != nullptr) {
        LOGI("dlopen(%s, RTLD_GLOBAL) ok", libjavaPath.c_str());
    } else {
        LOGE("dlopen(%s) failed: %s", libjavaPath.c_str(), dlerror());
    }

    using InitializeHooksFn = void (*)(JNIEnv*, jclass);
    const char* symbol = "Java_com_tungsten_fclauncher_bridge_FCLBridge_initializeHooks";
    InitializeHooksFn fn = nullptr;
    const std::string pojavLib = libDir + "/libpojavexec.so";
    void* handle = dlopen(pojavLib.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (handle != nullptr) {
        fn = reinterpret_cast<InitializeHooksFn>(dlsym(handle, symbol));
        LOGI("initializeHooks dlsym(handle): %p", reinterpret_cast<void*>(fn));
    } else {
        LOGE("dlopen(%s) failed: %s", pojavLib.c_str(), dlerror());
    }
    if (fn == nullptr) {
        fn = reinterpret_cast<InitializeHooksFn>(dlsym(RTLD_DEFAULT, symbol));
        LOGI("initializeHooks dlsym(RTLD_DEFAULT): %p", reinterpret_cast<void*>(fn));
    }
    if (fn != nullptr) {
        LOGI("FCLBridge.initializeHooks() via dlsym");
        fn(env, bridge);
        bool ok = true;
        if (env->ExceptionCheck()) {
            const std::string detail = describePendingException(env);
            LOGE("initializeHooks threw: %s", detail.c_str());
            g_lastError = "initializeHooks 抛出异常：" + detail;
            ok = false;
        }

        // hookExec 里的 dlsym(RTLD_DEFAULT, "Java_java_lang_ProcessImpl_forkAndExec")
        // 在 Android 的 linker namespace 隔离下拿不到 HotSpot 内部加载的 libjava 符号，
        // 导致 hooked forkAndExec 的 orig 指针为 NULL，任何 exec 调用都会 SIGSEGV。
        // 这里直接填 libpojavexec 的 .bss 槽位（FCL 1.3.4.8 固定偏移 0x15c30）。
        void* javaSym = javaHandle != nullptr
                ? dlsym(javaHandle, "Java_java_lang_ProcessImpl_forkAndExec")
                : nullptr;
        if (javaSym != nullptr) {
            Dl_info info{};
            if (dladdr(reinterpret_cast<void*>(fn), &info) != 0 && info.dli_fbase != nullptr) {
                auto** slot = reinterpret_cast<void**>(
                        reinterpret_cast<uintptr_t>(info.dli_fbase) + 0x15c30);
                *slot = javaSym;
                LOGI("patched forkAndExec orig=%p slot=%p", javaSym, static_cast<void*>(slot));
            }
        } else {
            LOGE("Java_java_lang_ProcessImpl_forkAndExec not found in libjava");
        }

        // FCL 的 setupExitTrap：把 JavaVM* 与 onExit 回调存进 libfcl 的静态变量。
        // 不调用时 exitTrap_jvm/exitTrap_bridge/exitTrap_method 全为 NULL，
        // JVM 退出阶段 nominal_exit 先解引用空指针（libfcl nominal_exit+40）。
        // 注意必须在本线程（由 Java 调 native 进入）调用，FindClass 才会用 AppClassLoader。
        if (ok) {
            const std::string fclLib = libDir + "/libfcl.so";
            void* fclHandle = dlopen(fclLib.c_str(), RTLD_NOW | RTLD_GLOBAL);
            using SetupExitTrapFn = void (*)(JNIEnv*, jobject, jobject);
            SetupExitTrapFn trapFn = fclHandle != nullptr
                    ? reinterpret_cast<SetupExitTrapFn>(dlsym(
                              fclHandle,
                              "Java_com_tungsten_fclauncher_bridge_FCLBridge_setupExitTrap"))
                    : nullptr;
            if (trapFn == nullptr) {
                LOGE("setupExitTrap not found in libfcl: %s",
                     fclHandle == nullptr ? dlerror() : "dlsym returned NULL");
            } else {
                jmethodID ctor = env->GetMethodID(bridge, "<init>", "()V");
                if (ctor == nullptr) {
                    env->ExceptionClear();
                    LOGE("FCLBridge 缺少无参构造器");
                } else {
                    jobject bridgeObj = env->NewObject(bridge, ctor);
                    if (bridgeObj == nullptr) {
                        LOGE("无法实例化 FCLBridge");
                        env->ExceptionClear();
                    } else {
                        LOGI("FCLBridge.setupExitTrap() via dlsym");
                        trapFn(env, bridgeObj, bridgeObj);
                        if (env->ExceptionCheck()) {
                            const std::string detail = describePendingException(env);
                            LOGE("setupExitTrap threw: %s", detail.c_str());
                        } else {
                            LOGI("FCL exitTrap 已安装");
                        }
                        env->DeleteLocalRef(bridgeObj);
                    }
                }
            }
        }

        // FCL 的 atexit 处理器依赖一组 hook 指针（nominal_exit、create_chmod_hooks 等），
        // 没完整初始化时退出阶段会解引用空指针。宿主进程本来就只跑一局游戏，
        // 这里在 FCL 注册之后再注册一个 atexit（LIFO 先执行），直接 _exit(0) 掐断，
        // 让 FCL 的清理逻辑不执行；避免「游戏已退出、启动器却被带崩」。
        std::atexit([] {
            fflush(nullptr);
            _exit(0);
        });

        // 注意：不要把 ART 的 Surface 对象传给 HotSpot 侧调用 setupBridgeWindow——
        // 两个运行时的 jobject 不是同一套对象头，JNI 调用会直接 SIGSEGV。
        // 窗口桥由 GameActivity 在 ART 侧调用（libpojavexec 的 JNI_OnLoad 注册在 ART），
        // HotSpot 侧 GLFW 只用 native 全局里已创建好的 EGLSurface。

        env->DeleteLocalRef(bridge);
        if (attached) vm->DetachCurrentThread();
        return ok ? JNI_TRUE : JNI_FALSE;
    }

    jmethodID method = env->GetStaticMethodID(bridge, "initializeHooks", "()V");
    if (method == nullptr) {
        env->ExceptionClear();
        g_lastError = "FCLBridge 缺少 initializeHooks()";
        LOGE("%s", g_lastError.c_str());
        env->DeleteLocalRef(bridge);
        if (attached) vm->DetachCurrentThread();
        return JNI_FALSE;
    }

    LOGI("FCLBridge.initializeHooks()");
    env->CallStaticVoidMethod(bridge, method);
    bool ok = true;
    if (env->ExceptionCheck()) {
        const std::string detail = describePendingException(env);
        LOGE("initializeHooks threw: %s", detail.c_str());
        g_lastError = "initializeHooks 抛出异常：" + detail;
        ok = false;
    }
    env->DeleteLocalRef(bridge);
    if (attached) vm->DetachCurrentThread();
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_mineport_launcher_jvm_JvmLauncher_nativeRunMainClass(
        JNIEnv* callerEnv, jclass, jstring className, jobjectArray args, jstring outputPath) {
    // B9：同一个 JVM 只能消费一次 main。第二次进入会得到已经跑过 main 的 VM，
    // 表现为各种莫名其妙的 NoClassDefFoundError / 状态残留，不如直接拒绝并让上层重启进程。
    if (g_mainConsumed.exchange(true)) {
        return -4;
    }
    // 先把参数复制成 C++ 字符串：只借用 ART env 读 Java 字符串。
    const std::string name = toStd(callerEnv, className);
    const std::string outPath = outputPath != nullptr ? toStd(callerEnv, outputPath) : "";
    std::vector<std::string> argStrings;
    const jsize count = args != nullptr ? callerEnv->GetArrayLength(args) : 0;
    argStrings.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto* item = static_cast<jstring>(callerEnv->GetObjectArrayElement(args, i));
        argStrings.push_back(toStd(callerEnv, item));
        callerEnv->DeleteLocalRef(item);
    }

    JavaVM* vm = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        vm = g_vm;
    }
    if (vm == nullptr) {
        g_lastError = "JVM 尚未创建";
        return -1;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    const jint state = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            g_lastError = "无法把当前线程附加到 JVM";
            return -1;
        }
        attached = true;
    } else if (state != JNI_OK) {
        g_lastError = "GetEnv 失败";
        return -1;
    }

    // 主类加载顺序：
    // 1) 系统类加载器（桌面 JVM 读 -Djava.class.path）；
    // 2) Android 集成的裁剪版 JRE 可能没有 launcher 模块，系统加载器找到不类，
    //    这时按 java.class.path 自建 URLClassLoader 兜底。
    jclass target = loadClassFromSystemLoader(env, name);
    if (target == nullptr) {
        target = loadClassFromClassPath(env, name);
    }
    if (target == nullptr) {
        g_lastError = "找不到主类：" + name + "（classpath 里没有它？）";
        if (attached) vm->DetachCurrentThread();
        return -2;
    }
    // 记住加载主类的 ClassLoader（MC 的所有类都在它下面）。
    if (g_mcLoader == nullptr) {
        jclass classClass = env->FindClass("java/lang/Class");
        jmethodID getLoader = env->GetMethodID(classClass, "getClassLoader",
                                                "()Ljava/lang/ClassLoader;");
        if (classClass != nullptr && getLoader != nullptr) {
            jobject loader = env->CallObjectMethod(target, getLoader);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
            if (loader != nullptr) {
                g_mcLoader = env->NewGlobalRef(loader);
                env->DeleteLocalRef(loader);
                LOGI("mc loader cached: %p", static_cast<void*>(g_mcLoader));
            }
        }
        if (classClass != nullptr) env->DeleteLocalRef(classClass);
    }

    jmethodID mainMethod = env->GetStaticMethodID(target, "main", "([Ljava/lang/String;)V");
    if (mainMethod == nullptr) {
        env->ExceptionClear();
        g_lastError = "主类缺少 main(String[])：" + name;
        env->DeleteLocalRef(target);
        if (attached) vm->DetachCurrentThread();
        return -3;
    }

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray argv = env->NewObjectArray(count, stringClass, nullptr);
    for (jsize i = 0; i < count; ++i) {
        jstring item = env->NewStringUTF(argStrings[static_cast<size_t>(i)].c_str());
        env->SetObjectArrayElement(argv, i, item);
        env->DeleteLocalRef(item);
    }

    LOGI("invoking %s.main with %d args", name.c_str(), static_cast<int>(count));
    env->CallStaticVoidMethod(target, mainMethod, argv);

    jint result = 0;
    if (env->ExceptionCheck()) {
        // ExceptionDescribe 在 Android 上写到 stderr，通常没人看；把异常文本带回 Kotlin 侧。
        const std::string detail = describePendingException(env);
        LOGE("main threw: %s", detail.c_str());
        g_lastError = "main 抛出异常：" + detail;
        if (!outPath.empty()) {
            std::ofstream out(outPath, std::ios::trunc);
            out << "main threw:\n" << detail << "\n";
        }
        result = -4;
    } else if (!outPath.empty()) {
        std::ofstream out(outPath, std::ios::trunc);
        out << "main returned 0\n";
    }

    env->DeleteLocalRef(argv);
    env->DeleteLocalRef(stringClass);
    env->DeleteLocalRef(target);
    if (attached) vm->DetachCurrentThread();
    return result;
}

// ---- 动作轮盘输入桥 ----
//
// 第 4 步（FCLCore 接管 JVM 内输入）之前，这里先维护一个 8 位「按住」掩码：
// Kotlin 侧 InputBridge.setHeld 会调 nativeAction；FCLCore 接入后只需读取
// nativeActionMask 或把掩码翻译成它的事件队列，UI 与 Kotlin 侧无需改动。
namespace {

std::atomic<uint32_t> g_actionMask{0};

const std::map<std::string, uint32_t>& actionBits() {
    static const std::map<std::string, uint32_t> bits = {
        {"FORWARD", 1u << 0},
        {"RIGHT", 1u << 1},
        {"BACK", 1u << 2},
        {"LEFT", 1u << 3},
        {"JUMP", 1u << 4},
        {"SNEAK", 1u << 5},
        {"ATTACK", 1u << 6},
        {"USE", 1u << 7},
    };
    return bits;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_mineport_launcher_input_InputBridge_nativeAction(
        JNIEnv* env, jclass, jstring actionName, jboolean pressed) {
    const std::string name = toStd(env, actionName);
    const auto& bits = actionBits();
    const auto it = bits.find(name);
    if (it == bits.end()) {
        LOGI("input: unknown action '%s'", name.c_str());
        return JNI_FALSE;
    }
    const uint32_t bit = it->second;
    if (pressed) {
        g_actionMask.fetch_or(bit);
    } else {
        g_actionMask.fetch_and(~bit);
    }
    LOGI("input: %s %s -> mask=0x%02x",
         pressed ? "hold" : "release", name.c_str(), g_actionMask.load());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_mineport_launcher_input_InputBridge_nativeActionMask(JNIEnv*, jclass) {
    return static_cast<jint>(g_actionMask.load());
}

// ---- 指针/触摸 → GLFW 输入桥 ----
//
// MC 的 GLFW 窗口接管了 Surface，触摸必须按 GLFW 的事件形式送进去，
// 否则主菜单按钮点不动。callback 通过 HotSpot 的 CallbackBridge 注入：
//   action 0 = ACTION_DOWN（左键按下） / 1 = ACTION_MOVE / 2 = ACTION_UP。
// 坐标直接使用 Surface 坐标（GLFW 窗口尺寸 = Surface 尺寸）。
namespace {

jclass g_callbackBridgeClass = nullptr;
jmethodID g_callbackCursorPos = nullptr;
jmethodID g_callbackMouseButton = nullptr;
std::mutex g_callbackMutex;
int g_screenWidth = 0;
int g_screenHeight = 0;
bool g_screenSizeSent = false;

// libpojavexec 的 CallbackBridge native 只在它的 JNI_OnLoad 里注册；HotSpot 与 ART
// 两套 VM 交替调用时 JNI 注册表对不上（UnsatisfiedLinkError: ... emulated ...）。
// 直接 dlsym 导出的 JNI 函数符号最稳，env 用当前 VM 的即可。
using SendCursorPosFn = void (*)(JNIEnv*, jclass, jfloat, jfloat);
using SendMouseButtonFn = void (*)(JNIEnv*, jclass, jint, jint, jint);
using SetInputReadyFn = jboolean (*)(JNIEnv*, jclass, jboolean);
SendCursorPosFn g_fnCursorPos = nullptr;
SendMouseButtonFn g_fnMouseButton = nullptr;
SetInputReadyFn g_fnInputReady = nullptr;

void resolveCallbackBridgeFns() {
    if (g_fnCursorPos != nullptr && g_fnMouseButton != nullptr) return;
    void* handle = dlopen("libpojavexec.so", RTLD_NOW);
    if (handle == nullptr) {
        LOGE("pointer: dlopen pojavexec failed");
        return;
    }
    g_fnCursorPos = reinterpret_cast<SendCursorPosFn>(
        dlsym(handle, "Java_org_lwjgl_glfw_CallbackBridge_nativeSendCursorPos"));
    g_fnMouseButton = reinterpret_cast<SendMouseButtonFn>(
        dlsym(handle, "Java_org_lwjgl_glfw_CallbackBridge_nativeSendMouseButton"));
    g_fnInputReady = reinterpret_cast<SetInputReadyFn>(
        dlsym(handle, "Java_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady"));
    LOGI("pointer: dlsym cursorPos=%p mouse=%p inputReady=%p",
         reinterpret_cast<void*>(g_fnCursorPos), reinterpret_cast<void*>(g_fnMouseButton),
         reinterpret_cast<void*>(g_fnInputReady));
}

bool ensureCallbackBridge(JNIEnv* env) {
    if (g_callbackBridgeClass != nullptr) return true;
    jclass cls = loadClassFromSystemLoader(env, "org.lwjgl.glfw.CallbackBridge");
    if (cls == nullptr) {
        cls = loadClassFromClassPath(env, "org.lwjgl.glfw.CallbackBridge");
    }
    if (cls == nullptr) {
        LOGE("pointer: CallbackBridge not found");
        return false;
    }
    jmethodID cursor = env->GetStaticMethodID(cls, "nativeSendCursorPos", "(FF)V");
    jmethodID button = env->GetStaticMethodID(cls, "nativeSendMouseButton", "(III)V");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(cls);
        LOGE("pointer: CallbackBridge native methods missing");
        return false;
    }
    g_callbackBridgeClass = static_cast<jclass>(env->NewGlobalRef(cls));
    g_callbackCursorPos = cursor;
    g_callbackMouseButton = button;
    env->DeleteLocalRef(cls);
    return true;
}

// ---- MC 反射注入（绕开 GLFW 事件队列） -------------------------------------
// libpojavexec 里没有导出 CallbackBridge.nativeSendCursorPos/nativeSendMouseButton
// （FCL 3.4.8 把输入改走 SDL 了），所以触摸进不了 GLFW 队列。最稳的办法是直接喂
// MC 自己的鼠标处理器：MC 1.20.1 混淆映射（client.txt）：
//   Minecraft        -> enn   instance=F  mouseHandler=n  window=Q
//   MouseHandler     -> eno   onPress(JIII)V=a  onMove(JDD)V=b
//   Window           -> ehn   long window=e
struct McInputRefs {
    jclass mcClass = nullptr;
    jfieldID instanceField = nullptr;
    jfieldID mouseHandlerField = nullptr;
    jfieldID windowField = nullptr;
    jclass mouseHandlerClass = nullptr;
    jmethodID onMove = nullptr;
    jmethodID onPress = nullptr;
    jclass windowClass = nullptr;
    jfieldID handleField = nullptr;
    jclass keyboardClass = nullptr;
    jfieldID keyboardField = nullptr;
    jmethodID keyPress = nullptr;
};
McInputRefs g_mcInput;

// 用指定 ClassLoader 加载类。触摸注入必须用加载 MC 主类的那个 loader，
// 否则拿到的是同名但不同类空间的副本，静态初始化会抛 NoClassDefFoundError。
jclass loadViaLoader(JNIEnv* env, jobject loader, const char* name) {
    if (loader == nullptr) return nullptr;
    jclass loaderClass = env->FindClass("java/lang/ClassLoader");
    if (loaderClass == nullptr) return nullptr;
    jmethodID loadClass = env->GetMethodID(
            loaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (loadClass == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(loaderClass);
        return nullptr;
    }
    jstring jname = env->NewStringUTF(name);
    auto* cls = static_cast<jclass>(env->CallObjectMethod(loader, loadClass, jname));
    env->DeleteLocalRef(jname);
    env->DeleteLocalRef(loaderClass);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return cls;
}

bool ensureMcInput(JNIEnv* hs) {
    if (g_mcInput.onMove != nullptr && g_mcInput.onPress != nullptr) return true;
    jclass mc = loadViaLoader(hs, g_mcLoader, "enn");
    if (mc == nullptr) {
        mc = loadClassFromClassPath(hs, "enn");
    }
    if (mc == nullptr) {
        LOGE("inject: Minecraft class (enn) not found");
        return false;
    }
    jfieldID inst = hs->GetStaticFieldID(mc, "F", "Lenn;");
    jfieldID mh = hs->GetFieldID(mc, "n", "Leno;");
    jfieldID win = hs->GetFieldID(mc, "Q", "Lehn;");
    if (hs->ExceptionCheck() || inst == nullptr || mh == nullptr || win == nullptr) {
        LOGE("inject: fields inst=%p mh=%p win=%p ex=%s", static_cast<void*>(inst),
             static_cast<void*>(mh), static_cast<void*>(win),
             describePendingException(hs).c_str());
        hs->ExceptionClear();
        hs->DeleteLocalRef(mc);
        return false;
    }
    g_mcInput.mcClass = static_cast<jclass>(hs->NewGlobalRef(mc));
    g_mcInput.instanceField = inst;
    g_mcInput.mouseHandlerField = mh;
    g_mcInput.windowField = win;
    hs->DeleteLocalRef(mc);

    jclass eno = loadViaLoader(hs, g_mcLoader, "eno");
    if (eno == nullptr) eno = loadClassFromClassPath(hs, "eno");
    jclass ehn = loadViaLoader(hs, g_mcLoader, "ehn");
    if (ehn == nullptr) ehn = loadClassFromClassPath(hs, "ehn");
    if (eno == nullptr || ehn == nullptr) {
        hs->ExceptionClear();
        LOGE("inject: MouseHandler/Window class not found");
        return false;
    }
    g_mcInput.onMove = hs->GetMethodID(eno, "b", "(JDD)V");
    g_mcInput.onPress = hs->GetMethodID(eno, "a", "(JIII)V");
    g_mcInput.handleField = hs->GetFieldID(ehn, "e", "J");
    if (hs->ExceptionCheck() || g_mcInput.onMove == nullptr || g_mcInput.onPress == nullptr ||
        g_mcInput.handleField == nullptr) {
        hs->ExceptionClear();
        LOGE("inject: MouseHandler methods missing");
        return false;
    }
    g_mcInput.mouseHandlerClass = static_cast<jclass>(hs->NewGlobalRef(eno));
    g_mcInput.windowClass = static_cast<jclass>(hs->NewGlobalRef(ehn));
    hs->DeleteLocalRef(eno);
    hs->DeleteLocalRef(ehn);

    // 键盘注入：KeyboardHandler.keyPress(long,int,int,int,int)，用于验证事件链路。
    jclass enm = loadViaLoader(hs, g_mcLoader, "enm");
    if (enm == nullptr) enm = loadClassFromClassPath(hs, "enm");
    if (enm != nullptr) {
        jfieldID kbField = hs->GetFieldID(g_mcInput.mcClass, "o", "Lenm;");
        jmethodID keyPress = hs->GetMethodID(enm, "a", "(JIIII)V");
        if (!hs->ExceptionCheck() && kbField != nullptr && keyPress != nullptr) {
            g_mcInput.keyboardField = kbField;
            g_mcInput.keyPress = keyPress;
            g_mcInput.keyboardClass = static_cast<jclass>(hs->NewGlobalRef(enm));
        } else {
            hs->ExceptionClear();
        }
        hs->DeleteLocalRef(enm);
    }
    LOGI("inject: MC MouseHandler ready");
    return true;
}

// 返回 false 表示注入链路不可用，调用方可退回 GLFW/CallbackBridge 尝试。
bool injectMcPointer(JNIEnv* hs, float x, float y, int action) {
    if (!ensureMcInput(hs)) return false;
    jobject mc = hs->GetStaticObjectField(g_mcInput.mcClass, g_mcInput.instanceField);
    if (mc == nullptr) {
        hs->ExceptionClear();
        return false;
    }
    jobject mouseHandler = hs->GetObjectField(mc, g_mcInput.mouseHandlerField);
    jobject window = hs->GetObjectField(mc, g_mcInput.windowField);
    if (mouseHandler == nullptr || window == nullptr) {
        hs->ExceptionClear();
        hs->DeleteLocalRef(mc);
        return false;
    }
    jlong handle = hs->GetLongField(window, g_mcInput.handleField);
    hs->CallVoidMethod(mouseHandler, g_mcInput.onMove, handle, static_cast<jdouble>(x),
                       static_cast<jdouble>(y));
    if (action == 0) {
        hs->CallVoidMethod(mouseHandler, g_mcInput.onPress, handle, 0, 1, 0);
    } else if (action == 2) {
        hs->CallVoidMethod(mouseHandler, g_mcInput.onPress, handle, 0, 0, 0);
    }
    if (hs->ExceptionCheck()) {
        hs->ExceptionDescribe();
        hs->ExceptionClear();
        hs->DeleteLocalRef(mouseHandler);
        hs->DeleteLocalRef(window);
        hs->DeleteLocalRef(mc);
        return false;
    }
    hs->DeleteLocalRef(mouseHandler);
    hs->DeleteLocalRef(window);
    hs->DeleteLocalRef(mc);
    return true;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_dev_mineport_launcher_game_NativeRenderer_nativeSetScreenSize(
        JNIEnv*, jclass, jint width, jint height) {
    LOGI("screen size: %dx%d", width, height);
    // 只记下来：真正的 CallbackBridge 是 HotSpot 的类，等第一次触摸时
    // 在 HotSpot env 里统一写字段（ART 的 dex 副本写了对 GLFW 没用）。
    std::lock_guard<std::mutex> lock(g_callbackMutex);
    if (width == g_screenWidth && height == g_screenHeight) return;
    g_screenWidth = width;
    g_screenHeight = height;
    // 尺寸变了要重发：方向切换后 onCreate 的 displayMetrics 可能给的是竖屏值。
    g_screenSizeSent = false;
}

// 供 render_bridge.cpp 的 nativeResize 调用：Surface 尺寸才是窗口尺寸的真源。
// 之前 nativeResize 只改 glViewport 和 g_width/g_height，屏幕尺寸另一条路写，
// 横竖屏切换后 GLFW 的窗口尺寸与触摸坐标系不一致，点击会整体偏移。
extern "C" void bridgeOnSurfaceSize(int width, int height) {
    if (width <= 0 || height <= 0) return;
    std::lock_guard<std::mutex> lock(g_callbackMutex);
    if (width == g_screenWidth && height == g_screenHeight) return;
    g_screenWidth = width;
    g_screenHeight = height;
    g_screenSizeSent = false;
    LOGI("surface size -> %dx%d (pending flush)", width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mineport_launcher_game_NativeRenderer_nativeSendPointer(
        JNIEnv* env, jclass, jfloat x, jfloat y, jint action) {
    LOGI("pointer: x=%.1f y=%.1f action=%d", x, y, action);
    // ART 的 CallbackBridge 是 dex 里的副本，但 libpojavexec 的 JNI_OnLoad 是
    // 在 HotSpot 里 System.loadLibrary("pojavexec") 时跑的，native 只注册到了
    // HotSpot 的 CallbackBridge（jar）。所以必须 attach 到 HotSpot，用它的
    // env + URLClassLoader 加载类再调 native；ART 侧调用会抛 UnsatisfiedLinkError。
    std::lock_guard<std::mutex> lock(g_callbackMutex);
    JavaVM* vm = nullptr;
    {
        std::lock_guard<std::mutex> vmLock(g_mutex);
        vm = g_vm;
    }
    if (vm == nullptr) return;
    JNIEnv* hs = nullptr;
    bool attached = false;
    const jint state = vm->GetEnv(reinterpret_cast<void**>(&hs), JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&hs, nullptr) != JNI_OK) return;
        attached = true;
    } else if (state != JNI_OK) {
        return;
    }

    // 首选：FCL/Pojav 的 critical_send_* 输入队列（渲染线程每帧 pump，线程安全）。
    // 这是 FCL 3.4.1 真正在用的通道，和反射 MouseHandler 相比不会与渲染线程抢字段。
    if (resolveCriticalInput()) {
        // 诊断：isInputReady 是 critical_* 的第一道门（反汇编 env+0x218，相对 enviro+0x27000），
        // 为 0 时事件被静默丢弃。只打一次，避免刷屏。
        static bool loggedReady = false;
        if (!loggedReady) {
            loggedReady = true;
            void* sym = dlsym(RTLD_DEFAULT, "pojav_environ");
            if (sym == nullptr) {
                LOGI("critical: pojav_environ symbol missing");
            } else {
                void* environ = *static_cast<void**>(sym);
                if (environ == nullptr) {
                    LOGI("critical: pojav_environ is null");
                } else {
                    const uint8_t ready =
                        *(reinterpret_cast<uint8_t*>(environ) + 0x27218);
                    LOGI("critical: isInputReady=%d env=%p",
                         static_cast<int>(ready), environ);
                }
            }
        }
        // 首次走 critical 通道时补一次窗口尺寸：FCL 的输入层要靠它算命中范围。
        if (!g_screenSizeSent && g_screenWidth > 0 && g_criticalScreenSize != nullptr) {
            g_criticalScreenSize(g_screenWidth, g_screenHeight);
            g_screenSizeSent = true;
            LOGI("critical: screen size -> %dx%d", g_screenWidth, g_screenHeight);
        }
        g_criticalCursorPos(x, y);
        // 诊断：0x27219 由 critical_send_cursor_pos 置 1（说明本次已入队/已置 pending），
        // 0x2721b 由 pojavPumpEvents 消费后清 0。两者对比可判断 pump 是否真的在跑。
        {
            static void* cachedSym = nullptr;
            static bool cacheTried = false;
            if (!cacheTried) {
                cacheTried = true;
                cachedSym = dlsym(RTLD_DEFAULT, "pojav_environ");
            }
            if (cachedSym != nullptr) {
                void* environ = *static_cast<void**>(cachedSym);
                if (environ != nullptr) {
                    const uint8_t* base = reinterpret_cast<const uint8_t*>(environ);
                    LOGI("ptr: pend19=%d pend1b=%d stack=%d qidx=%u",
                         static_cast<int>(base[0x27219]),
                         static_cast<int>(base[0x2721b]),
                         static_cast<int>(base[0x2721a]),
                         *reinterpret_cast<const uint32_t*>(base + 0x27130));
                }
            }
        }
        // action: 0=按下 1=移动 2=抬起；只有按/抬才动按钮位。
        if (action != 1 && g_criticalMouseButton != nullptr) {
            g_criticalMouseButton(0, action == 0 ? 1 : 0, 0);
        }
        if (attached) vm->DetachCurrentThread();
        return;
    }

    // 兜底：直接调 MC 自己的 MouseHandler（不依赖 GLFW 事件队列）。
    if (injectMcPointer(hs, x, y, action)) {
        if (attached) vm->DetachCurrentThread();
        return;
    }

    if (g_callbackBridgeClass == nullptr) {
        jclass cls = loadClassFromClassPath(hs, "org.lwjgl.glfw.CallbackBridge");
        if (cls == nullptr) {
            LOGE("pointer: CallbackBridge not found via URLClassLoader");
            if (attached) vm->DetachCurrentThread();
            return;
        }
        jmethodID cursor = hs->GetStaticMethodID(cls, "nativeSendCursorPos", "(FF)V");
        jmethodID button = hs->GetStaticMethodID(cls, "nativeSendMouseButton", "(III)V");
        if (cursor == nullptr || button == nullptr) {
            hs->ExceptionClear();
            hs->DeleteLocalRef(cls);
            LOGE("pointer: CallbackBridge native methods missing");
            if (attached) vm->DetachCurrentThread();
            return;
        }
        g_callbackBridgeClass = static_cast<jclass>(hs->NewGlobalRef(cls));
        g_callbackCursorPos = cursor;
        g_callbackMouseButton = button;
        // 窗口尺寸写在 HotSpot 的 CallbackBridge 上：GLFW 的命中范围/光标坐标
        // 都读这些静态字段，为 0 时主菜单按钮点不动。
        auto setInt = [&](const char* name, jint value) {
            jfieldID field = hs->GetStaticFieldID(cls, name, "I");
            if (field != nullptr) hs->SetStaticIntField(cls, field, value);
            if (hs->ExceptionCheck()) hs->ExceptionClear();
        };
        if (g_screenWidth > 0) {
            setInt("windowWidth", g_screenWidth);
            setInt("windowHeight", g_screenHeight);
            setInt("physicalWidth", g_screenWidth);
            setInt("physicalHeight", g_screenHeight);
            LOGI("pointer: window size -> %dx%d", g_screenWidth, g_screenHeight);
        }
        LOGE("pointer: CallbackBridge ready via HotSpot");
        resolveCallbackBridgeFns();
        if (g_fnInputReady != nullptr) {
            g_fnInputReady(hs, cls, JNI_TRUE);
            if (hs->ExceptionCheck()) {
                LOGE("pointer: nativeSetInputReady threw");
                hs->ExceptionClear();
            }
        }
        hs->DeleteLocalRef(cls);
    }
    if (g_fnCursorPos == nullptr || g_fnMouseButton == nullptr) {
        if (attached) vm->DetachCurrentThread();
        return;
    }
    g_fnCursorPos(hs, g_callbackBridgeClass, x, y);
    if (action == 0) {
        g_fnMouseButton(hs, g_callbackBridgeClass, 0, 1, 0);
    } else if (action == 2) {
        g_fnMouseButton(hs, g_callbackBridgeClass, 0, 0, 0);
    }
    if (hs->ExceptionCheck()) {
        hs->ExceptionDescribe();
        hs->ExceptionClear();
    }
    if (attached) vm->DetachCurrentThread();
}

/**
 * 键盘输入（轮盘/虚拟按键用）。走 critical_send_key，和触摸同一条 FCL 输入队列。
 * key 用 GLFW 键码（W=87、A=65、S=83、D=68、Space=32、LeftShift=340、Esc=256）；
 * action 用本工程的 0=抬起、1=按下。
 */
extern "C" JNIEXPORT void JNICALL
Java_dev_mineport_launcher_game_NativeRenderer_nativeSendKey(
        JNIEnv*, jclass, jint key, jint action) {
    LOGI("key: code=%d action=%d", key, action);
    if (!resolveCriticalInput() || g_criticalKey == nullptr) {
        LOGE("key: critical_send_key unavailable");
        return;
    }
    g_criticalKey(key, 0, action == 0 ? 0 : 1, 0);
}

/** 复位输入状态（CANCEL/系统手势后调用，避免按键停在按下态）。 */
extern "C" JNIEXPORT void JNICALL
Java_dev_mineport_launcher_game_NativeRenderer_nativeResetInputState(JNIEnv*, jclass) {
    LOGI("reset input state");
    if (!resolveCriticalInput()) return;
    if (g_criticalReset != nullptr) {
        g_criticalReset();
    }
}


