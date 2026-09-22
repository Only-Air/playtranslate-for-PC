#!/usr/bin/env bash
# 构建 Flatpak 单文件包（.flatpak）。
#
# 用法：build-flatpak.sh <payload-dir> [output-dir]
# 环境变量：INSTALL_DEPS=1 时先装 runtime/sdk（CI 里用）
#
# ⚠️ 本机沙箱没有 flatpak-builder，此脚本未经实际执行验证——它在 CI 的
#    Linux job 上跑。首次启用时请人肉确认一遍。
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "$here/../config.sh"

PAYLOAD="${1:?用法: build-flatpak.sh <payload-dir> [output-dir]}"
OUT="${2:-$(pwd)/build/flatpak}"
PAYLOAD="$(cd "$PAYLOAD" && pwd)"
RUNTIME_VERSION="$(sed -n "s/^runtime-version: *'\{0,1\}\([^']*\)'\{0,1\} *$/\1/p" "$here/app.yml.in" | head -1)"

for t in flatpak flatpak-builder; do
    command -v "$t" >/dev/null 2>&1 || {
        echo "缺少 $t。Debian/Ubuntu: apt install flatpak flatpak-builder" >&2
        echo "Fedora: dnf install flatpak flatpak-builder" >&2
        exit 1
    }
done

if [ "${INSTALL_DEPS:-0}" = "1" ]; then
    echo "==> 准备 Flathub 远端与 runtime/sdk"
    flatpak remote-add --if-not-exists --user flathub \
        https://flathub.org/repo/flathub.flatpakrepo
    flatpak install --user --noninteractive -y flathub \
        "org.freedesktop.Platform//${RUNTIME_VERSION}" \
        "org.freedesktop.Sdk//${RUNTIME_VERSION}"
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# flatpak-builder 的 type: dir 源用相对路径最稳；算出清单目录到载荷的相对路径。
rel="$(realpath --relative-to="$work" "$PAYLOAD")"

sed -e "s|@APP_ID@|${APP_ID}|g" \
    -e "s|@APP_SLUG@|${APP_SLUG}|g" \
    -e "s|@PAYLOAD_REL@|${rel}|g" \
    "$here/app.yml.in" > "$work/${APP_ID}.yml"

echo "==> flatpak-builder（runtime ${RUNTIME_VERSION}）"
# --disable-rofiles-fuse：容器里通常没有 fuse，不禁用会在挂载时失败。
flatpak-builder \
    --repo="$work/repo" \
    --force-clean \
    --disable-rofiles-fuse \
    --install-deps-from=flathub \
    "$work/build" "$work/${APP_ID}.yml"

mkdir -p "$OUT"
BUNDLE="$OUT/${APP_SLUG}-${APP_VERSION}.flatpak"
echo "==> flatpak build-bundle"
flatpak build-bundle \
    "$work/repo" "$BUNDLE" "$APP_ID" \
    --runtime-repo=https://flathub.org/repo/flathub.flatpakrepo

echo "==> $BUNDLE"
echo "    $(du -h "$BUNDLE" | cut -f1)  sha256=$(sha256sum "$BUNDLE" | cut -c1-16)…"
echo
echo "    本地试用：flatpak install --user $BUNDLE"
echo "              flatpak run ${APP_ID}"
echo "    发布：先建 Flatpak 仓库（flatpak build-export），再推到自己的 ostree 仓库；"
echo "          或直接把这个 .flatpak 单文件当发行物附件（用户一条命令安装）。"
