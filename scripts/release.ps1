# 本机构建可玩 APK 并发到 GitHub Release
#
# 为什么要本地构建：仓库不含运行时（JRE 约 400 MB 被 .gitignore 排除），
# CI 编出来的 APK 是空壳。本机已有完整工具链与 runtime，直接出可玩包。
#
# 用法：
#   pwsh -File X:\android\scripts\release.ps1                 # 0.1.0-alpha 自动递增
#   pwsh -File X:\android\scripts\release.ps1 -Tag v0.2.0     # 指定 tag
#   pwsh -File X:\android\scripts\release.ps1 -NoRelease      # 只构建不发布
#   pwsh -File X:\android\scripts\release.ps1 -Draft          # 发草稿

param(
    [string]$Tag = "",
    [string]$Repo = "aa398oo/newM-j-A",
    [switch]$NoRelease,
    [switch]$Draft,
    [switch]$SkipChecks
)

$ErrorActionPreference = 'Stop'
$env:TEMP = 'X:\android\.toolchain\tmp'
$env:TMP = 'X:\android\.toolchain\tmp'
$env:JAVA_HOME = 'X:\android\.toolchain\jdk\jdk-17.0.21+9'
$env:GRADLE_USER_HOME = 'X:\android\.toolchain\gradle-home'
$env:PATH = 'C:\Program Files\Git\cmd;C:\Program Files\GitHub CLI;' + $env:PATH

$root = 'X:\android'
$gradle = 'X:\android\.toolchain\gradle-extract\gradle-8.9\bin\gradle.bat'
$gh = 'C:\Program Files\GitHub CLI\gh.exe'
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'

Set-Location $root

# ---------- 1. 静态检查（失败就别构建） ----------
if (-not $SkipChecks) {
    Write-Host '== checks ==' -ForegroundColor Cyan
    $node = (Get-ChildItem 'C:\Users\as150\AppData\Local\Programs' -Filter node.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
    if ($node) {
        & $node 'scripts\check-touch.mjs' | Select-Object -Last 1
        if ($LASTEXITCODE -ne 0) { throw 'check-touch failed' }
    } else {
        Write-Host 'node not found, skip checks'
    }
}

# ---------- 2. 构建 ----------
Write-Host '== build ==' -ForegroundColor Cyan
& $gradle -p $root assembleDebug --console=plain 2>&1 | Select-String -Pattern '^e: |BUILD' | Select-Object -Last 3
if (-not (Test-Path $apk)) { throw "apk missing: $apk" }

$size = (Get-Item $apk).Length
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash
Write-Host ("apk: {0:N1} MB  sha256={1}" -f ($size / 1MB), $sha)

if ($NoRelease) {
    Copy-Item $apk (Join-Path $root 'dist\app-debug.apk') -Force -ErrorAction SilentlyContinue
    Write-Host 'build only, skip release'
    exit 0
}

# ---------- 3. tag ----------
if (-not $Tag) {
    $latest = (& $gh release list --repo $Repo --limit 1 --json tagName 2>$null | ConvertFrom-Json).tagName
    if ($latest -match '^v(\d+)\.(\d+)\.(\d+)') {
        $Tag = 'v{0}.{1}.{2}' -f [int]$Matches[1], [int]$Matches[2], ([int]$Matches[3] + 1)
    } else {
        $Tag = 'v0.1.0'
    }
}
Write-Host "tag: $Tag" -ForegroundColor Cyan

# ---------- 4. commit + push ----------
Write-Host '== commit ==' -ForegroundColor Cyan
& git add -A
$staged = (& git diff --cached --name-only | Measure-Object).Count
if ($staged -gt 0) {
    & git commit -q -m "release: $Tag"
    Write-Host "committed $staged files"
}
& git -c http.version=HTTP/1.1 push origin HEAD 2>&1 | Select-Object -Last 2

# tag 必须由脚本推，否则 release 指向旧提交
& git tag -f $Tag
& git -c http.version=HTTP/1.1 push -f origin $Tag 2>&1 | Select-Object -Last 1

# ---------- 5. release ----------
Write-Host '== release ==' -ForegroundColor Cyan
$notes = @"
首次公开发布。

- 内置 Pojav OpenJDK 17，FCL 渲染链路（GL4ES）
- 触控：根容器分流 + 双态手势机 + ``critical_*`` 正统通道，反射兜底
- 账号：离线 UUID + 微软设备码 OAuth
- 下载安装 / 短码分享 / 光影 / Fabric / 诊断导出 / 性能采样

APK 大小 $([math]::Round($size/1MB,1)) MB
SHA-256 ``$sha``

## 安装说明

1. 安装 APK（arm64-v8a，Android 8.0+）
2. 首次启动会解包内置运行时（约 3 秒）
3. 「下载」页选 1.20.1（**不要选 1.21+，那需要 Java 21**）
4. 装完进「实例」点播放
"@

$args_ = @('release','create',$Tag,'--repo',$Repo,'--title',"newM-j-A $Tag",'--notes',$notes,(Join-Path $root 'dist\app-debug.apk#newM-j-A-'+$Tag+'.apk'))
if ($Draft) { $args_ += '--draft' }
Copy-Item $apk (Join-Path $root 'dist\app-debug.apk') -Force -ErrorAction SilentlyContinue

& $gh @args_ 2>&1 | Select-Object -Last 3
Write-Host "done: https://github.com/$Repo/releases/tag/$Tag" -ForegroundColor Green
