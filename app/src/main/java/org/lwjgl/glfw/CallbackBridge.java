package org.lwjgl.glfw;

import java.nio.ByteBuffer;

/**
 * 覆盖 FCL lwjgl fork 里的同名类（callbackbridge-patch.jar 置于 JVM classpath 最前）。
 *
 * 来源：FCL-Team/FoldCraftLauncher 的 FCL/src/main/java/org/lwjgl/glfw/CallbackBridge.java，
 * 这里只保留「libpojavexec.so 期望的 JNI 面」与纯数据字段：
 * - native 声明集合必须和 libpojavexec 的 RegisterNatives 表一致，缺一个就会在 ART 上
 *   触发 JNI DETECTED ERROR（GetStaticMethodID called with pending exception）；
 * - 回调方法签名必须严格匹配：notifyLauncher(I[I)Z、accessAndroidClipboard(ILjava/lang/String;)Ljava/lang/String;、
 *   onGrabStateChanged(Z)V、onDirectInputEnable()V。
 *
 * 删去的 FCL 专有依赖：FCLApp / SdlBridge / SDLActivity / FCLBridge / keycodes / Logging。
 * 输入事件目前由 GLFW 的 native 直接驱动；SDL 双通道与游戏手柄待接入。
 */
public class CallbackBridge {

    public static volatile int windowWidth;
    public static volatile int windowHeight;
    public static volatile int physicalWidth;
    public static volatile int physicalHeight;
    public static volatile float mouseX;
    public static volatile float mouseY;
    public static volatile float deltaX;
    public static volatile float deltaY;
    public static volatile boolean holdingAlt;
    public static volatile boolean holdingCapslock;
    public static volatile boolean holdingCtrl;
    public static volatile boolean holdingNumlock;
    public static volatile boolean holdingShift;
    public static volatile boolean isGrabbing;
    public static volatile boolean sGamepadDirectInput;

    // GLFW direct gamepad 共享缓冲：libpojavexec 需要这两个 ByteBuffer 字段存在。
    public static ByteBuffer sGamepadButtonBuffer;
    public static ByteBuffer sGamepadAxisBuffer;

    // ---- libpojavexec 注册的 native 面（与 FCL 源码保持一致） ----

    public static native void setupBridgeWindow(Object surface);

    public static native void nativeSetGrabbing(boolean grab);

    public static native boolean nativeSetInputReady(boolean ready);

    public static native String nativeClipboard(int op, byte[] data);

    public static native void nativeNotifyLauncher(int type, int... action);

    public static native void nativeEnableGamepadDirectInput();

    public static native ByteBuffer nativeCreateGamepadButtonBuffer();

    public static native ByteBuffer nativeCreateGamepadAxisBuffer();

    public static native void nativeSetWindowAttrib(int attrib, int value);

    public static native int getFps();

    public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);

    public static native boolean nativeSendChar(char codepoint);

    public static native boolean nativeSendCharMods(char codepoint, int mods);

    public static native void nativeSendKey(int key, int scancode, int action, int mods);

    public static native void nativeSendCursorPos(float x, float y);

    public static native void nativeSendMouseButton(int button, int action, int mods);

    public static native void nativeResetInputState();

    public static native void nativeSendScroll(double xoffset, double yoffset);

    public static native void nativeSendScreenSize(int width, int height);

    // ---- libpojavexec 回调 JVM 的方法（签名必须精确） ----

    /** SDL 通知入口；没有 SDL 桥时只返回 false。 */
    public static boolean notifyLauncher(int type, int... action) {
        return false;
    }

    /** 剪贴板回调；当前只回显文本，接入 Activity 上下文后再走 ClipboardManager。 */
    public static String accessAndroidClipboard(int type, String text) {
        return text;
    }

    /** 直连手柄输入启用回调。 */
    public static void onDirectInputEnable() {
        sGamepadDirectInput = true;
    }

    /** 鼠标抓取状态变化回调。 */
    public static void onGrabStateChanged(boolean grabbing) {
        isGrabbing = grabbing;
        deltaX = 0f;
        deltaY = 0f;
    }

    // ---- 纯 Java 辅助 ----

    public static boolean isGrabbing() {
        return isGrabbing;
    }

    public static void setWindowSize(int width, int height) {
        windowWidth = width;
        windowHeight = height;
    }

    public static void setPhysicalSize(int width, int height) {
        physicalWidth = width;
        physicalHeight = height;
    }

    public static void setCursorPos(float x, float y) {
        deltaX = x - mouseX;
        deltaY = y - mouseY;
        mouseX = x;
        mouseY = y;
    }

    public static void putKeyEvent(int key, int scancode, boolean isDown, int modifiers) {
        nativeSendKey(key, scancode, isDown ? 1 : 0, modifiers);
    }

    public static void putMouseButtonEvent(int button, boolean isDown, int modifiers) {
        nativeSendMouseButton(button, isDown ? 1 : 0, modifiers);
    }
}
