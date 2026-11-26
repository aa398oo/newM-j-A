# 运行时二进制从哪来

这个仓库**不携带**任何预编译的 JVM / LWJGL / GL4ES 二进制（体积上百 MB，且各自许可证不同）。
下面是要放的目录、来源和校验方法。

## 1. 需要哪些文件

| 文件 | 用途 | 放哪 |
| --- | --- | --- |
| `lib/server/libjvm.so` + `lib/modules` + JRE 自带的 `lib/*.so` | OpenJDK for Android aarch64 | `app/src/main/assets/runtime/`（首启解到 `filesDir/runtime`） |
| 同一批 JRE `.so` | 供 `dlopen` 按 soname 解析依赖 | `app/src/main/jniLibs/arm64-v8a/`（只有当 dlopen 报缺依赖时才需要再放一份） |
| `liblwjgl.so` | LWJGL3 的 Android 构建（含 EGL/GLFW 移植） | `app/src/main/jniLibs/arm64-v8a/` |
| `libgl4es.so` | GL4ES 翻译层 | `app/src/main/jniLibs/arm64-v8a/` |
| `libGL.so`（gl4es 的兼容名） | 供 LWJGL 以 GL 名义加载 | `app/src/main/jniLibs/arm64-v8a/` |
| `libEGL.so`、`libGLESv2.so` | **不要放**：Android 系统自带，放进去会冲突 | — |
| libturnip / vulkan 驱动 | 仅骁龙 + Zink 需要，按设备型号区分 | `assets/runtime/natives/` |
| natives（.so 形式的 lwjgl 辅助库） | 由 LWJGL 构建产物附带 | `assets/runtime/natives/` |

关键约束：`filesDir/runtime/lib/modules` 必须存在。JVM 创建时会按 `-Djava.home=filesDir/runtime`
去找 `lib/modules`，缺了它 `libjvm.so` 能加载但 `java.base` 起不来。

JRE 的 `.so` 有两种放法，代码都支持：

- **放 `jniLibs/arm64-v8a/`（推荐先试）**：`extractNativeLibs=true` 会解到 `nativeLibraryDir`，
  `dlopen` 时依赖能按 soname 解析；
- **只放 `assets/runtime/lib/`**：首启解到 `filesDir/runtime/lib`，`jni_bridge.cpp` 会按依赖顺序
  预加载 `libjava/libnio/...`（`RTLD_GLOBAL`）再创建 JVM。真机验证前不要假设这条路一定通。

## 2. 来源（2026-11-21 实测）

1. **FCL 1.3.4.7 arm64 APK**（github.com/FCL-Team/FoldCraftLauncher/releases，160.9 MB）：实测里面是齐的，且布局正好匹配本工程：
   - `assets/app_runtime/java/jre17/universal.tar.xz`（16.3 MB）+ `bin-arm64.tar.xz`（4.4 MB）
     → 合并解压得到 `lib/server/libjvm.so`（14.6 MB）、`lib/modules`（77.9 MB）、`lib/libjava.so` 等 31 个 `.so`
   - `assets/app_runtime/lwjgl/3.4.1/` → `liblwjgl.so` 等 7 个 natives + 13 个 jar
   - `lib/arm64-v8a/libng_gl4es.so`（GL4ES 6.4 MB）、`libgl4es_114.so`、`libopenal.so`、`libc++_shared.so`
   - 同一 APK 里还有 jre21/jre25 包，版本按需取；`libEGL*.so`/`libGLESv2*.so` 不要拷。
2. **Amethyst-Android 1.1.7**：内置的是 **jre8**（`assets/components/jre/`，且 `lib/rt.jar.pack` 需要 unpack200），
   不适合直接当 1.20.1 的运行时；它的价值在图形栈（`libzink_dri.so`、`libvulkan_freedreno.so`、`libEGL_angle.so`）。
3. **PojavLauncherTeam/android-openjdk-build-multiarch 的 jre17 包是 macOS 的**（`libjvm.dylib`，Mach-O），
   不要拿它当 Android 运行时——这是本轮踩过的坑。
4. 自己构建：OpenJDK android/aarch64 port + LWJGL3 Android 分支 + GL4ES CMake 构建（只在需要改 JVM 时才走）。

## 3. 从 apk 里提取

本机（Windows，需要 7zr.exe 与 node）：

```powershell
# 只看清单
scripts/import-runtime.ps1 -Apk .runtime-cache/FCL-arm64.apk -ListOnly

# JRE（universal + bin-arm64 合并解到 assets/runtime）+ lib/arm64-v8a 的 so 拷进 jniLibs
scripts/import-runtime.ps1 -Apk .runtime-cache/FCL-arm64.apk
```

云构建（CI）不需要手动做：`android-debug.yml` 的 `Prepare runtime from FCL` 一步会自动下载 FCL arm64 APK、
取 jre17 与 LWJGL/GL4ES，再跑 `check-runtime.mjs`。仓库里仍然不提交二进制（`.gitignore` 已排除）。

## 4. 校验（本轮实测结果）

```powershell
# 架构 + 16 KB 页风险（不需要 Android SDK）
node scripts\check-runtime.mjs

# 需要 NDK 时再看一遍 ELF 头
& "$env:ANDROID_HOME\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe" -h android\app\src\main\jniLibs\arm64-v8a\libjvm.so | Select-String "Machine|Class"
```

期望：`Class: ELF64`、`Machine: AArch64`。若出现 `x86-64`，说明拿错了 ABI。

**本轮实测（2026-11-21）**：`check-runtime.mjs` 扫到 44 个 `.so`（JRE 31 + jniLibs 13），
全部 ELF64/AArch64、**0 错误**；但 `PT_LOAD` 对齐全是 `0x1000`（4 KB）。
如果 `adb shell getconf PAGE_SIZE` 返回 `16384`，这些库会报 `has invalid ELF program header`，
需要 `llvm-objcopy --page-size=16384` 重打包（CI 里有 NDK，可以加一步批量处理）。

## 5. 缺文件时的行为

不会静默崩溃：

1. `RuntimeStager.ensure()` 会把 `assets/runtime` 解到 `filesDir/runtime`（用安装时间做 stamp，装完第一次才解）；
2. `JvmLauncher.start()` 按 `runtime` → `libjvm.so` → `nativeLibraryDir/libjvm.so` 的顺序找库，
   找不到就直接返回中文错误并给出应放路径；
3. 失败原因显示在游戏画面上（`GameActivity` 的 statusView），同时写 logcat 的 `MineportJVM`。
