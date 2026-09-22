#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
为一次发行生成清单 latest.json + SHA256SUMS。

为什么要有清单而不是只堆一堆附件：
  应用的自更新检查（对应上游 Android 的 UpdateChecker）需要知道
  「最新版是哪个、我这平台该下哪个文件、下完怎么校验」。
  逐个去问 GitHub Releases API 也能做，但格式一变就全崩。
  固定一份 latest.json，把版本/文件名/大小/sha256 写在里面，
  自更新逻辑就只需要解析一个稳定的 JSON —— 这正是上游
  langpack_catalog.json 用的模式，这里沿用。

用法：
    make_manifest.py <版本号> <资产目录> [-o latest.json]

资产目录里放五种格式（缺哪个就少哪项，不报错）：
    *.msi  *.dmg  *.deb  *.rpm  *.flatpak
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from datetime import date

# 后缀 → 清单里的键名。顺序即输出顺序。
KINDS = [
    ("windows-msi", "msi"),
    ("macos-dmg", "dmg"),
    ("linux-deb", "deb"),
    ("linux-rpm", "rpm"),
    ("linux-flatpak", "flatpak"),
]

KIND_LABELS = {
    "windows-msi": "Windows（MSI 安装包）",
    "macos-dmg": "macOS（DMG 磁盘映像）",
    "linux-deb": "Linux · Debian/Ubuntu（deb）",
    "linux-rpm": "Linux · Fedora/openSUSE（rpm）",
    "linux-flatpak": "Linux · 通用（Flatpak 单文件）",
}


def sha256_of(path: str, chunk: int = 1 << 20) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def human(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024.0
    return f"{n:.1f} GB"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("version", help="版本号，如 0.2.0")
    ap.add_argument("assets", help="放置五种格式的目录")
    ap.add_argument("-o", "--output", default="latest.json")
    ap.add_argument("--notes-url", default="",
                    help="发行说明地址（默认指向 tag 的 Releases 页面）")
    args = ap.parse_args()

    if not os.path.isdir(args.assets):
        print(f"没有这个目录：{args.assets}", file=sys.stderr)
        return 1

    found = {}
    for name in sorted(os.listdir(args.assets)):
        p = os.path.join(args.assets, name)
        if not os.path.isfile(p):
            continue
        ext = name.rsplit(".", 1)[-1].lower()
        for kind, want in KINDS:
            if ext == want and kind not in found:
                found[kind] = p

    if not found:
        print(f"{args.assets} 里没有任何 .msi/.dmg/.deb/.rpm/.flatpak", file=sys.stderr)
        return 1

    assets = {}
    lines = []
    print(f"==> 版本 {args.version}")
    for kind, _ in KINDS:
        if kind not in found:
            print(f"    {kind:16} —（本次未产出）")
            continue
        p = found[kind]
        size = os.path.getsize(p)
        sha = sha256_of(p)
        assets[kind] = {
            "file": os.path.basename(p),
            "size": size,
            "sha256": sha,
        }
        lines.append(f"{sha}  {os.path.basename(p)}")
        print(f"    {kind:16} {os.path.basename(p):44} {human(size):>9}  {sha[:16]}…")

    manifest = {
        "version": args.version,
        "released": date.today().isoformat(),
        "notes_url": args.notes_url or
            "https://github.com/Only-Air/playtranslate-for-PC/releases/tag/v" + args.version,
        "assets": assets,
    }

    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2, sort_keys=False)
        f.write("\n")

    sums = os.path.join(os.path.dirname(args.output) or ".", "SHA256SUMS")
    with open(sums, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    print()
    print(f"==> {args.output}")
    print(f"==> {sums}")
    print()
    print("    两者都要作为发行附件上传。用户校验方式：")
    print(f"      sha256sum -c SHA256SUMS        （Linux）")
    print(f"      shasum -a 256 -c SHA256SUMS    （macOS）")
    print(f"      Get-FileHash -Algorithm SHA256 <文件>   （Windows）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
