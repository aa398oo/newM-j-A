# 求助：Android 上进程内 JVM 启动 Forge 1.20.1，`--add-opens` 不生效

## 背景

- 项目：android 启动器（Java 版 Minecraft 启动器壳），自研 JNI 层用 `JNI_CreateJavaVM` 在应用进程内建 JVM，再用 `CallStaticVoidMethod` 调 `main`。
- 运行时：随 APK 打包的 OpenJDK 17（`java.version=17-internal`，aarch64 Linux，Pojav/FCL 同款裁剪 JRE），`java.home` 指向解包目录，`lib/server/libjvm.so` + `lib/modules` 都在。
- 原版 1.20.1 **已经能跑起来**（标题界面正常，Demo 模式），说明 JVM 创建、LWJGL/GL4ES 渲染链路都通。
- 现在要跑 **Forge 1.20.1-47.2.0**（`cpw.mods.bootstraplauncher.BootstrapLauncher`）。

## 现象

```
11-28 15:41:14.297 I MineportGame: JVM 已创建：OpenJDK 17-internal，启动 cpw.mods.bootstraplauncher.BootstrapLauncher
11-28 15:41:14.361 E MineportJNI: main threw: java.lang.ExceptionInInitializerError
  <- java.lang.reflect.InaccessibleObjectException:
     Unable to make field static final java.lang.invoke.MethodHandles$Lookup
     java.lang.invoke.MethodHandles$Lookup.IMPL_LOOKUP accessible:
     module java.base does not "opens java.lang.invoke" to unnamed module @2b2948e2
```

也就是说：JVM 起来了、主类也找到了，但 Forge 的 bootstrap 需要 `java.base/java.lang.invoke` 对 **unnamed module** 开放，`--add-opens` 没起作用。

## Forge `version.json` 里的 `arguments.jvm`（原样）

```
-Djava.net.preferIPv6Addresses=system
-DignoreList=bootstraplauncher,securejarhandler,...,forge-,${version_name}.jar
-DmergeModules=jna-5.10.0.jar,jna-platform-5.10.0.jar
-DlibraryDirectory=${library_directory}
-p
<8 个模块 jar 用 ${classpath_separator} 拼成的一个长字符串>
--add-modules
ALL-MODULE-PATH
--add-opens java.base/java.util.jar=cpw.mods.securejarhandler
--add-opens java.base/java.lang.invoke=cpw.mods.securejarhandler
--add-exports java.base/sun.security.util=cpw.mods.securejarhandler
--add-exports jdk.naming.dns/com.sun.jndi.dns=java.naming
```

（上面 `--add-opens`/`--add-exports`/`--add-modules` 在 json 里都是「选项 + 值」两个独立 token。）

## 已经试过、都不行

1. **`args.ignoreUnrecognized = JNI_FALSE`（初始）** → `JNI_CreateJavaVM` 直接返回 -1，stderr 重定向抓到 `Unrecognized option: -cp`。说明创建阶段确实会因为不认识 launcher 选项而失败。
2. 改成 **`args.ignoreUnrecognized = JNI_TRUE`** → JVM 能建，但 `-cp`、`-p`、`--add-opens` 全部被忽略（因为 JNI 的 `JavaVMOption` 只解析 VM 选项）。
3. **保留 `-D`/`-X` 白名单** → `--add-opens` 被丢，报上面那个 `InaccessibleObjectException`。
4. **去掉白名单，全量传**（只丢未替换的 `${...}`）→ 仍然报 `InaccessibleObjectException`（说明分离形式的 `--add-opens` 在 JNI 层不被识别）。
5. **把「选项 + 值」粘成等号形式**：`--add-opens=java.base/java.lang.invoke=cpw.mods.securejarhandler` → **仍然报同样的错**。
6. **`-p` 映射成 `--module-path=<值>`** → 正在验证（本次改动）。

## 我的疑问

1. `JNI_CreateJavaVM` 的 `JavaVMOption` 到底支不支持 `--add-opens`？HotSpot 的 `Arguments::parse` 里有 `match_option(option, "--add-opens=", &tail)`，理论上认等号形式；但 JNI 入口是否会走 `JLI_PreprocessArg` 那条 launcher 路径？
2. 如果 JNI 层确实不认，**有没有等价的可传项**？比如：
   - `-XX:+IgnoreUnrecognizedVMOptions` 管不管这类 `--add-*`？
   - 能不能用 `-Djdk.module.addOpens=...` 之类的内部属性？
   - 或者 `--patch-module` / `-Xbootclasspath/a` 绕开？
3. Forge 的 target 是具名模块 `cpw.mods.securejarhandler`（来自 `-p` 的 module path）。如果 `--module-path` 没生效、`cpw.mods.securejarhandler` 这个模块不存在，`--add-opens` 会不会被静默忽略？**是不是应该先确认 module path 里真的有这个模块**？
4. 有没有更稳的做法：**不在 JNI 层传 `--add-opens`，而是在 `main` 调用前用 JNI 反射调 `Module.addOpens`**（`java.lang.Module` 的 `addOpens(packageName, other)` 是 public 方法）？这条路在 Android 上可行吗？
5. 参考实现：PojavLauncher / FCL / PCL 在 Android 上跑 Forge 时是怎么处理 `--add-opens` 的？它们的 JVM 启动参数是怎么拼的？

## 环境信息

- JDK：`java.version=17-internal`，`java.vm.name` 未打印，`lib/modules` 122.7MB。
- 设备：Xiaomi 2407FRK8EC，Android 16 (API 36)，arm64-v8a。
- JNI 侧创建参数（Java 侧拼好后传入）：`-Xshare:off`、`-Dos.name=Linux`、`-Dos.arch=aarch64`、`-Djava.home=...`、`-Djava.class.path=...`、`-Duser.home=...`、`-Duser.dir=<实例目录>`、`-Djna.tmpdir=...`，再加 `arguments.jvm` 全部内容（已做 `${}` 替换）。
- `ignoreUnrecognized = JNI_TRUE`。
- `main` 通过 `CallStaticVoidMethod` 调用，异常用 `ExceptionCheck` + `describePendingException` 抓（已展开 cause 链）。

## 想要的结果

一句话：**在 `JNI_CreateJavaVM` 这条路上，怎么让 Forge 需要的 `--add-opens java.base/java.lang.invoke` 真正生效？** 或者给出「虽然 JNI 不认，但可以这样绕」的可行方案（反射 `Module.addOpens` / 改传等价 VM 选项 / 改 BootstrapLauncher 入口参数）。
