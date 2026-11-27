# Real-device touch verification: install -> launch -> drag -> wheel -> perf -> verdict
# Usage: pwsh -File X:\android\scripts\verify-touch.ps1
# NOTE: keep comments ASCII - Windows PowerShell 5.1 mis-parses some multibyte comment bytes.

param(
    [string]$Serial = '',
    [string]$InstanceId = 'c9d58e86-4a5f-4b89-af43-a055bef2f757',
    [string]$Apk = 'X:\android\app\build\outputs\apk\debug\app-debug.apk',
    [string]$Adb = 'X:\android\.toolchain\sdk\platform-tools\adb.exe',
    [int]$BootWaitSec = 90,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$env:TEMP = 'X:\android\.toolchain\tmp'
$env:TMP = 'X:\android\.toolchain\tmp'
$out = 'X:\android\.toolchain'

if (-not (Test-Path $Adb)) { Write-Host "missing adb: $Adb"; exit 2 }

Write-Host '== device =='
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

Write-Host '== screen metrics =='
$densityDpi = 160
$dm = (& $Adb -s $Serial shell wm density) -join ' '
$md = [regex]::Match($dm, '(\d+)')
if ($md.Success) { $densityDpi = [int]$md.Groups[1].Value }
$scale = $densityDpi / 160.0

$wm = (& $Adb -s $Serial shell wm size) -join ' '
$ms = [regex]::Match($wm, '(\d+)x(\d+)')
if (-not $ms.Success) { Write-Host "cannot read wm size: $wm"; exit 5 }
$edgeA = [int]$ms.Groups[1].Value
$edgeB = [int]$ms.Groups[2].Value
# Game runs landscape: long edge is width.
$ScreenW = [math]::Max($edgeA, $edgeB)
$ScreenH = [math]::Min($edgeA, $edgeB)
Write-Host "density=${densityDpi}dpi scale=$scale size=${ScreenW}x${ScreenH}"

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

Write-Host '== stage 1: taps =='
$pts = @( @(1356,1127), @(1360,1000), @(1356,900) )
foreach ($p in $pts) {
    & $Adb -s $Serial shell input tap $p[0] $p[1] | Out-Null
    Start-Sleep -Milliseconds 700
}
Write-Host 'ok'

Write-Host '== stage 2: look drag =='
$dragStartX = [int]($ScreenW * 0.55)
$dragEndX = [int]($ScreenW * 0.75)
$dragY = [int]($ScreenH * 0.45)
& $Adb -s $Serial shell input swipe $dragStartX $dragY $dragEndX $dragY 700 | Out-Null
Start-Sleep -Milliseconds 500
Write-Host "ok: swipe $dragStartX,$dragY -> $dragEndX,$dragY"

Write-Host '== stage 3: wheel tap (front sector) =='
# Wheel: 220dp square, 12dp margin from bottom-left; front sector sits straight above center.
$wheelPx = [int](220 * $scale)
$marginPx = [int](12 * $scale)
$wheelCx = $marginPx + [int]($wheelPx / 2)
$wheelCy = $ScreenH - $marginPx - [int]($wheelPx / 2)
$sectorR = [int]($wheelPx * 0.5 * 0.42 * 0.60)
$frontX = $wheelCx
$frontY = $wheelCy - $sectorR
& $Adb -s $Serial shell input tap $frontX $frontY | Out-Null
Start-Sleep -Milliseconds 600
Write-Host "ok: tap $frontX,$frontY"

Write-Host '== collect =='
$touch = (& $Adb -s $Serial shell run-as dev.mineport.launcher cat files/touch.log 2>&1) -join "`n"
$jni = (& $Adb -s $Serial logcat -d -s MineportJNI MineportGame MineportInput 2>&1) -join "`n"
$perf = (& $Adb -s $Serial shell run-as dev.mineport.launcher cat files/perf-game.log 2>&1) -join "`n"
& $Adb -s $Serial shell screencap -p /sdcard/verify-touch.png | Out-Null
& $Adb -s $Serial pull /sdcard/verify-touch.png (Join-Path $out 'verify-touch.png') | Out-Null

$ptrLines = 0
$moveLines = 0
foreach ($line in ($touch -split "`n")) {
    if ($line -match 'ptr ') { $ptrLines = $ptrLines + 1 }
    if ($line -match 'a=1') { $moveLines = $moveLines + 1 }
}
$criticalReady = $jni -match 'critical: stack queue enabled'
$inputReady = $jni -match 'critical: isInputReady=1'
$criticalHit = $jni -match 'pointer: x='
$wheelPressed = $jni -match 'wheel press'
$perfLines = 0
foreach ($line in ($perf -split "`n")) {
    if ($line -match '"t":') { $perfLines = $perfLines + 1 }
}

Write-Host ''
Write-Host '======== verdict ========'
Write-Host "touch.log ptr lines : $ptrLines (move=$moveLines)"
Write-Host "critical queue      : $criticalReady"
Write-Host "input ready flag    : $inputReady"
Write-Host "pointer forwarded   : $criticalHit"
Write-Host "wheel press logged  : $wheelPressed"
Write-Host "perf samples        : $perfLines"
Write-Host "screenshot          : $out\verify-touch.png"
Write-Host ''
if ($ptrLines -eq 0) {
    Write-Host '[FAIL] touch never reached TouchInjector'
    Write-Host '  check GameHostLayout is root and gameView is set'
} elseif (-not $criticalReady) {
    Write-Host '[FAIL] critical_* not resolved from libpojavexec'
} elseif (-not $inputReady) {
    Write-Host '[HALF] critical ready but isInputReady=0 (events dropped)'
    Write-Host '  MC must run glfwPollEvents once before input is accepted'
} elseif ($moveLines -eq 0) {
    Write-Host '[HALF] only taps reached injector, no MOVE (look drag absent)'
} elseif (-not $wheelPressed) {
    Write-Host '[HALF] look drag ok, wheel sector not hit - check wheel geometry'
} elseif ($perfLines -eq 0) {
    Write-Host '[HALF] touch chain ok, but no perf samples (PerfSampler not started)'
} else {
    Write-Host '[PASS] taps + look drag + wheel + perf samples all present'
}
Write-Host '=========================='
Write-Host ''
Write-Host 'touch.log tail:'
($touch -split "`n" | Select-Object -Last 10) | ForEach-Object { Write-Host "  $_" }
Write-Host ''
Write-Host 'jni tail:'
($jni -split "`n" | Select-Object -Last 12) | ForEach-Object { Write-Host "  $_" }
Write-Host ''
Write-Host 'perf tail:'
($perf -split "`n" | Where-Object { $_ -match '"t":' } | Select-Object -Last 2) | ForEach-Object { Write-Host "  $_" }
