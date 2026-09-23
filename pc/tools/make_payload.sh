#!/usr/bin/env bash
# Build a *real* payload for the packaging scripts, from the PC build output.
#
# `packaging/examples/sample-payload.sh` builds a stub that prints a version
# string. Its own header says what to do when an application exists:
#
#     > 真正的载荷由 PC 端构建产出（jar + 原生库），摆成同样的树之后，
#     > 把本脚本换成真载荷路径即可，其余脚本一个字不用改。
#
# This is that script. It lays out the exact tree `packaging/README.md`
# documents, and the five format scripts (`build-deb.sh`, `build-rpm.sh`,
# `build-flatpak.sh`, `build-msi.ps1`, `build-dmg.sh`) consume it unchanged —
# which is the property worth proving: the packaging layer was written before
# there was an application and does not have to change now that there is one.
#
# Usage:
#   pc/tools/make_payload.sh <out-dir> [--classes <dir>]
#
# The jar is built fat (kotlin-stdlib + kotlinx-coroutines unpacked into it).
# That is a deliberate choice for a first package: it keeps the launcher's
# `-jar` invocation honest, avoids a Class-Path manifest that has to be
# rewritten per platform, and the two libraries are ~3 MB against a payload that
# will eventually carry hundreds of MB of model packs. `jpackage`, which is the
# recommended path in packaging/README.md, does this differently and better.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
# shellcheck source=/dev/null
. "$repo/packaging/config.sh"

OUT="${1:?usage: make_payload.sh <out-dir> [--classes <dir>]}"
shift || true
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac

CLASSES=""
EXTRA=""
while [ $# -gt 0 ]; do
    case "$1" in
        --classes) CLASSES="$2"; shift 2 ;;
        --cp)      EXTRA="$2"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

# Where the Kotlin compiler put the classes, in the order this repo has them:
# build output first, then the Kotlin distribution, then whatever the caller
# passes for the remaining runtime jars.
if [ -z "$CLASSES" ]; then
    for cand in "$repo/pc/build/classes" "$repo/build/classes" "$repo/pc/build/pc-classes"; do
        if [ -d "$cand" ]; then CLASSES="$cand"; break; fi
    done
fi
if [ -z "$CLASSES" ] || [ ! -d "$CLASSES" ]; then
    echo "no compiled classes found — build pc/ first, or pass --classes <dir>" >&2
    echo "see pc/README.md for the one-line build command" >&2
    exit 1
fi

KOTLIN_STDLIB="${KOTLIN_STDLIB:-}"
if [ -z "$KOTLIN_STDLIB" ]; then
    for cand in "$repo/../kotlinc/lib/kotlin-stdlib.jar" "$repo/kotlinc/lib/kotlin-stdlib.jar" \
                "$HOME/kotlinc/lib/kotlin-stdlib.jar"; do
        [ -f "$cand" ] && KOTLIN_STDLIB="$cand" && break
    done
fi
if [ -z "$KOTLIN_STDLIB" ] || [ ! -f "$KOTLIN_STDLIB" ]; then
    echo "kotlin-stdlib.jar not found — set KOTLIN_STDLIB=<path>" >&2
    exit 1
fi

ASSETS="$repo/packaging/assets"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

render() {
    sed -e "s|@APP_NAME@|${APP_NAME}|g" \
        -e "s|@APP_SLUG@|${APP_SLUG}|g" \
        -e "s|@APP_ID@|${APP_ID}|g" \
        -e "s|@APP_LIB_DIR@|${APP_LIB_DIR}|g" \
        -e "s|@APP_SUMMARY@|${APP_SUMMARY}|g" \
        -e "s|@APP_DESC@|${APP_DESC}|g" \
        -e "s|@APP_HOMEPAGE@|${APP_HOMEPAGE}|g" \
        -e "s|@APP_LICENSE@|${APP_LICENSE}|g" \
        -e "s|@APP_VERSION@|${APP_VERSION}|g" \
        -e "s|@APP_DATE@|$(date +%Y-%m-%d)|g" "$1"
}

echo "==> payload tree -> $OUT"
rm -rf "$OUT"
mkdir -p "$OUT${APP_BIN_DIR}" "$OUT${APP_LIB_DIR}" \
         "$OUT$(dirname "$APP_DESKTOP")" \
         "$OUT$(dirname "$APP_METAINFO")" \
         "$OUT${APP_ICON_DIR}/scalable/apps"

# ── the application jar ───────────────────────────────────────────────────
echo "==> jar (fat: app + kotlin-stdlib + the runtime jars)"
cp -r "$CLASSES/." "$WORK/jar/"
# `unzip -o` over the class tree merges the libraries in. A library jar shipping
# its own META-INF/MANIFEST.MF is the one thing that must not win, so the
# manifest is written last and written explicitly.
for lib in "$KOTLIN_STDLIB" ${EXTRA:-}; do
    [ -f "$lib" ] || continue
    ( cd "$WORK/jar" && unzip -oq "$lib" -x 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.DSA' 'META-INF/MANIFEST.MF' )
done
printf 'Manifest-Version: 1.0\nMain-Class: io.github.only_air.screengloss.shell.Main\nImplementation-Version: %s\n' \
    "$APP_VERSION" > "$WORK/MANIFEST.MF"
( cd "$WORK/jar" && jar --create --file "$OUT${APP_LIB_DIR}/${APP_SLUG}.jar" --manifest "$WORK/MANIFEST.MF" . )

# The panel is data, not code: it lives beside the jar so the static root can be
# a directory, and the shell finds it through -Dapp.home.
mkdir -p "$OUT${APP_LIB_DIR}/ui-web"
cp -r "$repo/pc/ui-web/." "$OUT${APP_LIB_DIR}/ui-web/"

# ── launcher + desktop integration ────────────────────────────────────────
render "$ASSETS/app-launcher.sh.in" > "$OUT${APP_BIN_DIR}/${APP_SLUG}"
chmod 0755 "$OUT${APP_BIN_DIR}/${APP_SLUG}"
render "$ASSETS/app.desktop.in"      > "$OUT${APP_DESKTOP}"
render "$ASSETS/app.metainfo.xml.in" > "$OUT${APP_METAINFO}"
cp "$ASSETS/icon.svg" "$OUT${APP_ICON_DIR}/scalable/apps/${APP_ID}.svg"

if [ -d "$ICONS_DIR" ]; then
    for n in 48 128 256; do
        mkdir -p "$OUT${APP_ICON_DIR}/${n}x${n}/apps"
        cp "$ICONS_DIR/icon_${n}.png" "$OUT${APP_ICON_DIR}/${n}x${n}/apps/${APP_ID}.png"
    done
else
    # Never rename the SVG to .png: a software centre that tries to decode it
    # fails, and packaging/README.md lists that as trap #7.
    echo "    note: no bitmap icons in $ICONS_DIR — run packaging/tools/make_icons.py first"
fi

echo "    $(find "$OUT" -type f | wc -l) files, $(du -sh "$OUT" | cut -f1)"
echo "    jar: $(du -h "$OUT${APP_LIB_DIR}/${APP_SLUG}.jar" | cut -f1)"
