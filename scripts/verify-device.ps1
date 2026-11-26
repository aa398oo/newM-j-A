# 真机一键验证脚本
# 用法：pwsh -File scripts/verify-device.ps1 [-Apk path] [-AutoTap] [-SkipInstall]
param(
    [string]$Apk = "X:\android\dist\app-debug.apk",
    [switch]$AutoTap,
    [switch]$SkipInstall,
    [string]$Adb = "X:\android\.toolchain\sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"
$env:TEMP = "X:\android\.toolchain\tmp"
$env:TMP = "X:\android\.toolchain\tmp"
$pkg = "dev.mineport.launcher"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$report = "X:\android\docs\verify-report-$stamp.txt"
$lines = New-Object System.Collections.Generic.List[string]
function L($s) { $lines.Add($s); Write-Host $s }

L "== verify-device $stamp =="

if (-not (Test-Path $Adb)) { L "adb 不存在：$Adb"; exit 2 }
$devices = (& $Adb devices) -split "`r?`n" | Where-Object { $_ -match "\bdevice$" }
if (-not $devices) { L "没有已连接的设备（adb devices 为空）。先连手机并允许 USB 调试。"; exit 2 }
L "设备：$($devices -join '; ')"

if (-not $SkipInstall) {
    L "-- 安装 APK"
    & $Adb install -r $Apk 2>&1 | ForEach-Object { L $_ }
}

L "-- 清空日志并启动"
& $Adb logcat -c 2>$null
& $Adb shell am force-stop $pkg
& $Adb shell am start -n "$pkg/.MainActivity" 2>&1 | ForEach-Object { L $_ }
Start-Sleep -Seconds 4

if ($AutoTap) {
    L "-- 进入实例页并点播放（竖屏坐标 344,2590 / 895,631）"
    & $Adb shell input tap 344 2590
    Start-Sleep -Seconds 2
    & $Adb shell input tap 895 631
    Start-Sleep -Seconds 25
}

L "-- 进程状态"
& $Adb shell pidof $pkg 2>&1 | ForEach-Object { L "pid: $_" }

L "-- startup.txt"
& $Adb shell "run-as $pkg cat files/crash/startup.txt" 2>&1 | ForEach-Object { L $_ }

L "-- 最近日志（Mineport*）"
& $Adb logcat -d -s MineportJNI MineportJVM MineportRuntime MineportInput 2>&1 | Select-Object -Last 40 | ForEach-Object { L $_ }

L "-- crash buffer"
& $Adb logcat -d -b crash -v brief 2>&1 | Select-Object -Last 20 | ForEach-Object { L $_ }

L "-- last-crash.txt"
& $Adb shell "run-as $pkg cat files/crash/last-crash.txt" 2>&1 | Select-Object -First 30 | ForEach-Object { L $_ }

$lines | Set-Content -Path $report -Encoding UTF8
Write-Host ""
Write-Host "报告已写入：$report"
