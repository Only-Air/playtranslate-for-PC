#!/usr/bin/env bash
# 五种打包格式共享的元数据。只改这一个文件，五个格式一起变。
#
# ⚠️ TRADEMARK.md：GPL 覆盖代码，但**不覆盖** "PlayTranslate" 这个名称、图标与视觉识别。
#    本仓库（源码）可以自称是 PlayTranslate 的 fork —— 政策明确允许。
#    但**从这里分发出去的构建产物**必须换名、换图标、换 application id。
#    下面的 APP_* 是占位值，公开发布前必须替换。
#    见 PORTING.md §12.2，以及 §11 待决策第 6 条（正式名称待定）。

set -euo pipefail

# ── 占位元数据（发布前改） ──────────────────────────────────────────
APP_NAME="ScreenGloss"                                   # 占位名
APP_SLUG="screengloss"                                   # 小写、无空格；包名/文件名用
APP_ID="io.github.only_air.screengloss"                  # 反向域名；desktop/metainfo/Flatpak 用

# ── 真实元数据 ────────────────────────────────────────────────────
APP_VERSION="${APP_VERSION:-0.0.0}"
APP_SUMMARY="Real-time screen translation for games, on the desktop"
APP_DESC="Reads the text off a game window, looks up the words, translates them, and paints the result back over the original. A PC port of PlayTranslate, in an unofficial fork."
APP_HOMEPAGE="https://github.com/Only-Air/playtranslate-for-PC"
APP_LICENSE="GPL-3.0-or-later"
APP_MAINTAINER="Only-Air"
APP_MAINTAINER_EMAIL="noreply@github.com"

# ── 安装布局（payload 必须按这个树摆） ──────────────────────────────
APP_BIN_DIR="/usr/bin"
APP_LIB_DIR="/usr/lib/${APP_SLUG}"                       # jar + 原生库放这里
APP_DESKTOP="/usr/share/applications/${APP_ID}.desktop"
APP_ICON_DIR="/usr/share/icons/hicolor"
APP_METAINFO="/usr/share/metainfo/${APP_ID}.metainfo.xml"

# ── 路径 ──────────────────────────────────────────────────────────
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 生成的图标（PNG/ICO/ICNS）放这里。放在 build/ 下是因为它已被 .gitignore 忽略——
# 图标是**构建产物**，不该进版本库（生成只花十几秒）。
ICONS_DIR="${ICONS_DIR:-$REPO_ROOT/build/icons}"
