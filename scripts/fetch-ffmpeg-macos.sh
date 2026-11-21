#!/usr/bin/env bash
# Prepare the pinned LGPL FFmpeg that is bundled into the macOS app.
#
# Fast path (offline): third-party/binaries/ffmpeg-macos-universal is a
# universal (x86_64 + arm64) build committed to this repo. When present and
# matching PREBUILT_SHA256, we just copy it into place — no network, no compiler.
# This is what makes packaging reproducible for people who clone and build.
#
# Fallback: if the prebuilt binary is absent or corrupted, build from the official
# release tarball (SHA-256 pinned) with exactly the components we use. BtbN/FFmpeg-Builds
# publishes no macOS assets and prebuilt LGPL macOS binaries omit the `concat`
# demuxer that FfmpegMuxer needs for TS playlists, so we cannot use those.
#
# Run on macOS before packaging:
#   ./scripts/fetch-ffmpeg-macos.sh          # reuse existing / prebuilt, else build
#   ./scripts/fetch-ffmpeg-macos.sh --force  # ignore both, rebuild from source
#
# Output: resources/macos/ffmpeg/ffmpeg (+x) and SOURCE.txt
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/resources/macos/ffmpeg"
TARGET="$DEST/ffmpeg"
PREBUILT="$REPO_ROOT/third-party/binaries/ffmpeg-macos-universal"

FFMPEG_VERSION="8.1.3"
TARBALL_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
# 官方源码包 SHA（2026-10-25 从 ffmpeg.org 下载后计算），升级版本时必须更新
EXPECTED_SHA256="7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3"
# 仓库预置 universal 二进制 SHA（2026-11-21 由本脚本的 configure 参数编译并 lipo 合并）
PREBUILT_SHA256="ec49289086ee9334cd33f83c24bd51b6d3907761dc3f13e379927916ecb5aadb"

FORCE=0
if [[ "${1:-}" == "--force" ]]; then FORCE=1; fi

# 自检：许可配置 + FfmpegMuxer 实际用到的组件，缺一个就说明构建/还原出了问题。
# 通过 stdout 返回 configuration 行供 SOURCE.txt 使用；诊断信息走 stderr。
verify_ffmpeg() {
    local version_output config
    version_output="$("$TARGET" -hide_banner -version)"
    config="$(printf '%s\n' "$version_output" | grep '^configuration:' || true)"
    case "$config" in
    *--enable-version3*) ;;
    *)
        echo "error: ffmpeg was built without --enable-version3: $config" >&2
        return 1
        ;;
    esac
    case "$config" in
    *--enable-gpl* | *--enable-nonfree*)
        echo "error: ffmpeg contains GPL/nonfree components: $config" >&2
        return 1
        ;;
    esac
    local check kind name list
    for check in "demuxers:concat" "demuxers:mpegts" "muxers:mp4" "decoders:aac" "bsfs:aac_adtstoasc"; do
        kind="${check%%:*}"
        name="${check##*:}"
        list="$("$TARGET" -hide_banner "-$kind")"
        if ! printf '%s\n' "$list" | grep -qw "$name"; then
            echo "error: ffmpeg is missing $kind '$name'" >&2
            return 1
        fi
    done
    printf '%s\n' "$config"
}

