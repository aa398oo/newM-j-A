# 进度存档

更新时间：2026-11-22

## 当前结论（2026-11-23 深夜）

- **渲染已通**：真机渲染出 MC 1.20.1 主菜单（FCL lwjgl 3.4.1 + libpojavexec 窗口桥 + GL4ES）。
- **输入已改造、待真机验证**：`GameHostLayout` 根容器分流 → `TouchInjector` 双态手势机 → `nativeSendPointer` → `critical_send_cursor_pos/mouse_button`（FCL 队列）；反射 `enn/eno` 仅兜底。
- **进世界未验**：免登录入口是主菜单的 Play Demo World（1.20.1 无 `--demo` 参数，离线即 Demo 形态）。

## 一、本机构建环境（已就绪，不进仓库）

全部在 `android/.toolchain/`（`.gitignore` 已排除）：

| 组件 | 路径 | 状态 |
| --- | --- | --- |
| JDK 17.0.21 | `.toolchain/jdk/jdk-17.0.21+9` | 可用 |
| Android SDK | `.toolchain/sdk` | platforms/android-35、build-tools/35.0.1 |
| NDK 27.0.12077973 | `.toolchain/sdk/ndk/27.0.12077973` | 可用 |
| Gradle 8.9 | `.toolchain/gradle-extract/gradle-8.9` | 可用 |
| `local.properties` | `android/local.properties` | 指向 `.toolchain/sdk` |

运行时二进制（JRE17 等）在 `app/src/main/assets/runtime/`（约 110MB）与
`app/src/main/jniLibs/arm64-v8a/`（约 26MB），未进 git。

## 二、本机构建命令

```powershell
$env:TEMP="X:\android\.toolchain\tmp"; $env:TMP="X:\android\.toolchain\tmp"
$env:JAVA_HOME="X:\android\.toolchain\jdk\jdk-17.0.21+9"
$env:GRADLE_USER_HOME="X:\android\.toolchain\gradle-home"
& "X:\android\.toolchain\gradle-extract\gradle-8.9\bin\gradle.bat" -p X:\android assembleDebug --console=plain
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`（debug 签名，签名配置在
`app/build.gradle.kts` 的 `debugLocal`，keystore 为 `.keystore/debug.keystore`）。

## 三、本机构建已踩过的坑

1. 代理并发下载全部断流 → 改国内镜像直连（清华 Adoptium、腾讯 Gradle、dl.google.com）。
2. Gradle daemon `java.net.BindException` → 清空 `GRADLE_USER_HOME/daemon` 与 `~/.gradle/daemon`。
3. AGP 拒绝中文路径（`适配项目`）→ 用 `subst X: C:\Users\<you>\Downloads\适配项目` 构建。
4. Kotlin 编译器要写系统 TEMP 被沙箱拒绝 → `TEMP/TMP` 指向 `X:\android\.toolchain\tmp`。
5. Kotlin 嵌套注释：KDoc 里出现 `*.so`、`runtime/**` 会触发 → 改写文案。
6. `android.content.pm.SystemFeatures` 不可用 → DeviceProfile 置空。
7. `AttachCurrentThread` 参数类型 → 传 `JNIEnv**`。
8. `C:\Users\<you>\.android\debug.keystore.lock` 不可写 → 改用工程内 keystore（见二）。

## 四、真机首测结论（2026-11-22，Android 16 / MT6989）

设备：Xiaomi 2407FRK8EC（Redmi K70 Ultra）、MediaTek MT6989、Android 16 / API 36、
arm64-v8a、1220x2712、`getconf PAGE_SIZE` = 4096（16KB 页风险排除）。

已跑通：

- 首页设备卡、实例创建（1.20.1 / Java 17 / 3072MB）、实例列表、删除按钮。
- 下载页：BMCLAPI → Mojang → 缓存三级回退，925 个版本、搜索过滤（含全角标点归一化）。
- 安装 1.20.1：54 个客户端/libraries/asset index 文件 + 3600 个 resources 全部走通
  （assets 阶段约 1.4 文件/秒，中断后保留 `.part` 续传）。
- GameActivity：GLSurfaceView 120fps；删掉重复的 `setRenderer` 调用后不再闪退。
- 动作轮盘可见并可选扇区。
- JVM：`JVM ready, java.version=17.0.10-internal`；主类
  `net.minecraft.client.main.Main` 加载成功，`invoking … with 29 args`。

阻塞：VM Thread 在 JVM 内部 `free()` 处 SIGABRT

```
Abort message: 'Pointer tag for 0x738a08a1a0 was truncated, see
'https://source.android.com/devices/tech/debug/tagged-pointers'.'
backtrace: #00 abort+156 libc、#01 free+104 libc、#02 libjvm.so+0x6c8a3c
```

试过但无效（按时间顺序）：

1. 全量 preload `lib/` 下所有 `.so`（RTLD_GLOBAL）→ 崩得更早；
2. JVM 创建后再 preload（libjava/libverify/libnet/libnio/libzip/libjimage/management）→ 仍崩；
3. 最小集只 preload libjava+libnet → 仍崩；
4. 去掉 `-XX:-UseCompressedOops` / `-XX:-UseCompressedClassPointers`（回默认）→
   主类能加载、main 被调用，随后仍在 VM Thread 崩；
5. `prctl(PR_SET_TAGGED_ADDR_CTRL, 0)` 关闭 tagged address → JVM 初始化卡 50 秒后
   在 DefaultDispatch 线程崩，比不关更糟。

结论：FCL app_runtime 里的 JRE17（android-openjdk 构建）与 Android 16 的 tagged
pointer 不兼容，问题在 JVM 内部，不是 classpath/加载顺序。

下一步候选（未做）：

- 换 PojavLauncher 的 Android JRE17 构建。注意
  `jre17-arm64-20220817-release.tar.xz` 是 macOS 产物（lib/server 下是 `libjvm.dylib`），
  不能用；要找 Android 构建的资产。
- `android_mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE)`
  关闭 heap tagging（与 prctl 不同，只影响 malloc）。
- `-Xlog:all=debug:file=…` 把 JVM 内部日志落盘，定位 abort 前的最后动作。
- 短期无解时，把「JVM 崩溃」变成可解释的错误页，P1 验收推迟。

## 五、2026-11-22 代码完善（不依赖手机）

- 输入桥：`input/InputBridge.kt` + `jni_bridge.cpp` 的
  `Java_dev_mineport_launcher_input_InputBridge_nativeAction/nativeActionMask`。
  轮盘改「按住/松开」语义（切换扇区先 release 再 press），native 侧维护 8 位掩码，
  FCLCore 接入时直接翻译掩码，UI 不用改。
