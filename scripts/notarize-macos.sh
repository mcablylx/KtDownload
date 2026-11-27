#!/bin/bash
# KtDownload — macOS Developer ID 签名 + 公证（P2-24）
#
# 为什么单独一个脚本：公证需要 Apple 凭证（Apple ID / Team ID / App 专用密码，
# 或提前存好的 notarytool keychain profile），不适合塞进 Gradle 构建或 CI 的明文配置。
# build.gradle.kts 只负责给 .app 与其内部二进制签名（默认 ad-hoc；可用
#   -PmacosSignIdentity="Developer ID Application: Name (TEAMID)"
# 切换为真实签名并自动补 --options runtime / --timestamp）。
# 本脚本负责后半段：安装包容器签名 → 提交公证 → staple → Gatekeeper 校验。
#
# 用法（一次性存凭证）：
#   xcrun notarytool store-credentials "KtDownload" \
#       --apple-id "<Apple ID>" --team-id "<Team ID>" --password "<app-specific-password>"
#
# 打包并公证：
#   export APP_SIGN_IDENTITY="Developer ID Application: Name (TEAMID)"
#   export INSTALLER_SIGN_IDENTITY="Developer ID Installer: Name (TEAMID)"
#   export APPLE_NOTARY_PROFILE="KtDownload"
#   ./gradlew -PmacosSignIdentity="$APP_SIGN_IDENTITY" :composeApp:packageReleaseDistributionForCurrentOS
#   bash scripts/notarize-macos.sh
#
# 不用 keychain profile 也可以直接给三元组：
#   APPLE_ID=... APPLE_TEAM_ID=... APPLE_APP_PASSWORD=... \
#       bash scripts/notarize-macos.sh path/to/KtDownload-1.0.3.dmg
#
# 可传入一个或多个 .dmg / .pkg 路径；不传则在 build/compose/binaries/main-release 下自动查找。
# 只能在 macOS + 有真实 Developer ID 证书时运行；无凭证环境不要调用（会明确报错退出）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "错误：签名与公证只能在 macOS 上执行（当前 $(uname -s)）。" >&2
    exit 1
fi

for tool in xcrun codesign productsign spctl; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "错误：缺少 $tool（需要 Xcode / Xcode Command Line Tools）。" >&2
        exit 1
    fi
done

# --- 凭证：keychain profile 或 Apple ID 三元组，二选一 ---
if [[ -n "${APPLE_NOTARY_PROFILE:-}" ]]; then
    NOTARY_AUTH=(--keychain-profile "$APPLE_NOTARY_PROFILE")
elif [[ -n "${APPLE_ID:-}" && -n "${APPLE_TEAM_ID:-}" && -n "${APPLE_APP_PASSWORD:-}" ]]; then
    NOTARY_AUTH=(--apple-id "$APPLE_ID" --team-id "$APPLE_TEAM_ID" --password "$APPLE_APP_PASSWORD")
else
    echo "错误：未配置公证凭证。" >&2
    echo "      请设置 APPLE_NOTARY_PROFILE，或同时设置 APPLE_ID / APPLE_TEAM_ID / APPLE_APP_PASSWORD。" >&2
    echo "      参考：xcrun notarytool store-credentials <profile> --apple-id ... --team-id ... --password ..." >&2
    exit 1
fi

if [[ -z "${APP_SIGN_IDENTITY:-}" || -z "${INSTALLER_SIGN_IDENTITY:-}" ]]; then
    echo "错误：需要 APP_SIGN_IDENTITY（Developer ID Application）与" >&2
    echo "      INSTALLER_SIGN_IDENTITY（Developer ID Installer）两个签名身份。" >&2
    exit 1
fi

# --- 收集安装包 ---
artifacts=()
if [[ $# -gt 0 ]]; then
    for a in "$@"; do
        if [[ ! -f "$a" ]]; then
            echo "错误：安装包不存在：$a" >&2
            exit 1
        fi
        artifacts+=("$a")
    done
else
    bin_root="$REPO_ROOT/composeApp/build/compose/binaries/main-release"
    while IFS= read -r -d '' f; do
        artifacts+=("$f")
    done < <(find "$bin_root" -type f \( -name '*.dmg' -o -name '*.pkg' \) -print0 2>/dev/null || true)
fi

if [[ ${#artifacts[@]} -eq 0 ]]; then
    echo "错误：没有找到 .dmg / .pkg 安装包。请先打包，或把路径作为参数传入。" >&2
    exit 1
fi

for artifact in "${artifacts[@]}"; do
    echo "==> 处理 $artifact"
    case "$artifact" in
        *.pkg)
            # productsign 不能原地覆盖：先写到 -signed.pkg，成功后再替换
            signed="${artifact%.pkg}-signed.pkg"
            rm -f "$signed"
            productsign --sign "$INSTALLER_SIGN_IDENTITY" "$artifact" "$signed"
            mv -f "$signed" "$artifact"
            ;;
        *.dmg)
            # DMG 用 codesign 签（productsign 只接受 pkg 产品归档）
            codesign --force --timestamp --sign "$APP_SIGN_IDENTITY" "$artifact"
            ;;
        *)
            echo "错误：不支持的文件类型（只接受 .dmg / .pkg）：$artifact" >&2
            exit 1
            ;;
    esac

    echo "==> 提交公证（notarytool --wait，直到返回结论）"
    xcrun notarytool submit "$artifact" "${NOTARY_AUTH[@]}" --wait

    echo "==> staple 公证票据（离线也能通过 Gatekeeper）"
    xcrun stapler staple "$artifact"

    echo "==> Gatekeeper 校验"
    spctl --assess --type install --verbose=2 "$artifact"

    echo "==> 完成：$artifact"
done