write_source_info() {
    local config="$1" origin="$2" built_for="$3"
    cat >"$DEST/SOURCE.txt" <<EOF
FFmpeg $FFMPEG_VERSION
Source tarball: $TARBALL_URL
SHA256:         $EXPECTED_SHA256
Origin:         $origin
Built for:      $built_for
Built on:       $(date -u +%Y-%m-%dT%H:%M:%SZ)
Configuration:  ${config#configuration: }

This binary is LGPL-3.0-or-later (built with --enable-version3, no GPL/nonfree
components). The build script is scripts/fetch-ffmpeg-macos.sh in this repository;
the source is the official release tarball above (matching the SHA256).
EOF
}

# 1) 目标已存在且版本正确 → 直接用，零开销
if [[ $FORCE -eq 0 && -x "$TARGET" ]]; then
    EXISTING_VERSION="$("$TARGET" -hide_banner -version 2>/dev/null || true)"
    if [[ "$EXISTING_VERSION" == *"ffmpeg version ${FFMPEG_VERSION}"* ]]; then
        echo "ffmpeg already in place and verified: $TARGET"
        exit 0
    fi
fi

# 2) 优先用仓库预置的 universal 二进制（离线、跨 Intel/Apple Silicon）
if [[ $FORCE -eq 0 && -f "$PREBUILT" ]]; then
    ACTUAL_SHA256="$(shasum -a 256 "$PREBUILT" | awk '{print $1}')"
    if [[ "$ACTUAL_SHA256" == "$PREBUILT_SHA256" ]]; then
        echo "Using prebuilt universal ffmpeg from $PREBUILT"
        mkdir -p "$DEST"
        cp "$PREBUILT" "$TARGET"
        chmod +x "$TARGET"
        CONFIG="$(verify_ffmpeg)"
        write_source_info "$CONFIG" "third-party/binaries/ffmpeg-macos-universal (SHA256 $PREBUILT_SHA256)" "universal x86_64 + arm64 apple-darwin"
        echo "ffmpeg restored from prebuilt binary and verified: $TARGET"
        exit 0
    else
        echo "warning: prebuilt ffmpeg SHA256 mismatch (expected $PREBUILT_SHA256, got $ACTUAL_SHA256); falling back to source build" >&2
    fi
fi

# 3) 兜底：下载官方源码包 + 本地编译（需要网络与 Xcode Command Line Tools）
if ! command -v make >/dev/null; then
    echo "error: make not found; install Xcode Command Line Tools: xcode-select --install" >&2
    exit 1
fi

ARCH="$(uname -m)"
JOBS="$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/ktdown-ffmpeg.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

echo "No usable prebuilt binary; building ffmpeg from source (this takes a few minutes)"
echo "Downloading $TARBALL_URL"
curl -fsSL --retry 3 --retry-delay 2 -o "$WORK/ffmpeg.tar.xz" "$TARBALL_URL"

ACTUAL_SHA256="$(shasum -a 256 "$WORK/ffmpeg.tar.xz" | awk '{print $1}')"
if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
    echo "error: source tarball SHA256 mismatch: expected $EXPECTED_SHA256, got $ACTUAL_SHA256" >&2
    exit 1
fi

tar -xJf "$WORK/ffmpeg.tar.xz" -C "$WORK"
SRC="$WORK/ffmpeg-$FFMPEG_VERSION"

# 在 Apple Silicon 上原生编译时，ffmpeg 会自动产出 arm64；这里不做交叉编译。
(
    cd "$SRC"
    ./configure \
        --disable-everything \
        --disable-autodetect \
        --disable-doc \
        --disable-debug \
        --disable-network \
        --disable-x86asm \
        --disable-gpl \
        --disable-nonfree \
        --enable-version3 \
        --disable-programs \
        --enable-ffmpeg \
        --enable-protocol=file \
        --enable-protocol=pipe \
        --enable-demuxer=mpegts \
        --enable-demuxer=mov \
        --enable-demuxer=concat \
        --enable-muxer=mp4 \
        --enable-parser=h264 \
        --enable-parser=hevc \
        --enable-parser=aac \
        --enable-decoder=aac \
        --enable-bsf=aac_adtstoasc
    make -j"$JOBS"
)

mkdir -p "$DEST"
cp "$SRC/ffmpeg" "$TARGET"
chmod +x "$TARGET"

CONFIG="$(verify_ffmpeg)"
write_source_info "$CONFIG" "built from official source tarball" "$ARCH-apple-darwin"
echo "ffmpeg built and verified: $TARGET"