- 安装可取消：安装对话框加「取消」，取消后 `.part` 保留、重装断点续传。
- 下载并发 8 → 12；请求加 `Accept-Encoding: identity`（Range 续传必须拿原始字节）。
- 失败不空转：JVM 启动失败/游戏退出后 `setRenderMode(RENDERMODE_WHEN_DIRTY)`。
- 安装对话框文案：删掉「assets 资源与启动参数由 FCLCore 接管」的错误说法。

### 第二轮完善（同日，仍不依赖手机）

- **启动里程碑**：新增 `jvm/StartupTrace.kt`，每次启动往 `filesDir/crash/startup.txt`
  逐阶段追加一行（`start → runtime-staged → plan-ok → jvm-creating → jvm-ready →
  main-invoking → main-exit`）。native 层 abort 时 Java 层写不了栈，但里程碑已经落盘；
  设置页新增「上次启动进度」卡片，闪退后回到 App 就能看到停在哪一步。
- **native 实验**：`jni_bridge.cpp` 在创建 JVM 前用 `dlsym` 调
  `android_mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE)`，
  只关 bionic 的 heap tagging（不动进程级 tagged address，prctl 已证明更糟）。
  失败静默，不影响启动。
- **主页固定竖屏**：`MainActivity` 加 `screenOrientation="portrait"`。真机实测从
  横屏 GameActivity 退回主页时会跟着横屏，现已修掉。
- **JVM 参数**：补 `-XX:-UsePerfData`（Android 上 /tmp 不可写）与
  `-Djava.awt.headless=true`（避免桌面 AWT 探测）。

### 第三轮完善（同日，产品功能补口）

- **实例设置弹窗**：点实例卡片打开，可改渲染后端（自动 / GL4ES / Zink）、模组加载器
  （原版 / Fabric / Forge / NeoForge / Quilt）、内存（512–4096 MB）。之前创建对话框写着
  「可在实例创建后调整」但没有入口，现已补齐。
- **模组安装入口**：实例设置里「安装模组…」→ Modrinth 搜索 + 一键安装（自动补
  required 前置，写入 `instances/<gameDir>/mods`）。原版实例会提示先改加载器。
- **CI 两处修正**：`build-tools` 对齐到 35.0.1（与 build.gradle.kts 一致）；新增
  「Ensure debug keystore」步骤，仓库没提交 `.keystore/debug.keystore` 时在 CI 生成，
  避免云端 validateSigningDebug 失败。



## 六、代码完成度（截至本轮）

已完成：原版启动路径（version JSON → classpath → main 参数 → JNI 调用）、安装器
（client/libraries/asset index/resources）、下载器、模组（Modrinth）、短码、输入桥、
光影策略、UI 动效、崩溃记录（filesDir/crash/last-crash.txt）。

未完成：JVM 在 Android 16 的崩溃（P1 生死线）、FCLCore 的 JVM 内入口、短码服务、
模组 UI、光影试跑、多实例并行与内存调度。

## 七、2026-11-22 15:48 突破：Android 16 heap tagging

**根因确认**：Android 16 默认给 64 位进程启用 native heap pointer tagging，bionic malloc 返回的指针高 8 位带 tag（实测 0xb4）。FCL 的 JRE17 内部（libjvm.so+0x6c8a3c）把带 tag 的指针存进只保留低位的结构，free 时 tag 已被截断，触发 `Pointer tag for 0x738a… was truncated` 而 abort。与 classpath、加载顺序、解释器参数都无关。

**有效修复（已落地）**：
- `AndroidManifest.xml` 的 `<application>` 加 `android:allowNativeHeapPointerTagging="false"`（API 30+）。系统在进程启动时读取该属性，ART/bionic 从此不给 malloc 返回值打 tag。
- 实测 probe：修复前 `malloc probe … tag=0xb4`；修复后 `tag=0x0`（连新线程也都是 0）。
- 运行期调用 `android_mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, NONE)` **无效**（返回 0 只是接受调用，新线程仍 tag=0xb4），必须用 manifest 属性。

**当前进展**：JVM 不再崩 free()；`startup.txt` 走到 `main-invoking:net.minecraft.client.main.Main`，进程存活、main 真正执行。

**新阻塞点（下一阶段）**：GLThread 112 在 `GLSurfaceView.swap → android::jni_eglSwapBuffers` 时 SIGABRT：`FORTIFY: pthread_mutex_lock called on a destroyed mutex`。原因是 MC 的 GL4ES/EGL 与 GLSurfaceView 各自维护 EGL 客户端、争用同一 surface。下一步把 GameSurfaceView 从 GLSurfaceView 换成裸 SurfaceView（或由 FCLCore/caciocavallo 接管 EGL 与窗口），只保留一个 EGL 客户端。
## 八、第四轮完善（2026-11-22 15:40–15:56，全部功能开发目标下）

| 功能 | 落地内容 | 验证 |
| --- | --- | --- |
| 短码分享 | 实例设置「分享」区生成 6 位短码 + 复制；实例页右上「短码导入」按短码复刻实例（版本/加载器/内存/后端） | 构建通过；待真机 |
| 光影 | `GameInstance.wantShaders` + 实例设置「光影（Zink 试跑）」开关；启动时 `ShaderPolicy.plan` 决定 zink/gl4es，GLSurfaceView 用 3.3/2.0 上下文；JVM 带 `-Dfcl.shaders=on/off` | 构建通过 |
| Fabric 加载器 | `install/FabricLoaderInstaller.kt`：meta.fabricmc.net 取 loader 版本与 profile，合并原版 libraries/arguments/mainClass 成自包含版本，下载 Fabric 库；`MinecraftLauncher` 支持 `inheritsFrom` 链与 `jar` 字段回找原版 jar；实例设置里「安装 Fabric 加载器」 | 构建通过 |
| 诊断导出 | `crash/DiagnosticsExporter.kt` + FileProvider：设备信息、startup.txt、last-crash.txt、JVM 版本打包分享；设置页「诊断与日志」卡 | 构建通过 |
| 联机基础 | `net/LanDiscovery.kt`：监听 224.0.2.60:4445（WifiManager.MulticastLock），解析 `[MOTD]…[/MOTD][AD]port[/AD]`；设置页「联机」卡扫描/复制地址；D37 不自建中转 | 构建通过 |
| 许可/关于 | 设置页「关于」补 GPL-3.0 与内置组件许可（OpenJDK/LWJGL/GL4ES/Compose/Fabric） | — |

构建：`assembleDebug` 通过（1m 左右）。
## 九、第五轮完善（2026-11-22 16:10–16:25）

