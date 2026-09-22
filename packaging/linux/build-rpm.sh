#!/usr/bin/env bash
# 构建 .rpm。需要 rpmbuild（rpm-build 包）。
#
# 用法：build-rpm.sh <payload-dir> [output-dir]
#
# ⚠️ 本机沙箱没有 rpmbuild，此脚本未经实际执行验证——它在 CI 的 Fedora/Ubuntu
#    job 上跑（见 .github/workflows/release.yml）。首次启用时请人肉确认一遍。
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "$here/../config.sh"

PAYLOAD="${1:?用法: build-rpm.sh <payload-dir> [output-dir]}"
OUT="${2:-$(pwd)/build/rpm}"
ARCH="${ARCH:-x86_64}"

# 统一转绝对路径：rpmbuild 会在自己的 _topdir 里工作，相对路径会失效。
case "$PAYLOAD" in /*) ;; *) PAYLOAD="$PWD/$PAYLOAD" ;; esac
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac

# rpm 与 deb 一样用 '~' 表示预发布（0.2.0~rc1 < 0.2.0）；'-' 在两者中都不合法。
RPM_VERSION="${APP_VERSION//-/\~}"

command -v rpmbuild >/dev/null 2>&1 || {
    echo "缺少 rpmbuild。Debian/Ubuntu: apt install rpm；Fedora: dnf install rpm-build" >&2
    exit 1
}

topdir="$(mktemp -d)"
trap 'rm -rf "$topdir"' EXIT
mkdir -p "$topdir"/{BUILD,RPMS,SOURCES,SPECS,SRPMS}

sed -e "s|@APP_SLUG@|${APP_SLUG}|g" \
    -e "s|@APP_ID@|${APP_ID}|g" \
    -e "s|@APP_NAME@|${APP_NAME}|g" \
    -e "s|@APP_VERSION@|${RPM_VERSION}|g" \
    -e "s|@APP_SUMMARY@|${APP_SUMMARY}|g" \
    -e "s|@APP_DESC@|${APP_DESC}|g" \
    -e "s|@APP_HOMEPAGE@|${APP_HOMEPAGE}|g" \
    -e "s|@APP_MAINTAINER@|${APP_MAINTAINER}|g" \
    -e "s|@APP_MAINTAINER_EMAIL@|${APP_MAINTAINER_EMAIL}|g" \
    -e "s|@APP_DATE@|$(date +%Y-%m-%d)|g" \
    -e "s|@ARCH@|${ARCH}|g" \
    "$here/rpm/app.spec.in" > "$topdir/SPECS/${APP_SLUG}.spec"

# _payload 把「已装好的文件树」位置告诉 spec 的 %install。
rpmbuild -bb "$topdir/SPECS/${APP_SLUG}.spec" \
    --define "_topdir $topdir" \
    --define "_payload $PAYLOAD" \
    --quiet

mkdir -p "$OUT"
find "$topdir/RPMS" -name '*.rpm' -exec cp {} "$OUT"/ \;

echo "==> $OUT/"
for f in "$OUT"/*.rpm; do
    echo "    $(basename "$f")  $(du -h "$f" | cut -f1)  sha256=$(sha256sum "$f" | cut -c1-16)…"
done
echo
echo "    检查：rpm -qip <包>        # 元数据"
echo "          rpm -qlp <包>        # 文件清单"
echo "          rpm -K  <包>         # 校验签名/摘要"
echo "          rpmlint <包>         # 规范检查（可选）"
