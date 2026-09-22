#!/usr/bin/env bash
# 生成一个**最小测试载荷**，用来验证打包链路本身是通的。
#
# ⚠️ 这不是应用。它编译一个打印版本号的桩程序，只为让 build-deb/build-rpm/
#    build-flatpak/build-msi/build-dmg 有东西可打，从而在 CI 里做冒烟测试。
#    真正的载荷由 PC 端构建产出（jar + 原生库），摆成同样的树之后，
#    把本脚本换成真载荷路径即可，其余脚本一个字不用改。
#
# 用法：sample-payload.sh <输出目录>
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "$here/../config.sh"

OUT="${1:?用法: sample-payload.sh <输出目录>}"
# 必须转成绝对路径：下面有 `cd "$SRC/classes"` 再调 jar 的步骤，
# 若 OUT 是相对路径，jar 会把文件写进临时目录，随后被 trap 删掉。
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac
ASSETS="$here/../assets"
SRC="$(mktemp -d)"
trap 'rm -rf "$SRC"' EXIT

render() { sed -e "s|@APP_NAME@|${APP_NAME}|g" \
               -e "s|@APP_SLUG@|${APP_SLUG}|g" \
               -e "s|@APP_ID@|${APP_ID}|g" \
               -e "s|@APP_LIB_DIR@|${APP_LIB_DIR}|g" \
               -e "s|@APP_SUMMARY@|${APP_SUMMARY}|g" \
               -e "s|@APP_DESC@|${APP_DESC}|g" \
               -e "s|@APP_HOMEPAGE@|${APP_HOMEPAGE}|g" \
               -e "s|@APP_LICENSE@|${APP_LICENSE}|g" \
               -e "s|@APP_VERSION@|${APP_VERSION}|g" \
               -e "s|@APP_DATE@|$(date +%Y-%m-%d)|g" "$1"; }

echo "==> 组装载荷树 → $OUT"
rm -rf "$OUT"
mkdir -p "$OUT${APP_BIN_DIR}" "$OUT${APP_LIB_DIR}" \
         "$OUT$(dirname "$APP_DESKTOP")" \
         "$OUT$(dirname "$APP_METAINFO")" \
         "$OUT${APP_ICON_DIR}/scalable/apps"

# 1) 桩 jar（打印版本号）
mkdir -p "$SRC/stub"
cat > "$SRC/stub/Main.java" <<'EOF'
public final class Main {
    public static void main(String[] args) {
        System.out.println("placeholder payload - not the application");
        System.out.println("version " + Main.class.getPackage().getImplementationVersion());
    }
}
EOF
if command -v javac >/dev/null 2>&1; then
    javac -d "$SRC/classes" "$SRC/stub/Main.java"
    printf 'Manifest-Version: 1.0\nMain-Class: Main\nImplementation-Version: %s\n' \
        "$APP_VERSION" > "$SRC/MANIFEST.MF"
    ( cd "$SRC/classes" && jar --create --file "$OUT${APP_LIB_DIR}/${APP_SLUG}.jar" \
        --manifest "$SRC/MANIFEST.MF" . )
    echo "    桩 jar: $(du -h "$OUT${APP_LIB_DIR}/${APP_SLUG}.jar" | cut -f1)"
else
    echo "    !! 无 javac，写占位文件代替 jar"
    printf 'placeholder payload\n' > "$OUT${APP_LIB_DIR}/${APP_SLUG}.jar"
fi

# 2) 启动器
render "$ASSETS/app-launcher.sh.in" > "$OUT${APP_BIN_DIR}/${APP_SLUG}"
chmod 0755 "$OUT${APP_BIN_DIR}/${APP_SLUG}"

# 3) 桌面集成
render "$ASSETS/app.desktop.in"            > "$OUT${APP_DESKTOP}"
render "$ASSETS/app.metainfo.xml.in"       > "$OUT${APP_METAINFO}"
cp "$ASSETS/icon.svg" "$OUT${APP_ICON_DIR}/scalable/apps/${APP_ID}.svg"

# 位图图标。hicolor 要 48/128/256 的 PNG，MSI 还要 .ico、macOS 要 .icns——
# 那些由 tools/make_icons.py 一次生成。拿不到就只装矢量，并明确提示，
# **绝不**把 SVG 改名成 .png 充数（那是假文件，软件中心解码会失败）。
if [ -d "$ICONS_DIR" ]; then
    for n in 48 128 256; do
        mkdir -p "$OUT${APP_ICON_DIR}/${n}x${n}/apps"
        cp "$ICONS_DIR/icon_${n}.png" "$OUT${APP_ICON_DIR}/${n}x${n}/apps/${APP_ID}.png"
    done
else
    echo "    提示：未找到位图图标（$ICONS_DIR）"
    echo "          先跑：python3 packaging/tools/make_icons.py"
fi

echo "    $(find "$OUT" -type f | wc -l) 个文件，$(du -sh "$OUT" | cut -f1)"