| 功能 | 内容 |
| --- | --- |
| Quilt | `install/QuiltLoaderInstaller.kt`：meta.quiltmc.net 取 loader 与 profile，合并自包含版本并下载库；实例设置里 FABRIC/QUILT 共用「安装加载器」按钮，FORGE/NEOFORGE 提示暂未接入 |
| GL 阻塞根因（D71） | FCL 的 LWJGL fork 在 GLFW 静态初始化里 `System.loadLibrary("pojavexec")` + `loadNative(GLFW.class, "org.lwjgl.glfw", "libpojavexec.so")`；libpojavexec.so 导出 `Java_org_lwjgl_glfw_CallbackBridge_setupBridgeWindow`、`Java_android_view_Surface_nativeGetBridgeSurfaceAWT`、`Java_com_tungsten_fclauncher_bridge_FCLBridge_*`，需要 FCL 的 Java 侧窗口桥才能把 ANativeWindow 交给 GL4ES。工程只带 lwjgl/gl4es，缺这套桥 → 下一步移植 FCLBridge/GameBoard 最小集或换 MobileGlues 方案 |

构建产物：`app-debug.apk` 79738545 字节，sha256 `2B6D19E7DFC6A567B0703CBE51631756B72D46F733CB46166FAB86DEE3D548C6`。
## 九A、caciocavallo 资源接入与 JIT 恢复（2026-11-22 16:30）

- 从 FCL 1.3.4.8 提取 `assets/app_runtime/caciocavallo17/` 的 3 个 jar（cacio-agent / cacio-shared-1.19.1-SNAPSHOT / cacio-tta-1.19.1-SNAPSHOT）到 `android/app/src/main/assets/runtime/cacio/`；RuntimeStager 会整体解到 `filesDir/runtime/cacio/`。
- 提取 FCL 的 `lib/arm64-v8a/libawt_xawt.so`（7,168 字节）与 `libpojavexec_awt.so`（12,456 字节）到 `android/app/src/main/jniLibs/arm64-v8a/`，由 APK 的 lib 目录统一解出（AWT 的 Android 后端入口）。
- `JvmLauncher`：`JvmLaunchSpec` 新增 `useCacio: Boolean = false`；开启时自动把 cacio 两个 jar 前置到 `-Djava.class.path`、加 `-javaagent:<runtime>/cacio/cacio-agent.jar`、`-Djava.awt.headless=false`；关闭时保持原 headless=true 路径。
- 同时移除 `-XX:TieredStopAtLevel=1` 与 `-XX:CICompilerCount=2`：heap tagging 已由 manifest 的 allowNativeHeapPointerTagging=false 在进程启动时关闭，恢复默认分层编译（C2），渲染线程不再跑在 C1 代码上，避免掉帧。
- 构建 52s 通过；APK 82,902,706 字节；APK 内确认 `assets/runtime/cacio/*.jar` 与 `lib/arm64-v8a/libawt_xawt.so`、`libpojavexec_awt.so` 均在。
- 待真机验证：cacio 是否能让 AWT 初始化成功、MW 的 GLFW 是否走到 Android EGL bridge。
## 十、实例管理增强（2026-11-22 16:50）

- 删除实例前弹确认框，说明「只删配置，游戏目录里的存档与模组保留」。
- 实例页新增「导入版本」：用系统文件选择器挑 ersions/<id>/ 目录（桌面端官方 installer 装好的 Forge/NeoForge/整合包），install/VersionImporter.kt 递归复制进应用私有 iles/versions/<id>/，校验 <id>.json 存在后自动创建同名实例。
- 依赖：ActivityResultContracts.OpenDocumentTree + DocumentsContract 遍历，无新增第三方库。
- 构建 58s 通过。
## 十一、整合包 zip 导入（2026-11-22 17:00）

- 新增 install/ZipVersionImporter.kt：读取标准整合包 zip（ersions/<id>/<id>.json），解压到 cacheDir/zip-import（带路径穿越校验），识别唯一版本目录后整体复制进 iles/versions/<id>/，并在实例页自动创建同名实例。
- 实例页 TopAppBar 三个入口：「导入目录」（SAF 选 versions/<id> 目录）、「导入 zip」（整合包）、「短码导入」。
- 限制：一次只处理一个版本；不处理 overrides/mods（模组仍走实例的「安装模组」）。
- 构建 1m8s 通过。
## 十二、模组列表与导入补全（2026-11-22 17:05）

- ui/mods/ModsDialog.kt：新增「已安装 N 个模组文件」区，列出 mods 目录里的 .jar（最多显示 6 个，其余折叠计数），每个可「删除」并即时刷新。
- 实例页三个导入入口已齐全：导入目录（SAF 目录树）、导入 zip（标准整合包）、短码导入。
- 构建 1m12s 通过；APK 82,902,706 字节。
## 十三、窗口桥资产接入（2026-11-22 17:10）

- 把 FCL 的 libpojavexec.so（82,584 字节，窗口/EGL 桥）复制进 ndroid/app/src/main/jniLibs/arm64-v8a/，随 APK 打包。
- JvmLauncher.start 在 JVM 创建成功后 System.loadLibrary("pojavexec")（失败只打警告，不影响现有链路），为后续移植 org.lwjgl.glfw.CallbackBridge 做准备。
- 新增 docs/VERIFY.md（真机 9 项验证清单，含 adb 命令与预期日志）与 docs/FCL-RENDER.md（FCL 渲染栈研究：caciocavallo17 + libpojavexec + lwjgl fork，移植路线与风险）。
- 构建 1m12s 通过；APK 含 libpojavexec.so。
## 十四、验证脚本（2026-11-22 17:15）

- 新增 ndroid/scripts/verify-device.ps1：一键安装 -> 启动 -> 可选自动点进实例并播放 -> 抓 startup.txt / Mineport* 日志 / crash buffer / last-crash.txt，输出 docs/verify-report-<时间戳>.txt。
- 用法（PowerShell 5.1 需绕过执行策略）：
  powershell -NoProfile -ExecutionPolicy Bypass -File X:\android\scripts\verify-device.ps1 -AutoTap
- 无设备时退出码 2，不产生报告。## 十五、静态检查与脚本修正（2026-11-22 17:20）

- `scripts/check-sources.mjs`：排除 `.toolchain`、`.runtime-cache`、`.idea`、`.kotlin`（此前扫到 NDK 头文件导致 8 个假阳性）。
- 静态检查结果：`check-jni` 12 条 Kotlin external 声明 vs 12 个 C++ 导出，0 错误；`check-sources` 45 文件 6,172 行，0 不平衡。
- 正确用法（脚本第一个参数是项目根目录）：
  `node X:\android\scripts\check-jni.mjs X:\android`
  `node X:\android\scripts\check-sources.mjs X:\android`
