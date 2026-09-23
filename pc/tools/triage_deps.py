#!/usr/bin/env python3
"""Split the extracted "portable" set by what it actually needs on the classpath.

PORTING.md §2.1 counts a file as reusable when it does not touch the Android
framework. That is necessary but not sufficient: the same file may import
OkHttp, Lucene, OpenCV, Sudachi, KOMORAN or gson, which are all *portable* but
are all *dependencies the desktop module then inherits*. Two files can both be
"pure JVM" and differ by whether moving them costs you a 90 MB native library.

This script answers the question that actually decides module boundaries:

    can this file compile against the JDK and the two shims, nothing else?

Tiers:
    jdk       — JDK + kotlin stdlib only. Free to move.
    shim      — JDK + androidx annotation shims / android.util.Log shim. Also free.
    thirdpty  — needs an external JVM library (still portable, must be declared)
    unmovable — needs something with no desktop equivalent at all

Usage:
    python3 pc/tools/triage_deps.py --root pc/core/src/main/kotlin/.../upstream
"""

from __future__ import annotations

import argparse
import re
from collections import Counter, defaultdict
from pathlib import Path

IMPORT_RE = re.compile(r"^\s*import\s+([A-Za-z_][\w.]*)", re.MULTILINE)

# Note the absence of `kotlinx.coroutines.` here. The first version of this
# file listed it as a JDK namespace and that was wrong: kotlinx-coroutines is
# a separate artifact, and a file that uses `StateFlow` needs it on the
# classpath. It is the single most common dependency in the portable set.
JDK_PREFIXES = ("java.", "javax.", "kotlin.")

SHIM_PREFIXES = ("android.util.", "androidx.annotation.", "android.")  # android.* handled below

# Everything here is a real library with a real desktop build. The point of
# listing it rather than pattern-matching is that the *reason* differs per
# entry, and the reason is what a module boundary has to be argued from.
LIBRARIES: dict[str, tuple[str, str]] = {
    "okhttp3": ("okhttp", "same library on desktop"),
    "kotlinx.coroutines": ("kotlinx-coroutines-core", "desktop-clean; the portable set assumes it heavily"),
    "com.google.gson": ("gson", "same library on desktop"),
    "com.google.common": ("guava", "same library on desktop"),
    "org.apache.lucene": ("lucene-analyzers-common", "used by the Snowball stemmers; desktop-clean"),
    "org.opencv": ("opencv", "the Android OpenCV AAR; desktop needs the native OpenCV build"),
    "com.worksap.nlp": ("sudachi", "Sudachi JVM; desktop-clean, ships its own dictionary"),
    "com.github.shin285": ("komoran", "KOMORAN via JitPack; desktop-clean"),
    "org.sqlite": ("sqlite-jdbc", "desktop-clean"),
    "com.github.tony19": ("logback", "test-only"),
    "org.json": ("json", "desktop-clean"),
}

# Namespaces that mean the file cannot move at all, whatever else it imports.
HARD_ANDROID = (
    "android.graphics",
    "android.content",
    "android.view",
    "android.app",
    "android.widget",
    "android.os",
    "android.net",
    "android.media",
    "android.hardware",
    "android.provider",
    "android.database",
    "android.accessibilityservice",
    "android.service",
    "androidx.",
    "com.google.mlkit",
)


def classify_imports(text: str) -> tuple[str, list[str]]:
    imports = set(IMPORT_RE.findall(text))

    hard = sorted(i for i in imports if i.startswith(HARD_ANDROID) and not i.startswith("androidx.annotation"))
    if hard:
        return "unmovable", hard

    libs = sorted({i for i in imports if i.split(".")[0] in LIBRARIES or
                   any(i.startswith(p + ".") or i == p for p in LIBRARIES)})
    # Match against the LIBRARIES keys properly: longest prefix wins.
    libs = []
    for i in sorted(imports):
        for prefix, (name, _) in LIBRARIES.items():
            if i == prefix or i.startswith(prefix + "."):
                libs.append(name)
                break
    if libs:
        return "thirdparty", sorted(set(libs))

    shim = sorted(i for i in imports if i.startswith(SHIM_PREFIXES) or i == "android.util.Log")
    if shim:
        return "shim", shim

    return "jdk", []


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    ap.add_argument("--out", help="write the report here")
    args = ap.parse_args()

    root = Path(args.root)
    files = sorted(root.rglob("*.kt"))
    tiers: dict[str, list[Path]] = defaultdict(list)
    why: dict[Path, list[str]] = {}
    for f in files:
        tier, reasons = classify_imports(f.read_text(encoding="utf-8", errors="replace"))
        tiers[tier].append(f)
        why[f] = reasons

    lines_total = {f: len(f.read_text(encoding='utf-8', errors='replace').splitlines()) for f in files}

    out: list[str] = []
    out.append("### What the extracted set actually needs")
    out.append("")
    out.append("| tier | files | lines | meaning |")
    out.append("|---|---:|---:|---|")
    for t, meaning in (
        ("jdk", "JDK + kotlin stdlib only"),
        ("shim", "JDK + `android.util.Log` / annotation shims"),
        ("thirdparty", "JDK + an external JVM library that also exists on desktop"),
        ("unmovable", "reaches the Android framework — the static scan over-admitted it"),
    ):
        out.append(f"| `{t}` | {len(tiers[t])} | {sum(lines_total[f] for f in tiers[t])} | {meaning} |")
    out.append("")

    if tiers["unmovable"]:
        out.append(f"{len(tiers['unmovable'])} files were classified portable by the static scan and are not. "
                   "The scan only looks at imports that name a platform namespace; a file that reaches the "
                   "framework through a *helper it imports* is admitted. That is the scan's known false-positive "
                   "mode and the reason the set is compiled rather than trusted:")
        out.append("")
        out.append("| file | reached via |")
        out.append("|---|---|")
        for f in sorted(tiers["unmovable"], key=lambda p: -lines_total[p])[:40]:
            out.append(f"| `{f.name}` | {', '.join(why[f][:3])} |")
        out.append("")

    if tiers["thirdparty"]:
        counter: Counter[str] = Counter()
        for f in tiers["thirdparty"]:
            for lib in why[f]:
                counter[lib] += 1
        out.append("Libraries the extract pulls onto the desktop classpath, by how many files need each:")
        out.append("")
        out.append("| library | files | note |")
        out.append("|---|---:|---|")
        for lib, n in counter.most_common():
            note = next(v[1] for v in LIBRARIES.values() if v[0] == lib)
            out.append(f"| `{lib}` | {n} | {note} |")
        out.append("")

    text = "\n".join(out)
    if args.out:
        Path(args.out).write_text(text + "\n", encoding="utf-8")
        print(f"wrote {args.out}")
    print(f"{len(files)} files: " + ", ".join(f"{t}={len(tiers[t])}" for t in ("jdk", "shim", "thirdparty", "unmovable")))
    # Emit the jdk+shim list so the compile step can use it.
    free = sorted(tiers["jdk"] + tiers["shim"])
    Path("pc/build/compile_free.txt").write_text("\n".join(str(p) for p in free) + "\n", encoding="utf-8")
    print(f"jdk+shim list -> pc/build/compile_free.txt ({len(free)} files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
