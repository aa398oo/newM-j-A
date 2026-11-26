cmake_minimum_required(VERSION 3.22.1)

project(fclandroid CXX)

add_library(fclandroid SHARED
        jni_bridge.cpp
        render_bridge.cpp)

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