## 十五、Pojav Android JRE17 替换实验（2026-11-22 17:30）

- 背景：FCL JRE17 在 Android 16 / HyperOS（API 36）上，JVM 创建成功、main 被调用后约 6 秒，ART 的 hwuiTask0 线程 SIGABRT：`FORTIFY: pthread_mutex_lock called on a destroyed mutex`；从 tombstone 看不是 class 加载或 JRE 裁剪问题。
- 实验：改用 PojavLauncherTeam 的 Android JRE17（release `jre17-ec28559`，资产 `jre17-arm64-20210825-release.tar.xz`，38,065,004 字节；`lib/server/libjvm.so` 20,205,120、`lib/modules` 128,687,007，`JAVA_VERSION=17-internal`、`JAVA_VERSION_DATE=2021-09-14`），整体替换 `assets/runtime/lib`。
- 构建：`compressDebugAssets` 在 4GB 堆下 OOM，`org.gradle.jvmargs` 提到 `-Xmx6144m` 后通过；APK 131,748,203 字节。
- 结果：JVM 创建成功（`JVM created: java.version=17-internal`）、`invoking net.minecraft.client.main.Main.main with 29 args`；数秒后仍崩在同一处（`Fatal signal 6 (SIGABRT) in tid (hwuiTask0), pid 22936 (:game)`），主进程存活（`android:process=":game"` 崩溃隔离生效，回到实例页不闪退）。
- 判定：崩溃与 JRE 版本无关，是 HotSpot 与 Android 16 ART/bionic 的互操作；而且即使 JVM 不崩，当前链路仍缺「Surface → LWJGL/GLFW」窗口桥（官方 lwjgl jar 里没有 `CallbackBridge.setupBridgeWindow`，libpojavexec 需要它的 Java 侧），MC 还没有可渲染窗口。
- 已验证可用：启动器壳、925 版本在线清单与安装（可取消、12 路并发、断点续传、SHA-1）、实例管理（导入目录/zip、设置、删除确认、Java 版本推断）、短码分享、光影判定与开关、Fabric/Quilt 加载器、Modrinth 模组搜索安装、诊断导出、LAN 发现、动作轮盘 8 位输入掩码、崩溃隔离与 startup 里程碑。
- P1 渲染剩余工作：A. 移植 FCL 的 CallbackBridge/GLFW 窗口桥（需覆盖 lwjgl 同名类）；B. 用 caciocavallo17 让 AWT 接管（useCacio 开关已就位，需启用并补 awt 桥）；C. 用 Android 14 以下设备做对照，确认是否 Android 16 回归。

## 十六、渲染突破（2026-11-22 晚，真机）

- **MC 1.20.1 首次在真机渲染出主菜单**：`Welcome to Minecraft! Would you like to enable the Narrator...` 界面（截图 screen-game6.png），`:game` 进程稳定，鼠标/ESC 之外的渲染链路全部打通。
- 修复链（按顺序）：① `AndroidManifest` 加 `android:allowNativeHeapPointerTagging="false"`（heap tagging 是 JVM 崩溃根因）；② libpojavexec 的 `initializeHooks`（dlsym 直调，绕 ART/HotSpot 类加载器）+ `setupExitTrap`（dlsym libfcl 的 Java_com_tungsten_fclauncher_bridge_FCLBridge_setupExitTrap + NewObject）+ 自注册 `atexit(fflush+_exit)` 兜底；③ `GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT`：`-Dorg.lwjgl.opengl.libname=libng_gl4es.so`（PojavRendererInit 识别 GL4ES）+ lwjgl 3.4.1 core（lwjgl.jar）+ 模块 jar（openal/stb/tinyfd/freetype）+ 窗口尺寸 2712x1220（`GLFW.internalChangeMonitorSize`、`surfaceChanged` 重挂 `setupBridgeWindow`）；④ quickPlay 参数污染：`${...}` 未替换时连同前面的 `--switch` 成对丢弃（1.20.1 的 `--quickPlayPath ${quickPlayPath}` 之前只剩光杆开关，MC 落到 "Failed to Quick Play / Unknown host"）。
- 当前遗留：**主菜单点击无效**。已实现 `NativeRenderer.nativeSendPointer`（在 ART 的 JNIEnv 里 `FindClass("org/lwjgl/glfw/CallbackBridge")`，调 `nativeSendCursorPos`/`nativeSendMouseButton`，绕开 HotSpot 类加载器）与 `nativeSetScreenSize`（写 `windowWidth/Height`、`physicalWidth/Height` 静态字段 + `nativeSetInputReady(true)`）。日志确认：`pointer: x=1356 y=1127 action=0/2`、`screen size: 2712x1220` 都到达且无 JNI abort，但 Continue 仍不响应。
- 注意：不要调 `nativeSendScreenSize`——libpojavexec 没注册这个 native，调用会抛 `UnsatisfiedLinkError` 并在 pending exception 下让下一次 `GetStaticMethodID` 直接 JNI abort（真机上就是 19735/20220/20592 三次 SIGABRT）。
- 下一步：对照 FCL 的输入映射（`CallbackBridge.sendCursorPos` 前后的 `nativeSetInputReady`/`nativeSetGrabbing` 状态、事件队列的消费线程）继续排查；或直接以 FCL 的完整 CallbackBridge + libpojavexec 注册面为准重做输入桥。

## 十七、输入通道转向 FCL 正统 critical_*（2026-11-23）

