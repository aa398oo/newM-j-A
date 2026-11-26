#include <jni.h>

#include <android/log.h>
#include <GLES2/gl2.h>

#include <cmath>
#include <string>

#define LOG_TAG "MineportRender"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

GLuint g_program = 0;
GLuint g_vbo = 0;
GLint g_timeUniform = -1;
int g_width = 0;
int g_height = 0;
bool g_ready = false;

const char* kVertexShader = R"(
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
)";

const char* kFragmentShader = R"(
precision mediump float;
varying vec2 vUv;
uniform float uTime;
void main() {
    vec3 a = vec3(0.16, 0.42, 0.29);
    vec3 b = vec3(0.05, 0.10, 0.22);
    float wave = 0.5 + 0.5 * sin(uTime * 0.8 + vUv.x * 4.0 + vUv.y * 2.0);
    vec3 color = mix(b, a, clamp(vUv.y * 0.7 + wave * 0.3, 0.0, 1.0));
    gl_FragColor = vec4(color, 1.0);
}
)";

GLuint compile(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (ok != GL_TRUE) {
        char log[512];
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("shader compile failed: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_dev_mineport_launcher_game_NativeRenderer_nativeInit(JNIEnv* env, jclass, jstring glVersion) {
    const char* versionChars = env->GetStringUTFChars(glVersion, nullptr);
    LOGI("nativeInit on %s", versionChars != nullptr ? versionChars : "unknown");
    if (versionChars != nullptr) env->ReleaseStringUTFChars(glVersion, versionChars);

    GLuint vertex = compile(GL_VERTEX_SHADER, kVertexShader);
    GLuint fragment = compile(GL_FRAGMENT_SHADER, kFragmentShader);
    if (vertex == 0 || fragment == 0) return -1;

    g_program = glCreateProgram();
    glAttachShader(g_program, vertex);
    glAttachShader(g_program, fragment);
    glBindAttribLocation(g_program, 0, "aPos");
    glLinkProgram(g_program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);

    GLint linked = GL_FALSE;
    glGetProgramiv(g_program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[512];
        glGetProgramInfoLog(g_program, sizeof(log), nullptr, log);
        LOGE("program link failed: %s", log);
        return -2;
    }

    static const GLfloat vertices[] = {
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f
    };
    glGenBuffers(1, &g_vbo);
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);

    g_timeUniform = glGetUniformLocation(g_program, "uTime");
    g_ready = true;
    return 0;
}

// jni_bridge.cpp 里定义：把 Surface 尺寸同步成窗口尺寸真源（并标记待重发）。
extern "C" void bridgeOnSurfaceSize(int width, int height);

extern "C" JNIEXPORT void JNICALL
Java_dev_mineport_launcher_game_NativeRenderer_nativeResize(JNIEnv*, jclass, jint width, jint height) {
    g_width = width;
    g_height = height;
    glViewport(0, 0, width, height);
    // Surface 变了意味着窗口尺寸也变了：GLFW 的窗口尺寸、触摸坐标系、
    // CallbackBridge 的 windowWidth/Height 必须一起更新，否则点击整体偏移。
    bridgeOnSurfaceSize(width, height);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_mineport_launcher_game_NativeRenderer_nativeDrawFrame(JNIEnv*, jclass) {
    if (!g_ready) return -1;
    static float elapsed = 0.0f;
    elapsed += 1.0f / 60.0f;

    // 第 3 步验收：前 120 帧（约 2 秒）只做纯色清屏。
    // 屏幕上出现整块 #0E1116，就说明 EGL 配置、glViewport、SwapBuffers 整条链路通了；
    // 之后继续画渐变，证明着色器与属性绑定也正常。
    static int bootFrames = 0;
    if (bootFrames < 120) {
        ++bootFrames;
        if (bootFrames == 1) {
            LOGI("EGL/GL 链路验收：纯色清屏 120 帧，%dx%d", g_width, g_height);
        }
        glClearColor(0.055f, 0.067f, 0.086f, 1.0f);  // #0E1116
        glClear(GL_COLOR_BUFFER_BIT);
        return 0;
    }

    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(g_program);
    if (g_timeUniform >= 0) glUniform1f(g_timeUniform, elapsed);
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(0);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mineport_launcher_game_NativeRenderer_nativeDestroy(JNIEnv*, jclass) {
    if (g_program != 0) {
        glDeleteProgram(g_program);
        g_program = 0;
    }
    if (g_vbo != 0) {
        glDeleteBuffers(1, &g_vbo);
        g_vbo = 0;
    }
    g_ready = false;
}

// 宿主 Surface 的引用。阶段目标：先把窗口句柄收好，等 JVM 侧渲染桥
// （pojavexec 的 setupBridgeWindow / EGL 上下文）接入时直接用这里存的 Surface。
static jobject g_hostSurface = nullptr;

// 供 jni_bridge.cpp 把 Android Surface 交给 HotSpot 侧的 CallbackBridge.setupBridgeWindow。
// 返回的是全局引用，调用方不要 DeleteLocalRef/DeleteGlobalRef。
extern "C" jobject mineportHostSurface() { return g_hostSurface; }

extern "C" JNIEXPORT void JNICALL
Java_dev_mineport_launcher_game_NativeRenderer_nativeAttachSurface(JNIEnv* env, jclass, jobject surface) {
    if (g_hostSurface != nullptr) {
        env->DeleteGlobalRef(g_hostSurface);
        g_hostSurface = nullptr;
    }
    if (surface != nullptr) {
        g_hostSurface = env->NewGlobalRef(surface);
    }
    LOGI("nativeAttachSurface: host=%p", g_hostSurface);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mineport_launcher_game_NativeRenderer_nativeDetachSurface(JNIEnv* env, jclass) {
    if (g_hostSurface != nullptr) {
        env->DeleteGlobalRef(g_hostSurface);
        g_hostSurface = nullptr;
    }
    LOGI("nativeDetachSurface: released");
}
