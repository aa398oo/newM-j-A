# JVM 与 JNI 之间的类名靠反射与 JNI 注册约定，不能混淆。
-keep class com.tungsten.fclcore.** { *; }
-keep class dev.mineport.launcher.jvm.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
