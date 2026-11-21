# Fetch the pinned LGPL FFmpeg build that is bundled into installers.
#
# The binary is NOT stored in git (134 MB). Run this before packaging locally:
#   powershell -File scripts/fetch-ffmpeg.ps1     (CI runs it with pwsh)
# CI runs the same script in the Windows packaging job.
#
# The pinned asset + SHA-256 below are documented in:
#   third-party/ffmpeg/SOURCE.txt
#   THIRD-PARTY-NOTICES.md

param(
    [string]$Destination = (Join-Path $PSScriptRoot "..\resources\windows\ffmpeg"),
    [switch]$Force
)

$ErrorActionPreference = "Stop"
# Windows PowerShell 5.1 的进度条会让大文件下载慢数倍
$ProgressPreference = "SilentlyContinue"

$ReleaseTag = "autobuild-2026-10-24-13-02"
$AssetName = "ffmpeg-n8.1.3-24-g92611900bb-win64-lgpl-8.1.zip"
$DownloadUrl = "https://github.com/BtbN/FFmpeg-Builds/releases/download/$ReleaseTag/$AssetName"
$ExpectedSha256 = "5a11d3d34496b6d4b3cedf0230739ddc3d2710c0ac58637ae134df801238d503"

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

if (-not $Force -and (Test-Path -LiteralPath $target) -and (Get-Sha256 $target) -eq $ExpectedSha256) {
    Copy-SourceInfo
    Write-Host "ffmpeg.exe already present and verified: $target"
    exit 0
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("ktdown-ffmpeg-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $zip = Join-Path $tempDir "ffmpeg.zip"
    Write-Host "Downloading $DownloadUrl"
    Invoke-WebRequest -Uri $DownloadUrl -OutFile $zip -UseBasicParsing

    $unpacked = Join-Path $tempDir "unpacked"
    Expand-Archive -LiteralPath $zip -DestinationPath $unpacked -Force

    $exe = Get-ChildItem -LiteralPath $unpacked -Recurse -Filter "ffmpeg.exe" |
        Select-Object -First 1
    if (-not $exe) {
        throw "ffmpeg.exe not found in $AssetName"
    }

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

    Copy-SourceInfo
    Write-Host "ffmpeg.exe installed and verified: $target"
}
finally {
    Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
}