- **发现**：`llvm-nm -D libpojavexec.so` 显示 FCL 3.4.1 除了 `Java_*` JNI 符号外，还导出整族普通 C 函数：`critical_send_cursor_pos`、`critical_send_mouse_button`、`critical_send_key`、`critical_send_char`、`critical_send_char_mods`、`critical_send_scroll`、`critical_send_screen_size`、`critical_reset_input_state`、`critical_set_stackqueue`，以及 `noncritical_*` 副本。之前只扫 `Java_` 前缀，漏了这一族，才误判「只能反射 MouseHandler」。
- **签名**（`llvm-objdump -d` 反汇编核对）：`void critical_send_cursor_pos(float, float)`；`void critical_send_mouse_button(int button, int action, int mods)`（内部 `buttonStates[button] = action`）；`void critical_send_key(int key, int scancode, int action, int mods)`；`void critical_send_scroll(float, float)`；`void critical_send_screen_size(int, int)`；`void critical_set_stackqueue(int use)`（内部 `useStackQueue = (use & 0xff) != 0`）。
- **接入**（`app/src/main/cpp/jni_bridge.cpp`）：新增 `resolveCriticalInput()`，首次触摸时 `dlopen("libpojavexec.so", RTLD_NOW)` + 逐项 `dlsym`；拿到后先调 `critical_set_stackqueue(1)` 切到「事件入队列、渲染线程 pump」模式（避免 UI 线程直接执行 MouseHandler 回调与渲染线程抢字段）；`nativeSendPointer(x,y,action)` 优先走 `critical_send_cursor_pos` + `critical_send_mouse_button(0, action==0?1:0, 0)`，失败才退回反射 `enn/eno` 兜底。首次走 critical 通道时补发 `critical_send_screen_size(2712,1220)`。
- **触摸分发**（Kotlin，`app/src/main/java/dev/mineport/launcher/game/GameHost.kt`）：`GameHostLayout` 在根容器 `dispatchTouchEvent` 里按矩形分流——轮盘区给 Compose（`super.dispatchTouchEvent`），其余全部给游戏（`TouchInjector`）；`onInterceptTouchEvent` 在游戏区一律返回 true，防止子 View 抢。`TouchInjector` 规则：DOWN 立刻按下（支持长按）、MOVE 位移 >2px 才发绝对坐标、UP/CANCEL 释放、第二指不参与视角。`GameActivity` 改用 `GameHostLayout` 作为根容器，删除挂在 GLSurfaceView 上的 `OnTouchListener`（它会被兄弟 View / Compose 覆盖层抢先消费，真手指触摸因此到不了 JNI，而 adb input tap 走注入路径所以会误判可用）。
- **待真机验证**：手指点 Continue 是否切到主菜单；logcat 应出现 `critical: cursor=... button=... stackqueue=...` 与 `critical: stack queue enabled`。
- **编译**：`assembleDebug` BUILD SUCCESSFUL（19s 增量，含 GameHost.kt 与 critical 通道）。

### 17.4 触摸状态机与轮盘（2026-11-23 晚）

- `TouchInjector` 双态：DOWN 只发光标 + 启动 300ms 长按计时；按住 >=300ms 补发 press（挖矿/持续攻击）；短按 UP 补发 press + 33ms 后 release（FCL `putMouseEventWithCoords` 语义）。
- CANCEL：释放按键 + `NativeRenderer.nativeResetInputState()` -> `critical_reset_input_state`，避免系统手势打断后按键卡在按下态。
- 坐标暂按全屏 1:1（windowWidth == gameView.width）；窗口化后需按 `CallbackBridge.windowWidth / gameView.width` 换算。
- 轮盘恢复：`GameActivity` 把 220dp `ComposeView` 挂到 `GameHostLayout.wheelView`，`onAction(GameAction, Boolean)` -> `nativeSendKey`（W87/A65/S83/D68/Space32/Shift340/E69/Q81），按下 120ms 后自动抬起。
- 编译：assembleDebug BUILD SUCCESSFUL（1m26s）。

### 17.5 真实触摸无响应的根因（2026-11-23 深夜）

- 事故：`GameHostLayout.gameView` 字段从未赋值（`GameActivity` 只 `addView(surface)`），而 `TouchInjector.touch(ev, gameView)` 首行是 `if (gameView == null) return false` -> 手指按在游戏区时事件在此被丢弃，根本没走到注入。adb 注入能点进主菜单是因为当时走的是反射 + GLSurfaceView 监听的老路径。
- 修复：`glView = surface` 之后紧接 `root.gameView = surface`。
- 教训：事件链路上任何一处「空值提前返回」都会静默吞掉输入，且不会报错；以后每加一个 early-return 必须配日志。
- 观测手段：每次注入前写 `files/touch.log`（`ptr x= y= a=`），真机 `run-as ... cat files/touch.log` 可确认事件是否到达。

### 17.6 「进世界」前置核查（2026-11-24）

- 1.20.1 的 `arguments.game` 只有 username/version/gameDir/assetsDir/assetIndex/uuid/accessToken/clientId/xuid/userType/versionType，**没有 `--demo` 开关**；我们传 `--accessToken 0 --userType legacy`，MC 因此进入离线 Demo 形态，主界面出现 "Play Demo World"。
- 因此「免登录进世界」的唯一入口就是主菜单的 **Play Demo World**（点击后本地生成 demo 世界），不需要账号；P2-01 账号体系上线后再换成正常世界。
- `--quickPlayPath ` 等占位符会被 `MinecraftLauncher.replace` 连同开关一起丢弃（已核对：开关与值为两个独立参数时才丢弃上一项，单参数形式不误删），所以不会把非法路径传给 MC。
- 进世界的主要风险不在参数，而在 GL：chunk 加载会显著抬高帧缓冲与纹理压力（GL4ES 路径），需在真机上记录 gfxinfo 与 PSS。

### 17.7 多 AI 协作

已把「双 AI 会诊」写进 docs/AI-COLLAB.md：分工（本执行体动手/参考文献复核）、文件化交接（参考文献答复写成 docs/REVIEW-*.md）、提问模板、案例库、纪律（结论必须可验证）。台账 D86。

### 17.8 轮盘改为「按住持续」（2026-11-23 深夜）

- `ActionWheel` 的 `onAction(action, down)` 语义其实是「拖到该扇区 = true（保持），离开/松手 = false」，并非一次性点击。
- 旧实现忽略 `down`、每次切换都发「按下 + 120ms 抬起」，只能走一步。
- 现改为 `handleWheel(name, down)` + `wheelKeys: MutableSet<Int>` 去重：down=true 时 press（若未按过），down=false 时 release（若在按）；按住扇区即持续前进，离开即停。
- 键位：前 W(87)、后 S(83)、左 A(65)、右 D(68)、跳 Space(32)、潜 Shift(340)、攻 E(69)、用 Q(81)。
- 编译：assembleDebug BUILD SUCCESSFUL（2m36s）。

## 十八、第二周期第一阶段：账号体系（2026-11-23 深夜）

### 18.1 数据与存储

- `data/AccountStore.kt`：`Account`（name / type / uuid / accessToken / refreshToken / expiresAt）与 `AccountStore`（filesDir/accounts.json，单文件 + StateFlow + Mutex）。
- 离线账号 uuid 用原版同款算法：`MD5("OfflinePlayer:" + name)`，再按 UUID v3 规则置位 version/variant——这样离线存档、皮肤、服务端白名单与官方离线模式一致。
- 首次启动无账号时自动建一个离线账号（沿用实例里的默认玩家名），并写回文件。

### 18.2 微软登录（设备码）

- `auth/MicrosoftAuth.kt`：OAuth 2.0 设备码全流程——
  1. `oauth20_connect.srf` 申请 device_code，UI 显示 user_code + verification_uri；
  2. `oauth20_token.srf` 轮询（`authorization_pending` / `slow_down` / `expired_token` / `authorization_declined` 分别处理）；
  3. MSA → Xbox Live → XSTS（取 uhs）→ Minecraft Services；
  4. `/minecraft/profile` 拿 uuid 与 name。
