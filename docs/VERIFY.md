> **已过期提示（2026-11-23）**：本文写于 AWT/caciocavallo 主路径时期。现路线已改为 **FCL lwjgl 3.4.1 + libpojavexec 窗口桥 + GL4ES**，并已在真机跑通主菜单；输入走 `critical_*` 通道。历史内容仅作参考，现行结论见 `BUILD-STATUS.md` 与 `docs/AI-COLLAB.md`。

# 真机验证清单（手机连上后按顺序做）

## 0. 准备

```powershell
$env:TEMP = "X:\android\.toolchain\tmp"; $env:TMP = "X:\android\.toolchain\tmp"
$adb = "X:\android\.toolchain\sdk\platform-tools\adb.exe"
& $adb devices
& $adb install -r X:\android\dist\app-debug.apk
& $adb shell am start -n dev.mineport.launcher/.MainActivity
```

坐标基准：竖屏 1220x2712。底部导航「实例」tab `(344,2590)`；实例卡片播放键 `(895,631)`。

## 1. 启动链路（不勾 AWT 桥）

- 点实例 → 播放。
- 预期：进入 GameActivity，画面先显示「准备运行时…」→「运行时已就绪」，最后显示「游戏启动失败」或黑屏后退出。
- 检查：
  ```powershell
  & $adb logcat -d -s MineportJNI MineportJVM MineportRuntime | Select-Object -Last 30
  & $adb shell run-as dev.mineport.launcher cat files/crash/startup.txt
  ```
- 成功里程碑应包含：`jvm-ready:17.0.10-internal` 与 `main-invoking:net.minecraft.client.main.Main`。
- 已知阻塞：GL 层 `pthread_mutex_lock called on a destroyed mutex`（D64）——这是 caciocavallo/LWJGL 窗口桥缺失导致的，不是启动失败。

## 2. AWT 桥实验（勾上后再启动）

- 实例设置 → 勾「AWT 桥（caciocavallo，实验）」→ 保存 → 播放。
- 检查：
  ```powershell
  & $adb logcat -d | Select-String -Pattern "cacio|CTCToolkit|AWT|XToolkit|headless|GLFW" | Select-Object -Last 40
  ```
- 关注：是否出现 `CTCToolkit` 初始化、`sun.awt` 相关错误、`libawt_xawt` 加载失败。
- 记录：如果 cacio 初始化成功但 GL 仍崩，下一步是移植 FCL 的 `org.lwjgl.glfw.CallbackBridge`（见 `docs/FCL-RENDER.md`）。

## 3. 输入桥（轮盘）

- 进入 GameActivity 后，在左下角动作轮盘按住「前」再松开。
- 检查：
  ```powershell
  & $adb logcat -d -s MineportInput | Select-Object -Last 20
  ```
- 预期：`input: hold FORWARD -> mask=0x...` 与 `input: release FORWARD -> mask=0x...`，掩码位 0..7 对应 FORWARD..USE。

## 4. 短码

- 实例卡 → 设置弹窗 → 「生成分享码」→ 复制。
- 实例页「短码导入」→ 粘贴 → 导入。
- 预期：生成 6 位码；导入后新建「来自短码 XXX」实例，版本/加载器/内存一致。

## 5. Fabric 安装

- 新建实例 → 加载器选 Fabric → 保存 → 「安装 Fabric 加载器」。
- 预期：下载 libraries 后提示成功；`files/versions/fabric-loader-*-1.20.1/` 出现。

## 6. 诊断导出

- 设置页 → 「导出并分享诊断包」。
- 预期：生成 `cache/diagnostics/newM-j-A-diagnostics-*.txt` 并弹出分享面板；文件含设备、ABI、JVM、startup.txt。

## 7. LAN 扫描

- 同一 WiFi 下电脑开一个 MC 世界并「对局域网开放」。
- 设置页 → 「扫描局域网世界」。
- 预期：4 秒内列出世界名称与地址；「复制地址」得到 `ip:port`。

## 8. 导入

- 导入目录：从桌面端拷贝 `versions/<id>/` 到手机，SAF 选择该目录。
- 导入 zip：选一个标准整合包 zip（含 `versions/<id>/<id>.json`）。
- 预期：导入后自动出现「导入 <id>」实例，可播放（版本文件完整前提下）。

## 9. 记录结果

每项通过后，在 `android/docs/BUILD-STATUS.md` 追加一行结论；失败则记录 logcat 关键行与复现步骤。
