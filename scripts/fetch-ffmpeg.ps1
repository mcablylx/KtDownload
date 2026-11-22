# Fetch the pinned LGPL FFmpeg build that is bundled into Windows installers.
#
# The binary is NOT stored in git (134 MB > GitHub's 100 MB per-file limit). Run
# this before packaging locally:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/fetch-ffmpeg.ps1
# CI runs the same script in the Windows packaging job.
#
# Sources, in priority order:
#   1. an already verified resources/windows/ffmpeg/ffmpeg.exe (no network)
#   2. a pre-seeded zip via $env:KTDOWNLOAD_FFMPEG_ZIP (fully offline builds)
#   3. a pre-seeded zip in %GRADLE_USER_HOME%/vlcSetup/ (same offline cache as VLC/UPX)
#   4. this repository's own GitHub Release "ffmpeg" (stable, owner-controlled)
#   5. the upstream FFmpeg-Builds release (last-resort fallback; upstream deletes
#      old autobuild tags and renamed the repo, so this link may rot)
#
# Every source is verified against the pinned SHA-256 before it is accepted.
# Downloaded archives are cached locally so later builds work fully offline.
#
# The pinned asset + SHA-256 are documented in:
#   third-party/ffmpeg/SOURCE.txt
#   THIRD-PARTY-NOTICES.md

param(
    [string]$Destination = (Join-Path $PSScriptRoot "..\resources\windows\ffmpeg"),
    [switch]$Force
)

$ErrorActionPreference = "Stop"
# Windows PowerShell 5.1 的进度条会让大文件下载慢数倍
$ProgressPreference = "SilentlyContinue"

# --- pinned build -----------------------------------------------------------
$AssetName      = "ffmpeg-n8.1.3-24-g92611900bb-win64-lgpl-8.1.zip"
$ExpectedSha256 = "5a11d3d34496b6d4b3cedf0230739ddc3d2710c0ac58637ae134df801238d503"
# 构建依赖专用 tag，与 v1.0.0 这类版本发布分开，避免发新版本时误删
$ReleaseTag = "ffmpeg"
# 主源：项目自身 Release（自己控制，上游删 tag 也不受影响）
$SelfUrl = "https://github.com/mcablylx/KtDownload/releases/download/$ReleaseTag/$AssetName"
# 兜底源：上游（BtbN/FFmpeg-Builds 已改名为 FFmpeg-Builds/FFmpeg-Builds）
$UpstreamUrl = "https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/download/autobuild-2026-10-24-13-02/$AssetName"

function Get-Sha256([string]$Path) {
    (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

$null = New-Item -ItemType Directory -Force -Path $Destination
$targetDir = (Resolve-Path -LiteralPath $Destination).Path
$target = Join-Path $targetDir "ffmpeg.exe"

function Copy-SourceInfo {
    $source = Join-Path $PSScriptRoot "..\third-party\ffmpeg\SOURCE.txt"
    if (Test-Path -LiteralPath $source) {
        Copy-Item -LiteralPath $source -Destination (Join-Path $targetDir "SOURCE.txt") -Force
    }
}

# 1) 已验证的本地二进制，直接用
if (-not $Force -and (Test-Path -LiteralPath $target) -and (Get-Sha256 $target) -eq $ExpectedSha256) {
    Copy-SourceInfo
    Write-Host "ffmpeg.exe already present and verified: $target"
    exit 0
}

# 本地 zip 缓存目录（与 VLC/UPX 的离线兜底共用同一个文件夹）
$gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $HOME ".gradle" }
$cacheDir   = Join-Path $gradleHome "vlcSetup"
$cacheZip   = Join-Path $cacheDir $AssetName

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("ktdown-ffmpeg-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

try {
    $zip = $null
    $downloaded = $false

    # 2) 环境变量指定的离线包
    if ($env:KTDOWNLOAD_FFMPEG_ZIP -and (Test-Path -LiteralPath $env:KTDOWNLOAD_FFMPEG_ZIP)) {
        Write-Host "Using pre-seeded FFmpeg archive: $env:KTDOWNLOAD_FFMPEG_ZIP"
        $zip = (Resolve-Path -LiteralPath $env:KTDOWNLOAD_FFMPEG_ZIP).Path
    }
    # 3) 本地缓存（上次下载的，或手动预置到 vlcSetup 目录的）
    elseif (Test-Path -LiteralPath $cacheZip) {
        Write-Host "Using cached FFmpeg archive: $cacheZip"
        $zip = $cacheZip
    }
    # 4/5) 依次尝试自身 Release 与上游
    else {
        $destZip = Join-Path $tempDir $AssetName
        foreach ($url in @($SelfUrl, $UpstreamUrl)) {
            Write-Host "Downloading $url"
            try {
                Invoke-WebRequest -Uri $url -OutFile $destZip -UseBasicParsing -TimeoutSec 1800
                $zip = $destZip
                $downloaded = $true
                break
            }
            catch {
                Write-Warning "Download failed from $url : $($_.Exception.Message)"
            }
        }
        if (-not $zip) {
            throw "无法下载 FFmpeg。请把 $AssetName 放到 $cacheDir ，或设置 KTDOWNLOAD_FFMPEG_ZIP 指向本地 zip 后重试。"
        }
    }

    $unpacked = Join-Path $tempDir "unpacked"
    Expand-Archive -LiteralPath $zip -DestinationPath $unpacked -Force

    $exe = Get-ChildItem -LiteralPath $unpacked -Recurse -Filter "ffmpeg.exe" | Select-Object -First 1
    if (-not $exe) { throw "ffmpeg.exe not found in $AssetName" }

    Copy-Item -LiteralPath $exe.FullName -Destination $target -Force
    $actual = Get-Sha256 $target
    if ($actual -ne $ExpectedSha256) {
        Remove-Item -LiteralPath $target -Force
        throw "SHA256 mismatch: expected $ExpectedSha256, got $actual"
    }

    # Expand-Archive 从网络下载的 zip 解压时会给 exe 标 Low Integrity Level，
    # 导致 ffmpeg 只能写自己所在盘——跨盘写文件会报 "Permission denied" (exit -13)。
    # 提升到 Medium 后才能正常写用户指定的下载目录（可能在任意盘）。
    icacls $target /setintegritylevel Medium | Out-Null

    # 下载成功则缓存 zip，供下次离线复用
    if ($downloaded) {
        New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
        Copy-Item -LiteralPath $zip -Destination $cacheZip -Force
        Write-Host "Cached archive to $cacheZip for offline reuse"
    }

    Copy-SourceInfo
    Write-Host "ffmpeg.exe installed and verified: $target"
}
finally {
    Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
}
