#include <jni.h>

// 只为了让 libnio.so 的 DT_NEEDED 能解析。真正的实现（NET_* 符号）在我们
// 的启动路径里不会被调用：离线启动不走 DNS、也不开 InetAddress。
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    return JNI_VERSION_1_6;
}
