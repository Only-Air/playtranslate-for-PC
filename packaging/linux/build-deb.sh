#!/usr/bin/env bash
# 构建 .deb。
#
# 用法：build-deb.sh <payload-dir> [output-dir]
#   payload-dir 是「已装好」的文件树（usr/bin/... usr/lib/... 等），
#   由 PC 端构建产出，或用 examples/sample-payload.sh 生成桩载荷做冒烟测试。
#
# 依赖：dpkg-deb（Debian/Ubuntu 自带）。生成 amd64 或 arm64 包，不交叉编译。
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "$here/../config.sh"

PAYLOAD="${1:?用法: build-deb.sh <payload-dir> [output-dir]}"
OUT="${2:-$(pwd)/build/deb}"
ARCH="${ARCH:-amd64}"

# 统一转绝对路径：脚本内部会 cd 到临时目录操作，相对路径会指向错误的位置。
case "$PAYLOAD" in /*) ;; *) PAYLOAD="$PWD/$PAYLOAD" ;; esac
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac

# Debian 版本号不允许 '-'，预发布版本用 '~'（0.2.0-rc1 → 0.2.0~rc1，
# 这样 0.2.0 正式版能正确覆盖 rc，而 rpm 那边用 '-' 表示预发布，两种约定不同）。
DEB_VERSION="${APP_VERSION//-/\~}"
PKG_FILE="${APP_SLUG}_${DEB_VERSION}_${ARCH}.deb"

root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
cp -a "$PAYLOAD"/. "$root"/
mkdir -p "$root/DEBIAN"

# ---- control ----------------------------------------------------------
installed_kb=$(du -sk "$root" | cut -f1)
desc_wrapped=$(printf '%s' "$APP_DESC" | fold -s -w 76 | sed 's/^/ /')
cat > "$root/DEBIAN/control" <<EOF
Package: ${APP_SLUG}
Version: ${DEB_VERSION}
Architecture: ${ARCH}
Maintainer: ${APP_MAINTAINER} <${APP_MAINTAINER_EMAIL}>
Installed-Size: ${installed_kb}
Depends: libc6
Recommends: fonts-noto-cjk
Section: utils
Priority: optional
Homepage: ${APP_HOMEPAGE}
Description: ${APP_SUMMARY}
${desc_wrapped}
 .
 这不是官方 PlayTranslate，而是其非官方 PC 移植分支。
EOF

# ---- 维护脚本 ---------------------------------------------------------
# 桌面数据库与图标缓存必须刷新，否则装完在应用菜单里看不到图标。
cat > "$root/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
if [ "$1" = "configure" ]; then
    command -v update-desktop-database >/dev/null 2>&1 && \
        update-desktop-database -q /usr/share/applications || true
    command -v gtk-update-icon-cache >/dev/null 2>&1 && \
        gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
fi
exit 0
EOF

cat > "$root/DEBIAN/postrm" <<'EOF'
#!/bin/sh
set -e
if [ "$1" = "remove" ] || [ "$1" = "purge" ]; then
    command -v update-desktop-database >/dev/null 2>&1 && \
        update-desktop-database -q /usr/share/applications || true
    command -v gtk-update-icon-cache >/dev/null 2>&1 && \
        gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
fi
exit 0
EOF

chmod 0755 "$root/DEBIAN/postinst" "$root/DEBIAN/postrm"

# ---- md5sums ----------------------------------------------------------
# dpkg-deb **不会**自动生成 md5sums（实测确认），必须自己算，
# 否则 lintian 报 no-md5sums-control-file，且 dpkg --verify 无从校验。
( cd "$root" && find . -path ./DEBIAN -prune -o -type f -print \
    | sed 's|^\./||' | LC_ALL=C sort \
    | xargs -d '\n' md5sum > DEBIAN/md5sums )

# ---- 打包 -------------------------------------------------------------
mkdir -p "$OUT"
# --root-owner-group：文件归 root:root，且不需要 fakeroot。
dpkg-deb --build --root-owner-group "$root" "$OUT/$PKG_FILE" >/dev/null

echo "==> $OUT/$PKG_FILE"
echo "    $(du -h "$OUT/$PKG_FILE" | cut -f1)  sha256=$(sha256sum "$OUT/$PKG_FILE" | cut -c1-16)…"
echo
echo "    检查：dpkg-deb -I  $OUT/$PKG_FILE     # 元数据"
echo "          dpkg-deb -c  $OUT/$PKG_FILE     # 文件清单"
echo "          dpkg-deb -x  $OUT/$PKG_FILE /tmp/x   # 解开看"
echo "          lintian      $OUT/$PKG_FILE     # 规范检查（可选）"
