#!/bin/bash
# 打包 KtDownload 为 macOS DMG / PKG 安装包。
#
# P2-30：set -euo pipefail —— 任一 gradle / 脚本失败立即退出，不会再误报「打包完成！」；
# 所有路径基于脚本自身位置解析（不依赖调用者 cwd）；结尾只在交互终端等待按键，
# CI / 重定向 stdin 时不再阻塞自动化。
# P2-24：KTDL_NOTARIZE=1 时接公证脚本；KTDL_MACOS_SIGN_IDENTITY 给定时走真实签名。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

GRADLEW="$REPO_ROOT/gradlew"
PROPERTIES_FILE="$REPO_ROOT/gradle.properties"
FFMPEG_SCRIPT="$REPO_ROOT/scripts/fetch-ffmpeg-macos.sh"
NOTARIZE_SCRIPT="$REPO_ROOT/scripts/notarize-macos.sh"

echo "===================================="
echo "打包 KtDownload 为 macOS DMG 安装包"
echo "===================================="
echo ""

if [[ ! -f "$PROPERTIES_FILE" ]]; then
    echo "错误：未找到 $PROPERTIES_FILE" >&2
    exit 1
fi
APP_VERSION="$(grep -E '^appVersion=' "$PROPERTIES_FILE" | head -n 1 | cut -d= -f2- | tr -d '\r')"
if [[ -z "$APP_VERSION" ]]; then
    echo "错误：gradle.properties 中未找到 appVersion" >&2
    exit 1
fi

echo "正在清理旧的构建文件..."
"$GRADLEW" clean --console=plain

echo ""
echo "正在准备内置 FFmpeg（预置归档存在则直接还原，否则回退下载/源码编译，约几分钟）..."
"$FFMPEG_SCRIPT"

echo ""
echo "正在打包应用程序（这可能需要几分钟）..."
gradle_args=(:composeApp:packageReleaseDistributionForCurrentOS --no-configuration-cache --console=plain)
if [[ -n "${KTDL_MACOS_SIGN_IDENTITY:-}" ]]; then
    gradle_args+=("-PmacosSignIdentity=$KTDL_MACOS_SIGN_IDENTITY")
fi
"$GRADLEW" "${gradle_args[@]}"

# 公证需要 Apple 凭证；脚本会自行校验并在缺失时明确报错退出。
if [[ "${KTDL_NOTARIZE:-0}" == "1" ]]; then
    echo ""
    echo "正在签名安装包并提交公证..."
    bash "$NOTARIZE_SCRIPT"
fi

echo ""
echo "===================================="
echo "打包完成！"
echo "===================================="
echo ""
echo "安装包位置："
echo "composeApp/build/compose/binaries/main-release/dmg/KtDownload-$APP_VERSION.dmg"
echo "composeApp/build/compose/binaries/main-release/pkg/KtDownload-$APP_VERSION.pkg"
echo ""

# 只在交互式终端等待按键；CI / 非 tty 时不阻塞（P2-30）。
if [[ -t 0 && -z "${CI:-}" ]]; then
    echo "按回车键退出..."
    read -r -n 1 -s || true
fi