- client_id 用官方启动器的公开值 `00000000402b5328`（Pojav/FCL 等第三方启动器同款）。
- 阻塞式调用，调用方放到 IO 线程；`onDeviceCode` / `onPending` 两个回调驱动 UI。

### 18.3 HTTP 层扩展

- `download/Http.kt` 从「只有 GET」扩展为 `get / postForm / postJson`，支持自定义请求头。
- 关键行为：**非 2xx 也返回响应体**——OAuth 轮询的 `authorization_pending` 是 HTTP 400 且错误码只在 body 里，抛异常会丢上下文。

### 18.4 启动参数注入

- `MinecraftLauncher.prepare(..., account: Account?)`；`buildArgs` 里替换：
  `\` = account.name（空则实例 playerName）、`\` = uuid 去连字符、
  `\`/`\` = accessToken（离线为 "0"）、`\` = msa/legacy。
- `MineportApplication` 的 `AppContainer` 增加 `accountStore`，`onCreate` 里 `loadBlocking()`（:game 进程也要同步就绪）；
  `GameActivity` 调 `prepare` 时传 `app.container.accountStore.current`。

### 18.5 编译

- `compileDebugKotlin` BUILD SUCCESSFUL（1m22s）。

### 18.6 待办（第一阶段剩余）

- 设置页账号 UI：当前账号展示、离线改名、微软登录（设备码对话框 + 轮询进度）、账号切换/删除。
- refreshToken 加密存储（Android Keystore）与过期自动刷新。
- 皮肤加载（`/minecraft/profile` 的 skins 字段）。

### 18.7 账号 UI（2026-11-23 深夜，Round 14）

- 新增 `ui/settings/AccountCard.kt`，插到设置页第 3 张卡片：
  - 列出全部账号（名字 + 类型 + uuid 前 8 位），当前账号打 ✓；
  - 「切换 / 改名 / 删除」按钮；改名会新建离线账号并删掉旧的（离线 uuid 由名字派生，改名校验等价于换身份）；
  - 「新建离线账号」「微软登录」两个入口；
  - 微软登录弹设备码对话框：显示验证网址、大号短码、轮询状态（等待授权 / 请求过快 / 网络异常），失败弹错误详情。
- 登录成功后写入 `Account`：uuid 格式化为 8-4-4-4-12、accessToken、refreshToken（便于后续免交互续期）、expiresAt = now + 23h。
- `MicrosoftAuth.McProfile` 增加 `refreshToken` 字段并透传（此前 pollForToken 的 refresh_token 被丢弃）。
- `assembleDebug` BUILD SUCCESSFUL（1m34s），APK 125.6 MB 已覆盖到 `X:\android\dist\app-debug.apk`。

第一阶段剩余（不阻塞交付）：refreshToken 用 Android Keystore 加密、令牌过期自动刷新、皮肤加载（`/minecraft/profile` 的 skins 字段）。

## 十九、参考文献体检整改（2026-11-26）

参考文献提交 `X:\参考文献回复-代码体检-2026-11-24.md`（271 行），逐条裁决后落地四项：

| 编号 | 问题 | 修法 |
|---|---|---|
| B1（高） | 「真手指点不动」：UP 时才发 press 且用 lastX/lastY，真手指抖动使 press 落在抬起点 | DOWN 记 downX/downY、立即 press、33ms 自动 release、300ms 后升级长按并取消自动 release |
| B2（高） | 窗口尺寸双真源：nativeResize 只改 glViewport，不改 GLFW 的 windowWidth/Height | 新增 `bridgeOnSurfaceSize(w,h)`，nativeResize 里调用；删除 jvm-ready 后用 displayMetrics 覆盖的代码 |
| B3（中） | CANCEL 与 clickRelease 双重 release | 引入 `held`/`longHeld` 两个状态位，只在 held 时 release |
| B5 | PerfSampler CPU tick 解析失败返回 0，算出负 CPU% | 失败返回 -1L，采样端两值非负才算 |
| B6 | OAuth `slow_down` 未调整轮询间隔、无续期函数 | 退避 +5s 封顶 60s 并保持；新增 `refreshMsaToken()` |
| B7 | accounts.json 非原子写，损坏即丢全部账号 | tmp 文件 + rename，失败抛 IOException |

未采纳/待办：B4（轮盘与视角并用，需 pointer 级命中）、B8–B12（低优先级）。B3（队列丢事件）需真机 A/B（`critical_set_stackqueue(1)` vs `0`）。

判定链路（真机验证用）：
1. `adb shell run-as dev.mineport.launcher cat files/touch.log` 看有没有 `ptr x= y= a=` 行 → 没有就是事件没到 View；
2. 有 ptr 行但 MC 不动 → `adb logcat -s MineportJNI` 看 `critical:` 前缀日志；
3. 两者都有 → 是 MC 内部没命中，核对窗口尺寸日志 `surface size -> WxH`。

## 二十、B4/B12 收尾（2026-11-26 晚）

- **B4 轮盘独立窗口**：`ActionWheel` 从 `GameHostLayout` 的子 View 改挂到 `PopupWindow`（220dp，BOTTOM|START，12dp 边距）。子 View 方案下根容器必须在 DOWN 时独占整条手势，第二指按轮盘会被当成视角 MOVE 吞掉；独立窗口有自己的触摸通道，可以「左手轮盘移动 + 右手转视角」同时用。`onDestroy` 里 dismiss。
- **B12 java.library.path**：除 `nativeLibraryDir` 与 `runtime/natives` 外，追加 `runtime/lib` 与 `runtime/lib/server`——HotSpot 找 libjava/libnio/libnet 也要走这个路径。
- 编译 assembleDebug 1m21s，APK 125.6 MB。

## 二十一、触控链路静态自检（2026-11-26）

新增 `scripts/check-touch.mjs`：无手机时对「手指 → MC」这条链的关键契约做断言，防止重构悄悄退回旧语义。14 项全部通过：

| 断言 | 守护的行为 |
|---|---|
| B1 DOWN 立即 press | 按下点取 DOWN 坐标，不是抬起点 |
| B1 自动 release 33ms / 长按 300ms | 点击必成对，长按不被松开 |
| B1 clickRelease 尊重 longHeld | 长按期间不会误 release |
| B3 CANCEL 复位输入态 | 系统手势打断后 FCL 内部状态干净 |
| 无重复触摸路径 | 只有一条注入链，没有幽灵双发 |
| B2 bridgeOnSurfaceSize 定义 + nativeResize 调用 | Surface 尺寸是唯一真源 |
| B2 仅在 onCreate 兜底一次 | 不存在第二条 displayMetrics 写入路径 |
| critical 通道已解析 / 队列开启 / 反射兜底保留 | 正统通道优先，兜底还在 |
| touch.log 可读路径 | 真机 `run-as cat files/touch.log` 可用 |

运行：`node scripts/check-touch.mjs`（node 不在 PATH 时用绝对路径）。

### B11：GlfwProbe 改为按需开启（2026-11-26）

探针本身会调 `glfwInit`，而 FCL 的 `glfwInit` 有「已初始化直接返回」的短路；默认先跑探针会让 MC 复用一个还没绑定 Surface 的窗口，属于隐性副作用。改为文件开关：

`ash
# 真机诊断时才开
adb shell run-as dev.mineport.launcher touch files/enable-probe
# 跑完删掉，避免影响正常启动
adb shell run-as dev.mineport.launcher rm files/enable-probe
`

默认不跑，`probe.txt` 也就不会在正常启动时被覆盖。

## 二十二、性能基线可见化（2026-11-26）

### 22.1 采样器已在游戏进程里

`GameActivity.kt:170` 在 `onCreate` 的协程里 `PerfSampler.start(this, "game")`，`GameActivity.kt:336` 的 `onDestroy` 里 `PerfSampler.stop()`。每 2 秒写一行到 `files/perf-game.log`：

- Java 堆（`Runtime.totalMemory - freeMemory`）
- native 堆（`Debug.getNativeHeapAllocatedSize()`）
- 线程数（`/proc/self/status` 的 `Threads`）
- CPU 占用（`/proc/self/stat` 的 utime+stime 差除以墙上时间和核数）

### 22.2 设置页直接看

「设置 → 诊断与日志」底部新增「性能采样」段：显示采样条数与最近一行 JSON。没有采样文件时显示「尚无采样（启动一次游戏后自动记录）」。

### 22.3 真机读取方式

```bash
adb shell run-as dev.mineport.launcher cat files/perf-game.log | tail -20
# 或整份拷出来
adb exec-out run-as dev.mineport.launcher cat files/perf-game.log > perf.jsonl
```

判定基线时看三件事：Java 堆是否稳定在设定值以下（默认 3072 MB 上限，实际占用应在 1.5 GB 内）、线程数是否随世界加载后回落、CPU 是否在渲染帧率稳定后低于 100%（单核满为 100%）。

### 22.4 为什么不测帧率

宿主 `View` 本身不产帧，`Choreographer` 量到的是空转 UI 帧率而非 MC 渲染帧率。真要帧率用：

```bash
adb shell dumpsys SurfaceFlinger --latency <surface>
```


## 二十三、真机验证脚本（2026-11-26）

`X:\android\scripts\verify-touch.ps1` 把「装包 → 清状态 → 启动 → 点击 → 抓证据 → 判定」压成一条命令，手机接上后直接跑：

```powershell
pwsh -File X:\android\scripts\verify-touch.ps1
# 可选参数
#   -SkipInstall        已装好，只跑验证
#   -BootWaitSec 90     MC 冷启动慢时加长等待
#   -InstanceId <uuid>  指定实例
```

### 23.1 输出什么

跑完会打印四行证据与一个判定：

```
touch.log ptr lines : 3        # 手指事件是否到了 TouchInjector
critical resolved   : True     # dlsym 是否拿到 critical_* 符号
critical invoked    : True     # 是否真的走了 critical 通道
screenshot          : X:\android\.toolchain\verify-touch.png
```

判定分四档，每档直接给下一步：

| 判定 | 含义 | 下一步 |
|---|---|---|
| `[FAIL]` ptr=0 | 事件没到注入器 | 查 `gameView` 是否赋值（BUILD-STATUS 17.5） |
| `[HALF]` critical 未解析 | 符号名不对 | `nm -D libpojavexec.so \| grep critical_send_cursor_pos` |
| `[HALF]` critical 未调用 | 被反射兜底抢先 | 看 `inject: MC MouseHandler` 日志 |
| `[PASS]` 全通 | 链路执行 | 画面没变则 A/B `critical_set_stackqueue` 改 0 |

### 23.2 为什么先删 touch.log

上一局的 `ptr` 行会污染判定，脚本在启动前 `run-as rm -f files/touch.log`，保证计数只反映本局。

### 23.3 点击坐标

点了三处 `(1356,1127) (1360,1000) (1356,900)`，覆盖不同 GUI scale 下主菜单按钮的位置。命中一处即可，多余的点击落在背景上没有副作用。


## 二十四、B9：JVM 复用校验（2026-11-26）

同一个 HotSpot JVM 只能消费一次 `main`。第二次进入时 VM 已经跑过一遍 `main`，类加载器、GLFW 状态、静态字段全都残留，表现为五花八门的 `NoClassDefFoundError`，查起来极费时间。当前每局游戏在独立 `:game` 进程里跑，正常路径不会触发；但「用户退回再进」「Activity 被系统重建」都可能让同一进程再次调用。

### 24.1 改法

`jni_bridge.cpp` 增加文件级 `std::atomic<bool> g_mainConsumed{false}`，`nativeRunMainClass` 开头：

```cpp
if (g_mainConsumed.exchange(true)) {
    return -4;
}
```

`GameActivity` 对 `-4` 给出明确提示：「本进程已经运行过一次游戏，JVM 不能复用。请从最近任务里彻底划掉本应用后重新启动。」

### 24.2 为什么是拒绝而不是重建

重建要在同一进程里 `DestroyJavaVM` + 重新 `dlopen` libjvm，Android 上 libjvm 无法安全重载（符号与 TLS 都留在进程里），失败率远高于收益。**拒绝 + 让用户重启进程**是唯一稳的方案，这也和 FCL 的行为一致。


---

## 二十三、多点触控（2026-11-27）

### 23.1 旧方案为什么只有一根手指

`GameHostLayout` 在 `ACTION_DOWN` 时定一次 `owner`，整条手势都跟着走：
手指按下的那一刻落在游戏区，之后所有 `ACTION_POINTER_DOWN`（第二根手指）
都被判成「游戏区」，永远进不了轮盘。表现就是「一根手指转视角、另一根手指按轮盘」完全无效。

### 23.2 新方案：按 pointerId 记账

```kotlin
private val owners = SparseIntArray()      // pointerId -> OWNER_GAME / OWNER_WHEEL
private val wheelIndex = SparseIntArray()  // pointerId -> 当前扇区下标
```

- `ACTION_DOWN` / `ACTION_POINTER_DOWN`：只判这一根手指（`ev.actionIndex`）落在哪；
- `ACTION_MOVE`：遍历 `pointerCount`，每根手指各自处理——轮盘手指算扇区，游戏手指发视角；
- `ACTION_POINTER_UP`：只释放这一根；
- `ACTION_UP` / `ACTION_CANCEL`：全清。

### 23.3 轮盘改成「宿主命中 + Compose 只画」

Compose 的 `detectDragGestures` 一次只能吃一条手势，做不到按手指分流。
所以轮盘彻底让出触摸：

- `ActionWheel(active: GameAction?, ...)` 只负责画，不再有 `pointerInput`；
- 扇区判定搬到 `GameHostLayout.pickWheelIndex(x, y)`，几何与绘制保持一致
  （中心 = 轮盘 View 中心，半径 = `min(w,h) * 0.42 * 1.18`，8 等分，从 -90° 开始）；
- 命中后回调 `onWheelAction(action, down)`，`GameActivity` 转成 `nativeSendKey` 的 press/release，
  同时更新 `wheelActive` 让扇区高亮。

### 23.4 TouchInjector 支持多指针

- 第一个按下的游戏区指针 = 视角指针（`lookId`），MOVE 只跟它；
- 其余指针各自按下即 press、33ms 自动 release，互不影响（边走边打）；
- `pointerUp(id)` 只处理对应指针；`cancelAll()` 统一复位。

### 23.5 静态自检

`scripts/check-touch.mjs` 从 19 项扩到 **22 项**，新增：

- `多指归属按 pointerId 记账`
- `注入器有 pointerDown/Move/Up 三入口`
- `轮盘命中在宿主里手动算（不依赖 Compose 触摸）`
- `ActionWheel 不再接收触摸`
- `不再使用 PopupWindow 装轮盘`

`node scripts/check-touch.mjs` → **22 项全部通过**。

### 23.6 待真机验证

用户需使用手机，本轮未上机。验证要点：
1. 一根手指按住屏幕拖动 → 视角转动（`touch.log` 里 `a=1` 连续）；
2. 另一根手指按轮盘「前」→ 角色前进，同时视角仍可拖；
3. 两指同时按住轮盘不同扇区 → 两个动作同时生效（`W` + `Space`）；
4. 三指以上不乱跳（多余指针只做点击，不抢视角）。

### 23.7 一键验证脚本升级（`scripts/verify-touch.ps1`）

脚本从「点三下 + 三档判定」扩成五段流程，**不再需要人工判读**：

| 阶段 | 动作 | 判定依据 |
|---|---|---|
| 1 | 连点三处（Continue / 世界内） | `touch.log` 出现 `ptr` 行 |
| 2 | 横向 swipe 700ms（视角拖动） | `touch.log` 里 `a=1` 的 MOVE 行数 > 0 |
| 3 | 点轮盘「前」扇区 | logcat 出现 `wheel press` |
| 4 | 读 `files/perf-game.log` | 有 `"t":` 采样行 |
| 5 | 截图 | `X:\android\.toolchain\verify-touch.png` |

**轮盘坐标不再写死**：脚本读 `wm density` 与 `wm size`，按 `220dp` 方块 + `12dp` 左下边距
换算出中心点，再沿正上方取 `0.25 × 轮盘边长` 作为「前」扇区落点。设备换分辨率也不会点空。

判定升级为七档：

```
FAIL  touch never reached TouchInjector
FAIL  critical_* not resolved
HALF  critical ready but isInputReady=0        <- 之前踩过的坑，单独一档
HALF  only taps, no MOVE (look drag absent)
HALF  look drag ok, wheel sector not hit
HALF  touch chain ok, no perf samples
PASS  taps + drag + wheel + perf all present
```

脚本注释全部改成 ASCII——Windows PowerShell 5.1 解析含中文注释的 .ps1 会报
`意外的标记`，这是之前反复踩的坑。

用法（先放开执行策略）：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
pwsh -File X:\android\scripts\verify-touch.ps1
```

