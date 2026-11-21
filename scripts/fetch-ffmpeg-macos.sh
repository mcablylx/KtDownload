#!/usr/bin/env bash
# Build the pinned LGPL FFmpeg that is bundled into the macOS app.
#
# Why build instead of download: BtbN/FFmpeg-Builds (our Windows source) publishes
# no macOS assets, and the prebuilt macOS LGPL binaries we checked omit the `concat`
# demuxer that FfmpegMuxer needs for TS playlists. Building the official release
# tarball with the flags below gives a reproducible LGPL-3.0-or-later binary with
# exactly the components we use (`-c copy` muxing, plus the AAC decoder needed to
# probe ADTS sample rate/channels).
#
# Run on macOS before packaging. The build is native, so the same script produces
# x86_64 on an Intel Mac and arm64 on Apple Silicon:
#   ./scripts/fetch-ffmpeg-macos.sh          # skip if already built
#   ./scripts/fetch-ffmpeg-macos.sh --force  # rebuild
#
# Output: resources/macos/ffmpeg/ffmpeg (+x) and SOURCE.txt
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/resources/macos/ffmpeg"
TARGET="$DEST/ffmpeg"

FFMPEG_VERSION="8.1.3"
TARBALL_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
# 官方发布包（2026-10-25 从 ffmpeg.org 下载后计算），升级版本时必须同时更新
EXPECTED_SHA256="7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3"

FORCE=0
if [[ "${1:-}" == "--force" ]]; then FORCE=1; fi

if [[ $FORCE -eq 0 && -x "$TARGET" ]]; then
    EXISTING_VERSION="$("$TARGET" -hide_banner -version 2>/dev/null || true)"
    if [[ "$EXISTING_VERSION" == *"ffmpeg version ${FFMPEG_VERSION}"* ]]; then
        echo "ffmpeg already built and verified: $TARGET"
        exit 0
    fi
fi

if ! command -v make >/dev/null; then
    echo "error: make not found; install Xcode Command Line Tools: xcode-select --install" >&2
    exit 1
fi

ARCH="$(uname -m)"
JOBS="$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/ktdown-ffmpeg.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

echo "Downloading $TARBALL_URL"
curl -fsSL --retry 3 --retry-delay 2 -o "$WORK/ffmpeg.tar.xz" "$TARBALL_URL"

ACTUAL_SHA256="$(shasum -a 256 "$WORK/ffmpeg.tar.xz" | awk '{print $1}')"
if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
    echo "error: SHA256 mismatch: expected $EXPECTED_SHA256, got $ACTUAL_SHA256" >&2
    exit 1
fi

tar -xJf "$WORK/ffmpeg.tar.xz" -C "$WORK"
SRC="$WORK/ffmpeg-$FFMPEG_VERSION"

# 只编 -c copy 需要的组件；--disable-x86asm 省掉 nasm/yasm 依赖（拷贝流不需要 asm 加速）。
# 例外是 AAC 解码器：TS 的 ADTS 头没有进 codecpar，avformat_find_stream_info 要靠解码
# 几帧才能拿到采样率/声道数，否则 mp4 封装会报 "sample rate not set"（实测复现）。
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

# 自检：许可配置 + FfmpegMuxer 实际用到的组件，缺一个就说明构建参数写错了
VERSION_OUTPUT="$("$TARGET" -hide_banner -version)"
CONFIG="$(printf '%s\n' "$VERSION_OUTPUT" | grep '^configuration:' || true)"
case "$CONFIG" in
*--enable-version3*) ;;
*)
    echo "error: ffmpeg was built without --enable-version3: $CONFIG" >&2
    exit 1
    ;;
esac
case "$CONFIG" in
*--enable-gpl* | *--enable-nonfree*)
    echo "error: ffmpeg contains GPL/nonfree components: $CONFIG" >&2
    exit 1
    ;;
esac
for check in "demuxers:concat" "demuxers:mpegts" "muxers:mp4" "decoders:aac" "bsfs:aac_adtstoasc"; do
    kind="${check%%:*}"
    name="${check##*:}"
    LIST="$("$TARGET" -hide_banner "-$kind")"
    if ! printf '%s\n' "$LIST" | grep -qw "$name"; then
        echo "error: built ffmpeg is missing $kind '$name'" >&2
        exit 1
    fi
done

cat >"$DEST/SOURCE.txt" <<EOF
FFmpeg $FFMPEG_VERSION
Source tarball: $TARBALL_URL
SHA256:         $EXPECTED_SHA256
Built on:       $(date -u +%Y-%m-%dT%H:%M:%SZ)
Built for:      $ARCH-apple-darwin
Configuration:  ${CONFIG#configuration: }

This binary is LGPL-3.0-or-later (built with --enable-version3, no GPL/nonfree
components). The build script is scripts/fetch-ffmpeg-macos.sh in this repository;
the source is the official release tarball above (matching the SHA256).
EOF

echo "ffmpeg built and verified: $TARGET"
