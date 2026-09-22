#!/usr/bin/env bash
# 构建 macOS DMG：补 plist / 图标 → 签名 → 压盘 → （可选）公证。
#
# 用法：build-dmg.sh <App.app> [output-dir]
#
# 入参是一个 **.app bundle**——macOS 侧推荐先用 jpackage 产出 app-image：
#     jpackage --type app-image --name <slug> --input <jars> \
#              --main-jar <slug>.jar --main-class <Main> \
#              --icon build/icons/app.icns
# 本脚本再把它包成 DMG，并补上 jpackage 不会生成的东西
# （使用说明字符串、图标、entitlements）。
#
# 环境变量：
#   SIGN_IDENTITY  "Developer ID Application: Name (TEAMID)"  留空则跳过签名
#   NOTARY_PROFILE notarytool 里存的 profile 名（xcrun notarytool store-credentials）
#
# ⚠️ 本机沙箱没有 macOS 与 hdiutil，此脚本未经实际执行验证——
#    它在 CI 的 macos job 上跑。首次启用时请人肉确认一遍。
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "$here/../config.sh"

APP="${1:?用法: build-dmg.sh <App.app> [output-dir]}"
OUT="${2:-$(pwd)/build/macos}"
APP="$(cd "$(dirname "$APP")" && pwd)/$(basename "$APP")"

[ -d "$APP" ] || { echo "找不到 .app：$APP" >&2; exit 1; }
command -v hdiutil >/dev/null 2>&1 || { echo "需要 macOS（hdiutil）。" >&2; exit 1; }

PLIST="$APP/Contents/Info.plist"
RES="$APP/Contents/Resources"
MACOS_DIR="$APP/Contents/MacOS"

# ── 1) 补 Info.plist ────────────────────────────────────────────────
# jpackage 不提供「加 usage description」的选项，所以必须事后改 plist。
echo "==> 补 Info.plist"
render() {
    sed -e "s|@APP_NAME@|${APP_NAME}|g" \
        -e "s|@APP_SLUG@|${APP_SLUG}|g" \
        -e "s|@APP_ID@|${APP_ID}|g" \
        -e "s|@APP_VERSION@|${APP_VERSION}|g" \
        -e "s|@APP_BUILD@|${APP_BUILD:-1}|g" "$1"
}
BIN_NAME="$(render "$here/Info.plist.in" | /usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' /dev/stdin 2>/dev/null || true)"
if [ -f "$PLIST" ] && [ -n "$BIN_NAME" ] && [ ! -e "$MACOS_DIR/$BIN_NAME" ] && [ -e "$MACOS_DIR/$APP_SLUG" ]; then
    echo "    plist 里的可执行名 ($BIN_NAME) 与 bundle 里的 ($APP_SLUG) 不一致——"
    echo "    以实际存在的为准，改 plist 而不是改名。"
    render "$here/Info.plist.in" | sed "s|>${BIN_NAME}<|>${APP_SLUG}<|" > "$PLIST"
else
    render "$here/Info.plist.in" > "$PLIST"
fi
plutil -lint "$PLIST" >/dev/null && echo "    plutil -lint 通过"

# ── 2) 图标 ─────────────────────────────────────────────────────────
ICNS="$ICONS_DIR/app.icns"
if [ -f "$ICNS" ]; then
    echo "==> 装图标 app.icns"
    cp "$ICNS" "$RES/AppIcon.icns"
else
    echo "!! 没有 $ICNS —— 先跑 python3 packaging/tools/make_icons.py"
fi

# ── 3) 签名 ─────────────────────────────────────────────────────────
if [ -n "${SIGN_IDENTITY:-}" ]; then
    echo "==> codesign（$SIGN_IDENTITY）"
    # --deep 便于一次性签内嵌的 runtime 与 JNI 库；更严格的做法是按需逐层签。
    codesign --force --deep --options runtime --timestamp \
        --entitlements "$here/entitlements.plist" \
        --sign "$SIGN_IDENTITY" "$APP"
    codesign --verify --verbose=2 "$APP"
    echo "    已签名。注意：换签名标识会让用户**重新授权**屏幕录制/辅助功能（TCC 绑定签名）。"
else
    echo "!! 跳过签名（SIGN_IDENTITY 未设）。未签名的包在别人机器上会被 Gatekeeper 拦下："
    echo "   用户需要右键→打开，或 xattr -d com.apple.quarantine <App>.app"
fi

# ── 4) 压盘 ─────────────────────────────────────────────────────────
echo "==> hdiutil 打包"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
cp -a "$APP" "$stage/"
ln -s /Applications "$stage/Applications"     # 拖进去就能装的常见形态

mkdir -p "$OUT"
DMG="$OUT/${APP_SLUG}-${APP_VERSION}.dmg"
rm -f "$DMG"
hdiutil create \
    -volname "$APP_NAME" \
    -srcfolder "$stage" \
    -fs HFS+ \
    -format UDZO \
    -ov "$DMG" >/dev/null

# ── 5) 公证（可选） ─────────────────────────────────────────────────
if [ -n "${NOTARY_PROFILE:-}" ] && [ -n "${SIGN_IDENTITY:-}" ]; then
    echo "==> 公证（notarytool）"
    xcrun notarytool submit "$DMG" --keychain-profile "$NOTARY_PROFILE" --wait
    xcrun stapler staple "$DMG"
    xcrun stapler validate "$DMG"
else
    echo "!! 跳过公证（NOTARY_PROFILE 未设）。不公证的 DMG 在别人机器上会显示"
    echo "   「无法验证开发者」；用户需右键→打开。"
fi

echo
echo "==> $DMG"
echo "    $(du -h "$DMG" | cut -f1)  sha256=$(shasum -a 256 "$DMG" | cut -c1-16)…"
echo
echo "    提醒：Apple Developer Program 年费 99 美元是公证的门槛；"
echo "    不做公证就直发也可以，但安装说明里必须写清右键打开的步骤。"