---

## 二十五、光影与模组补课（2026-11-27）

用户反馈「模组还没做！还有光影」，核查后确认：**光影是真缺，模组是入口可达性问题**。

### 25.1 先修一个真 bug：asset index 没进 assets/indexes（黑块根因）

`VersionInstaller` 原来只把 asset index 下到 `versions/<id>/asset_index.json`，
`AssetInstaller` 拿它去下 objects——**但 MC 运行时是按 `--assetsDir` + `--assetIndex`
去读 `<assetsDir>/indexes/<id>.json` 的**。文件不在那儿，游戏就认为一个资源都没有，
所有纹理退化成黑块。真机上看到的「深棕地形 + 中央黑方块 + 6X」正是这个。

修复：下完 assets 后把 index 复制到 `File(assetsDir, "indexes").mkdirs()` 下的
`"$indexId.json"`，失败计入 `failed` 回报。**已装过版本的设备需重装一次版本**才会补上。

### 25.2 光影：Iris 自动安装

之前只有「光影（Zink 试跑）」开关，`ShaderPolicy` 只决定后端走 Zink 还是 GL4ES，
**没有任何地方装 Iris**——光影包放进去也不会生效。

新增 `app/src/main/java/dev/mineport/launcher/render/ShaderInstaller.kt`：

