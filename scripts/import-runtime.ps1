# 从上游 apk 里导运行时（Windows PowerShell）
#
# 支持两种上游布局：
#   FCL      ：assets/app_runtime/java/jre17/{universal.tar.xz, bin-arm64.tar.xz}
#   Amethyst ：assets/components/jre/{universal.tar.xz, bin-arm64.tar.xz}
#
# 依赖：7zr.exe（解 .tar.xz，没有 xz 的 Windows 需要它）+ node（本仓库的 untar.mjs）
#   .runtime-cache/7zr.exe        可从 https://www.7-zip.org/a/7zr.exe 下载
#   .runtime-cache/untar.mjs      本仓库已带
#
# 用法：
#   scripts/import-runtime.ps1 -Apk .runtime-cache/FCL-arm64.apk -ListOnly
#   scripts/import-runtime.ps1 -Apk .runtime-cache/FCL-arm64.apk
#   可选 -SevenZip <path> -Node <path> -SkipLibs

param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$JniLibs = "app/src/main/jniLibs/arm64-v8a",
    [string]$Runtime = "app/src/main/assets/runtime",
    [string]$SevenZip = ".runtime-cache/7zr.exe",
    [string]$Node = "node",
    [switch]$SkipLibs,
    [switch]$ListOnly
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

$root = Split-Path -Parent $PSScriptRoot
$untar = Join-Path $root ".runtime-cache/untar.mjs"
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$zip = [System.IO.Compression.ZipFile]::OpenRead($apkPath)

function Expand-TarXz([string]$archive, [string]$target, [string]$seven) {
    $tmp = Join-Path $env:TEMP ("xz-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    & $seven x -y "-o$tmp" $archive | Out-Null
    $tar = Get-ChildItem $tmp -Filter *.tar | Select-Object -First 1
    if ($null -eq $tar) { throw "解 xz 后没有拿到 tar：$archive" }
    & $Node $untar $tar.FullName $target
    Remove-Item $tmp -Recurse -Force
}

try {
    $entries = $zip.Entries
    Write-Host "== $apkPath ($([math]::Round($zip.Length / 1MB, 1)) MB, $($entries.Count) entries)"

    # 1) JRE：按目录成对找 universal + bin-<arch>
    $groups = @{}
    foreach ($e in $entries) {
        if ($e.FullName -notmatch '^(assets/(?:app_runtime/java|components)/[^/]+)/(universal|bin-[^/]+)\.tar\.xz$') { continue }
        $dir = $Matches[1]
        if (-not $groups.ContainsKey($dir)) { $groups[$dir] = @{} }
        $groups[$dir][$Matches[2]] = $e
    }
    Write-Host "`n-- JRE 组 --"
    foreach ($dir in ($groups.Keys | Sort-Object)) {
        $g = $groups[$dir]
        $sizes = ($g.Keys | Sort-Object | ForEach-Object { "$_=$([math]::Round($g[$_].Length / 1MB, 1))MB" }) -join "  "
        Write-Host "  $dir  ($sizes)"
    }
    if ($groups.Count -eq 0) {
        Write-Host "`n没找到 JRE 组。已知布局：assets/app_runtime/java/jre17/universal.tar.xz + bin-arm64.tar.xz"
    }

    # 2) 原生库
    $systemLibs = @("libEGL.so", "libGLESv2.so", "libGLESv1_CM.so")
    $libs = $entries | Where-Object {
        $_.FullName -match '^lib/arm64-v8a/[^/]+\.so$' -and
        $systemLibs -notcontains [System.IO.Path]::GetFileName($_.FullName)
    }
    Write-Host "`n-- lib/arm64-v8a ($($libs.Count) 个) --"
    $libs | Sort-Object FullName | ForEach-Object {
        Write-Host ("  {0,-30} {1,7:N1} MB" -f [System.IO.Path]::GetFileName($_.FullName), ($_.Length / 1MB))
    }

    if ($ListOnly) { return }

    if (-not $SkipLibs) {
        New-Item -ItemType Directory -Force -Path $JniLibs | Out-Null
        foreach ($e in $libs) {
            $target = Join-Path $JniLibs ([System.IO.Path]::GetFileName($e.FullName))
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $target, $true)
            Write-Host "  -> $target"
        }
    }

    if ($groups.Count -gt 0 -and -not (Test-Path $SevenZip)) {
        throw "缺少 7zr.exe（$SevenZip）。从 https://www.7-zip.org/a/7zr.exe 下载后重试，或加 -ListOnly 只看清单。"
    }

    foreach ($dir in ($groups.Keys | Sort-Object)) {
        $g = $groups[$dir]
        $tmpDir = Join-Path $env:TEMP ("jre-" + [guid]::NewGuid().ToString("N"))
        New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
        Write-Host "`n合并解包 $dir ..."
        foreach ($kind in ($g.Keys | Sort-Object)) {
            $pack = Join-Path $tmpDir "$kind.tar.xz"
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($g[$kind], $pack, $true)
            New-Item -ItemType Directory -Force -Path $Runtime | Out-Null
            Expand-TarXz $pack (Resolve-Path $Runtime).Path $SevenZip
        }
        Remove-Item $tmpDir -Recurse -Force
    }
} finally {
    $zip.Dispose()
}

Write-Host "`n== 校验 =="
$check = Join-Path $PSScriptRoot "check-runtime.mjs"
if (Test-Path $check) {
    & $Node $check
}
