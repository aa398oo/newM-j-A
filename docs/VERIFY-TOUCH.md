# 触控验证清单（VERIFY-TOUCH）

目标：确认「真手指触摸」能把事件送进 MC，并完成第一周期验收（进世界 / 轮盘 / 性能基线）。
本清单每一项都给出**可判定的证据**，不依赖主观感受。

## 0. 前置

```powershell
$env:TEMP = "X:\android\.toolchain\tmp"; $env:TMP = $env:TEMP
$adb = "X:\android\.toolchain\sdk\platform-tools\adb.exe"
& $adb devices                # 期望出现 <DEVICE_SERIAL>  device
& $adb install -r X:\android\app\build\outputs\apk\debug\app-debug.apk
& $adb shell run-as dev.mineport.launcher rm -f files/touch.log
& $adb logcat -c
````

## 1. 启动实例

```powershell
& $adb shell am start -n dev.mineport.launcher/.MainActivity --es launch_instance c9d58e86-4a5f-4b89-af43-a055bef2f757
Start-Sleep -Seconds 55
& $adb exec-out screencap -p > X:\android\.toolchain\v1.png
````

**期望**：出现 MC 主界面（首次为 "Welcome to Minecraft!" 辅助功能页）。

## 2. 关键证据（三项，缺一不可）

| # | 命令 | 期望输出 | 说明 |
|---|---|---|---|
| A | `& $adb shell run-as dev.mineport.launcher cat files/touch.log` | 出现 `ptr x=.. y=.. a=0/1/2` | 手指事件到达注入层 |
| B | `& $adb logcat -d \| Select-String "critical:"` | `critical: stack queue enabled` | FCL 输入通道启用 |
| C | `& $adb logcat -d \| Select-String "inject:"` | `inject: MC MouseHandler ready` 或 `pointer ... via critical` | 注入成功 |

**判定**：A 为空 → `gameView` 仍为空或手势被上层吞掉；A 有但 B/C 缺失 → `libpojavexec` 未加载或 dlsym 失败；三者都有但画面不动 → 坐标/输入就绪时序问题（见第 4 节）。

## 3. 触摸操作验证

1. **菜单点击**：手指点「Continue」→ 应关闭辅助功能页，进入主菜单（Play Demo World / Options / Quit Game）。
2. **拖动视角**：进入世界后，单指拖动 → 画面视角应连续旋转。
3. **长按挖矿**：按住方块 > 300ms → 应开始破坏方块（press 由 `longPress` 补发）。
4. **轮盘**：左下角 220dp 轮盘拖选「前」→ 角色应向前走一步（`nativeSendKey(87)` 按下 + 120ms 抬起）。

## 4. 常见失败与排查

| 现象 | 可能原因 | 处理 |
|---|---|---|
| `touch.log` 无内容 | `root.gameView` 未赋值；或手势被 Compose/父容器拦截 | 检查 `GameActivity` 里 `root.gameView = surface` 是否在 `addView` 之后 |
| 有 `ptr` 日志但 MC 无反应 | `critical_*` 未解析成功，回退反射也失败 | logcat 搜 `critical` 与 `MC MouseHandler ready`；确认 `libpojavexec.so` 已加载 |
| 点击位置偏移 | 窗口尺寸与 Surface 尺寸不一致 | 目前全屏按 1:1；窗口化后需 `sx = x * windowWidth / gameView.width` |
| 进世界后视角反转/抖动 | 多指跟踪权未转移 | 检查 `ACTION_POINTER_UP` 分支 |
| 系统手势打断后按键卡住 | CANCEL 未复位 | 确认 `nativeResetInputState()` 被调用（logcat `reset input state`） |

## 5. 性能基线（进入世界后记录）

```powershell
& $adb shell dumpsys gfxinfo dev.mineport.launcher:game | Select-String -Context 0,6 "Janky frames"
& $adb shell top -n 1 -b | Select-String "mineport"
````

记录：FPS（若日志含 FPS 行）、平均帧耗时、内存占用（PSS）、启动到主界面耗时（`files/crash/startup.txt` 各里程碑时间戳）。

## 6. 结论模板

```
版本：app-debug.apk（<构建时间>）
设备：Xiaomi 2407FRK8EC / Android 16 / MT6989
A touch.log：<有/无>  B stack queue：<有/无>  C inject ready：<有/无>
菜单点击：<通过/失败>  视角拖动：<通过/失败>  长按挖矿：<通过/失败>  轮盘：<通过/失败>
启动耗时：<秒>  平均帧耗时：<ms>  PSS：<MB>
结论：<P2-06 通过 / 卡在 X，下一步 Y>
```
