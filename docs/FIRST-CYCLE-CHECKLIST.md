# 第一周期收口清单（2026-11-27）

第一周期的定义：**能进世界、能操作、有性能数据**。下面按「已验证 / 代码就绪待验 / 未开始」三档列出。

## 一、已验证（有真机证据）

| 项 | 证据 |
| --- | --- |
| JVM 创建 | `jvm-ready:17-internal`，`:game` 进程稳定存活 |
| MC 主菜单渲染 | 截图 `g6.png`（Minecraft 1.20.1 Demo 主菜单） |
| 进入世界 | 截图 `t4.png`（Demo 对话框关闭，切到游戏内视角） |
| FCL 输入通道解析 | `critical: cursor/button/key/scroll/screen/reset/stackqueue` 全部非空 |
| 队列模式 | `critical: stack queue enabled`（A/B 证明 0 会退回首页，必须 1） |
| 输入就绪门 | `critical: isInputReady=1 env=0x...` |
| 窗口尺寸 | `critical: screen size -> 2712x1220` |
| 点击能关对话框 | `input tap 1202 633` 后 Demo 提示框消失 |

## 二、代码就绪、待真机验证

| 项 | 代码位置 | 待验证据 |
| --- | --- | --- |
| 事件派发（pump） | `jni_bridge.cpp` 调 `pojavStartPumping()`；`enable-selfpump` 标记可切自建线程 | `pend1b` 在两次点击间 1→0 |
| 手指触摸 | `GameHost.kt` `TouchInjector.pointerDown/Move/Up` | 手指点 Continue 生效 |
| 多指分流 | `GameHostLayout` per-pointerId `owners` | 一手拖视角 + 一手按轮盘 |
| 轮盘按键 | `handleWheel` → `nativeSendKey` → `critical_send_key` | 按「前」玩家前进 |
| 纹理正确性 | `VersionInstaller` 补 asset index 到 `assets/indexes/<id>.json` | **需重装 1.20.1** 后画面正常 |
| 性能采样 | `PerfSampler` 每 2s 写 `files/perf-game.log` | 跑 2 分钟后读到 JSON 行 |

## 三、未开始

| 项 | 说明 |
| --- | --- |
| 结构重构 | `jni_bridge.cpp` 已 1500+ 行，需拆 6 个子模块（待触控验收后做） |
| UX-05 模组图标 | 需引入 Coil 依赖 |
| UX-06 模组更新检测 | 需批量查 Modrinth 版本 |
| P2 剩余 | 皮肤、令牌 Keystore 加密、联机鉴权、云同步 |

## 四、收口判定标准

真机跑通下面三条即可宣布第一周期收口：

1. **输入链路**：点一下屏幕，`logcat -s MineportJNI` 里 `ptr: pend1b` 从 1 回落到 0。
2. **画面正确**：重装 1.20.1 后地形/方块/物品栏纹理正常（不再是纯黑块）。
3. **性能数据**：跑 2 分钟后 `files/perf-game.log` 有 ≥30 行 JSON。

## 五、验收命令

```powershell
$adb = "X:\android\.toolchain\sdk\platform-tools\adb.exe"
$env:TEMP = "X:\android\.toolchain\tmp"; $env:TMP = $env:TEMP

# 1. 装最新包
& $adb install -r X:\android\dist\app-debug.apk

# 2. 清日志并启动
& $adb logcat -c
& $adb shell am start -n dev.mineport.launcher/.MainActivity `
    --es launch_instance c9d58e86-4a5f-4b89-af43-a055bef2f757

# 3. 等 60s 后点两下
Start-Sleep -Seconds 60
& $adb shell input tap 1202 633
& $adb shell input tap 1202 500

# 4. 读证据
& $adb logcat -d -s MineportJNI | Select-String "critical:|ptr:"
& $adb shell run-as dev.mineport.launcher cat files/touch.log
& $adb shell run-as dev.mineport.launcher cat files/perf-game.log
& $adb exec-out screencap -p > X:\android\.toolchain\check.png
```

如果第 3 步后 `pend1b` 不回落，补一步再试：

```powershell
& $adb shell run-as dev.mineport.launcher touch files/enable-selfpump
& $adb shell am force-stop dev.mineport.launcher
# 重新执行第 2 步
```
