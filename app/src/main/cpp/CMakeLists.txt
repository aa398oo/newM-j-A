cmake_minimum_required(VERSION 3.22.1)

project(fclandroid CXX C)

add_library(fclandroid SHARED
        jni_bridge.cpp
        render_bridge.cpp)

# JVM 的 libnio.so 在 DT_NEEDED 里写着 libnet.so（JDK 网络库）。这台裁剪 JRE
# 没带它，boot layer 一解析 java.naming 就 dlopen 失败。这里编一个最小替身：
# 只要能被 dlopen、导出 JNI_OnLoad 就够，真正的网络符号平时用不到（离线启动）。
# 运行期由 JvmLauncher 从 nativeLibraryDir 复制到 files/runtime/lib/。
add_library(net SHARED net_stub.c)

target_compile_features(fclandroid PRIVATE cxx_std_20)

find_library(log-lib log)
find_library(android-lib android)

# 不链接任何外部预编译库：libjvm.so 由运行期 dlopen，
# LWJGL/GL4ES 由用户放到 assets/runtime/natives 后在 Java 侧设置 java.library.path。
target_link_libraries(fclandroid
        ${log-lib}
        ${android-lib}
        dl
        EGL
        GLESv2)
