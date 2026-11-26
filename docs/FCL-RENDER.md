> **已过期提示（2026-11-23）**：本文写于 AWT/caciocavallo 主路径时期。现路线已改为 **FCL lwjgl 3.4.1 + libpojavexec 窗口桥 + GL4ES**，并已在真机跑通主菜单；输入走 `critical_*` 通道。历史内容仅作参考，现行结论见 `BUILD-STATUS.md` 与 `docs/AI-COLLAB.md`。

# FCL 渲染栈研究（D71 追踪）

## 结论先行

FCL 在 Android 上把 Minecraft 的 LWJGL/GLFW 窗口层替换成 **caciocavallo（AWT 的 Android 端口）+ libpojavexec.so（窗口/EGL 桥）**，不是直接用 `GLSurfaceView`。

我们的工程目前只有：
- OpenJDK 17（FCL 1.3.4.7 提取，`libjvm.so` 14.59MB）
- LWJGL 3.4.1 官方 jar
- GL4ES（`libng_gl4es.so`）

缺的是 **org.lwjgl.glfw.CallbackBridge / FCLBridge 的 Java 侧窗口桥**，所以 MC 的 GLFW 会退回桌面窗口路径，最终在 EGL 上崩（D64：`pthread_mutex_lock called on a destroyed mutex`）。

## FCL 的资源与符号

| 资产 | 说明 |
| --- | --- |
| `assets/app_runtime/caciocavallo17/cacio-agent.jar` | 5,480 B，`Premain-Class: com.github.caciocavallosilano.cacio.agent.CTCJavaAgent` |
| `cacio-shared-1.19.1-SNAPSHOT.jar` | 106,230 B |
| `cacio-tta-1.19.1-SNAPSHOT.jar` | 52,825 B |
| `lib/arm64-v8a/libpojavexec.so` | 0.08MB，窗口/EGL 桥 |
| `lib/arm64-v8a/libawt_xawt.so` | 7,168 B |
| `lib/arm64-v8a/libpojavexec_awt.so` | 12,456 B |

`libpojavexec.so` 导出的 JNI 符号（节选）：
- `Java_com_tungsten_fclauncher_bridge_FCLBridge_*`（chdir/dlopen/initializeHooks/setLdLibraryPath/setupBridgeSurfaceAWT…）
- `Java_org_lwjgl_glfw_CallbackBridge_*`（setupBridgeWindow/nativeSendData/nativeSetWindowAttrib…）
- `Java_org_lwjgl_glfw_GLFW_*`
- `Java_android_view_Surface_nativeGetBridgeSurfaceAWT`
- `Java_com_oracle_dalvik_VMLauncher_launchJVM`

## Java 侧源码位置

- `FCL/src/main/java/org/lwjgl/glfw/CallbackBridge.java`（21,304 字符，含 `setupBridgeWindow`、输入/剪贴板/手柄）
- `FCL/src/main/java/com/tungsten/fclauncher/bridge/FCLBridge.java`（15,208 字符）
- 依赖 FCL 内部类：`EfficientAndroidLWJGLKeycode`、`LwjglGlfwKeycode`、`Logging`、`SdlBridge`（手柄，可裁剪）

## 移植路线（待决策）

1. **最小移植**：把 `org.lwjgl.glfw.CallbackBridge` 与 `FCLBridge` 的窗口部分搬进我们的工程（去掉 SDL/手柄/事件总线依赖），编译成补丁 jar，放在 `-Djava.class.path` 最前，覆盖 lwjgl 里的同名类；libpojavexec.so 已在 jniLibs。
2. **风险**：官方 lwjgl 3.4.1 的 GLFW 类仍会 `System.loadLibrary("lwjgl")` 并绑定到 liblwjgl.so；需要确认 JNI 符号解析先命中 libpojavexec.so（FCL 的 lwjgl jar 是 fork 版，可能已把 native 方法改为 builtin/延迟绑定）。
3. **替代**：改用 MobileGlues（FCL 的 `libmobileglues.so` 5.88MB）+ 其 Java 桥；或接受现状，先保证 JVM/主类链路可用，窗口层作为后续里程碑。

## 当前状态

- JVM 崩溃已解决（`android:allowNativeHeapPointerTagging="false"`），`net.minecraft.client.main.Main.main` 已能被执行。
- 下一步需要真机日志确认 cacio 是否初始化；若成功但窗口未建，再走「最小移植」。