- `installIris(gameVersion, modsDir, onFile)`：Modrinth 搜 `iris shaders` + 版本 + `fabric`，
  按 slug `iris` 命中，交给现有 `ModInstaller.install`（required 依赖 BFS + SHA-1 + 逐文件进度）；
  成功后 `mkdirs()` 出 `shaderpacks/`
- `modsDir(filesDir, gameDirName)` / `shaderPacksDir(...)` / `isIrisInstalled(modsDir)`

实例设置里勾选「光影」后：
- 加载器不是 Fabric：红字「Iris 只能装在 Fabric 实例上」
- 是 Fabric：「安装 Iris 光影加载器」按钮 + 逐文件进度 + `shaderpacks` 绝对路径

### 25.3 模组：原版实例的引导闭环

`ModsDialog` 对 vanilla 实例一直只显示「不能装」的提示，用户点进去就退出来——功能在，但没给出路。

改动：
- 签名加 `onOpenSettings: (() -> Unit)? = null`
- 原版分支多一个「去实例设置装加载器」按钮，点了关掉对话框并直接打开实例设置
  （那里已有 Fabric/Quilt 一键安装）

链路闭环：原版实例 → 装 Fabric → 模组可用 → 光影可装 Iris。

### 25.4 编译

`assembleDebug` BUILD SUCCESSFUL（1m28s / 1m19s 两次），APK 覆盖 `X:\android\dist\app-debug.apk`。
