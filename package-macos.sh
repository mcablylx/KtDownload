#!/bin/bash

echo "===================================="
echo "打包 KtDownload 为macOS DMG安装包"
echo "===================================="
echo ""

APP_VERSION="$(grep -E '^appVersion=' gradle.properties | head -n 1 | cut -d= -f2- | tr -d '\r')"
if [ -z "$APP_VERSION" ]; then
    echo "错误：gradle.properties 中未找到 appVersion"
    exit 1
fi

echo "正在清理旧的构建文件..."
./gradlew clean

echo ""
echo "正在准备内置 FFmpeg（首次会从官方源码构建，约几分钟；已存在则跳过）..."
./scripts/fetch-ffmpeg-macos.sh || exit 1

echo ""
echo "正在打包应用程序（这可能需要几分钟）..."
./gradlew composeApp:packageReleaseDistributionForCurrentOS

echo ""
echo "===================================="
echo "打包完成！"
echo "===================================="
echo ""
echo "安装包位置："
echo "composeApp/build/compose/binaries/main-release/dmg/KtDownload-$APP_VERSION.dmg"
echo "composeApp/build/compose/binaries/main-release/pkg/KtDownload-$APP_VERSION.pkg"
echo ""
echo "按回车键退出..."
read -n 1 -s
