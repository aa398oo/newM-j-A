# newM-j-A（纵向切片阶段）

目标：在安卓上做一个**性能可调、启动稳定、交互现代**的 Minecraft 启动器。
与上游 FCL 的关系是「复用 JVM 层、重写壳与渲染」。

## 现在能做什么

- 编译出 APK（需要 Android SDK + NDK 27；本机没有 SDK 时走 `.github/workflows/android-debug.yml` 云构建）
- 首页显示真实设备画像：SoC 厂商/型号、内存、GLES 版本、Vulkan 支持、ABI、核数
- 按机型给出渲染后端推荐（骁龙 → Zink 可尝试；天玑/Exynos/麒麟 → GL4ES）
- 新建/删除实例，数据落在 `filesDir/instances.json`
- 界面动效统一走 `ui/motion/Motion.kt`：页面淡入上移 220ms、按钮按下 0.97 回弹、卡片/列表错峰入场、
  三个方块呼吸式加载（不用转圈）
- 设置页有「运行时」自检卡片，直接显示 `libjvm.so` / `lib/modules` 是否就位
- **下载页已接真实版本清单**：BMCLAPI → Mojang → 本地缓存三级降级，失败可重试
- **版本安装第一段**：客户端 jar + libraries + asset index 落盘（并发 8、断点续传、SHA-1、失败文件单列）
- **短码逻辑层**：6 位 Crockford Base32 + 校验位，可生成/登记/读取整机配置（跨设备要接短码服务）
- **模组**：Modrinth 搜索、按版本/加载器筛选、required 依赖递归解析、下载到 mods 目录
- **动作轮盘**：游戏画面左下角拖动选择动作（前进/跳跃/攻击…），意图先打 log，输入桥下一步接
- **光影策略**：按 SoC/Vulkan/内存给出 Zink 或 GL4ES 判定与回退理由
- 点「启动」进入横屏游戏窗口，用 GLES 画一个渐变画面（验证 EGL/GL/帧循环链路）
- **JNI → JVM 已打通到第 2 步验收**：启动时把 `assets/runtime` 解到 `filesDir/runtime`，
  `dlopen` `libjvm.so` 创建 JavaVM，屏幕上显示 `OpenJDK <java.version>`、ABI 与页大小

## 现在**不能**做什么（诚实清单）

- **还不能真正启动游戏**：FCLCore.jar 由 CI 尽力构建（可能失败），且 JVM 内还没有调用它的入口；
- **还不能操作游戏**：动作轮盘只输出意图日志，JNI 输入桥未接；
- 短码跨设备分享需要服务端；模组尚未接到实例与启动流程；
- 没有触控/手柄映射层：下一步做；
- JRE/LWJGL/GL4ES 二进制不在仓库里，见 [docs/binaries.md](docs/binaries.md)。

## 构建

本地：

```bash
cd android
./gradlew :app:assembleDebug        # 需要 ANDROID_HOME 与 NDK 27.0.12077973
```

Windows 上没有 gradlew 时用 `gradle :app:assembleDebug`（需本机 Gradle 8.9+）。

云端（本机无 SDK 时的唯一路径）：
把 `android/` 推到 GitHub，`Android Debug APK` 会在每次 push 时构建并上传 `app-debug` 产物。
不带运行时也能构建，但装到手机上会在第 2 步之前停下并显示「缺少 JVM」。
要产出能验证第 1/2 步的 APK，走 manual dispatch 并填 `runtime_url`（JRE 压缩包直链，
包根目录直接含 `lib/server/libjvm.so` 与 `lib/modules`）。

构建前会跑三个静态检查：

```bash
node scripts/check-sources.mjs .      # 括号/引号配平
node scripts/check-jni.mjs .          # Kotlin external 与 C++ 导出符号一一对应
node scripts/check-runtime.mjs        # 运行时 .so 必须是 ELF64/AArch64，并提示 16 KB 页风险
```

## 运行时怎么进 APK

见 [docs/binaries.md](docs/binaries.md)。一句话：
`app/src/main/assets/runtime/` 放 JRE（`lib/server/libjvm.so` + `lib/modules`），
首次启动由 `RuntimeStager` 解到 `filesDir/runtime` 后再 `dlopen`；
`jniLibs/arm64-v8a/` 放同一批 `.so` 供加载器解析依赖（`libEGL.so`/`libGLESv2.so` 不要放）。

从上游 apk 一次性提取（本机脚本，带清单与校验）：

```powershell
pwsh -File scripts/import-runtime.ps1 -Apk .runtime-cache/Amethyst-1.1.7.apk -ListOnly  # 先看清单
pwsh -File scripts/import-runtime.ps1 -Apk .runtime-cache/Amethyst-1.1.7.apk            # 再落盘
```

## 目录

```
app/src/main/java/dev/mineport/launcher/
  MineportApplication.kt    进程级单例（实例库、设备画像）
  MainActivity.kt      Compose 入口
  data/                GameInstance、InstanceStore（JSON 持久化）
  device/              DeviceProfile：SoC/GLES/Vulkan 检测与后端推荐
  ui/                  首页、实例、下载、设置
  game/                GameActivity、GLSurfaceView、NativeRenderer
  jvm/                 JvmLauncher（JNI 声明与启动）、RuntimeStager（assets → filesDir）
app/src/main/cpp/
  jni_bridge.cpp       dlopen libjvm.so + JNI_CreateJavaVM + 回传 java.version
  render_bridge.cpp    GLES2 着色器与帧绘制
```

> JVM 创建后是 HotSpot：它只读 classpath 上的 jar，读不到 APK 的 dex。
> 所以不要指望在 JVM 内调用 Kotlin 对象；游戏与 FCLCore 都必须是 jar 里的类。

## 路线

1. **本周**：触控/手柄映射 + 实例编辑页（后端、内存、分辨率、帧率上限）
2. **接入 FCLCore**：构建 jar → 放进 assets → 由 JVM 以 `-cp` 加载并调用安装/启动 API
3. **接入 GL4ES**：替换 `render_bridge.cpp` 的演示着色器，接 LWJGL 的 EGL 表面
4. **性能档位**：分辨率缩放、帧率上限解除、启动前体检、崩溃降档重试
