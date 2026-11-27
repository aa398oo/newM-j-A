# 真机触控验证（一条命令跑完并给出判定）
# 用法: pwsh -File X:\android\scripts\verify-touch.ps1

param(
    [string]$Serial = '',
    [string]$InstanceId = 'c9d58e86-4a5f-4b89-af43-a055bef2f757',
    [string]$Apk = 'X:\android\app\build\outputs\apk\debug\app-debug.apk',
    [string]$Adb = 'X:\android\.toolchain\sdk\platform-tools\adb.exe',
    [int]$BootWaitSec = 60,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$env:TEMP = 'X:\android\.toolchain\tmp'
$env:TMP = 'X:\android\.toolchain\tmp'
$out = 'X:\android\.toolchain'

if (-not (Test-Path $Adb)) { Write-Host "missing adb: $Adb"; exit 2 }

Write-Host '== device =='
# 不硬编码序列号：取第一个在线设备，多设备时用 -Serial 指定。
if ([string]::IsNullOrWhiteSpace($Serial)) {
    $Serial = (& $Adb devices) | Where-Object { $_ -match '^\S+\s+device$' } |
        ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1
}
if ([string]::IsNullOrWhiteSpace($Serial)) {
    Write-Host 'no online device'
    Write-Host ((& $Adb devices) -join "`n")
    exit 2
}
Write-Host "ok: $Serial"

Write-Host '== install =='
if ($SkipInstall -and (Test-Path $Apk)) {
    Write-Host 'skip install'
} else {
    $install = & $Adb -s $Serial install -r $Apk 2>&1
    if (-not ($install -match 'Success')) { Write-Host "install failed: $install"; exit 3 }
    $mb = [math]::Round((Get-Item $Apk).Length / 1MB, 1)
    Write-Host "ok: $mb MB"
}

Write-Host '== reset state =='
& $Adb -s $Serial shell am force-stop dev.mineport.launcher | Out-Null
& $Adb -s $Serial logcat -c | Out-Null
& $Adb -s $Serial shell run-as dev.mineport.launcher rm -f files/touch.log | Out-Null
Write-Host 'ok'

Write-Host '== launch =='
$start = & $Adb -s $Serial shell am start -n dev.mineport.launcher/.MainActivity --es launch_instance $InstanceId 2>&1
Write-Host ($start -join ' ')
Write-Host "waiting $BootWaitSec s ..."
Start-Sleep -Seconds $BootWaitSec

Write-Host '== game process =='
$gamePid = (& $Adb -s $Serial shell pidof 'dev.mineport.launcher:game' 2>&1) -join ''
if (-not $gamePid.Trim()) {
    Write-Host 'no :game process'
    & $Adb -s $Serial logcat -d -s MineportGame MineportJNI AndroidRuntime 2>&1 | Select-Object -Last 40
    exit 4
}
Write-Host "ok: :game pid=$($gamePid.Trim())"

Write-Host '== tap 3 times =='
$pts = @( @(1356,1127), @(1360,1000), @(1356,900) )
foreach ($p in $pts) {
    & $Adb -s $Serial shell input tap $p[0] $p[1] | Out-Null
    Start-Sleep -Milliseconds 800
}
Write-Host 'ok'

Write-Host '== collect =='
$touch = (& $Adb -s $Serial shell run-as dev.mineport.launcher cat files/touch.log 2>&1) -join "`n"
$jni = (& $Adb -s $Serial logcat -d -s MineportJNI MineportGame 2>&1) -join "`n"
& $Adb -s $Serial shell screencap -p /sdcard/verify-touch.png | Out-Null
& $Adb -s $Serial pull /sdcard/verify-touch.png (Join-Path $out 'verify-touch.png') | Out-Null

$ptrCount = 0
foreach ($line in ($touch -split "`n")) {
    if ($line -match 'ptr ') { $ptrCount = $ptrCount + 1 }
}
$criticalOk = $jni -match 'critical: stack queue enabled'
$criticalHit = $jni -match 'critical: cursor_pos'

Write-Host ''
Write-Host '======== verdict ========'
Write-Host "touch.log ptr lines : $ptrCount"
Write-Host "critical resolved   : $criticalOk"
Write-Host "critical invoked    : $criticalHit"
Write-Host "screenshot          : $out\verify-touch.png"
Write-Host ''
if ($ptrCount -eq 0) {
    Write-Host '[FAIL] touch never reached TouchInjector'
    Write-Host '  check GameHostLayout is root and gameView is set'
} elseif (-not $criticalOk) {
    Write-Host '[HALF] touch reached injector but critical_* not resolved'
    Write-Host '  check: nm -D libpojavexec.so | grep critical_send_cursor_pos'
} elseif (-not $criticalHit) {
    Write-Host '[HALF] critical ready but not called'
    Write-Host '  check logcat for inject: MC MouseHandler'
} else {
    Write-Host '[PASS] touch -> critical -> MC executed'
    Write-Host '  if screen did not change, A/B critical_set_stackqueue 0'
}
Write-Host '=========================='
Write-Host ''
Write-Host 'touch.log tail:'
($touch -split "`n" | Select-Object -Last 8) | ForEach-Object { Write-Host "  $_" }
Write-Host ''
Write-Host 'jni tail:'
($jni -split "`n" | Select-Object -Last 12) | ForEach-Object { Write-Host "  $_" }
