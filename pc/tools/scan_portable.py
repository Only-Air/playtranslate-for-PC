#!/usr/bin/env python3
"""Classify every file in the Android source tree by how portable it is.

PORTING.md §2.1 states a quantitative baseline:

    491 Kotlin files
      125  (25.5%)  pure Kotlin/JVM, no Android API at all
       20  ( 4.1%)  only android.util.Log / annotations
      346  (70.4%)  genuinely Android

That table is quoted in two documents and underlies the "core logic is more
portable than it looks" claim. Until now nothing in the repo could reproduce
it: a reader had to trust it. This script reproduces it, and more importantly
*names* the files, so the extraction below is mechanical rather than a matter
of someone hand-picking a list.

Usage:
    python3 pc/tools/scan_portable.py --repo . --report pc/PORTABILITY.md
    python3 pc/tools/scan_portable.py --repo . --extract pc/core/src/main/kotlin

The classifier is deliberately naive and deliberately strict. It does not try
to understand Kotlin; it looks at what the file *imports* and what fully
qualified names it *mentions*. That is enough to answer the only question that
matters here — "does this compile against a plain JVM classpath?" — and it
never produces a false "portable" from clever inference, only false
negatives, which fail loudly at compile time instead of silently at runtime.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
from collections import Counter, defaultdict
from pathlib import Path

# ── The Android surface ────────────────────────────────────────────────────
#
# Every package we treat as "the platform". Kept as an explicit list rather
# than a regex on `^android` so that androidx and the Google Play services are
# caught by the same rule as the framework itself: from a porting standpoint
# they are the same problem, and a file importing `androidx.annotation.StringRes`
# is a different case from one importing `androidx.recyclerview`.
ANDROID_PREFIXES = (
    "android.",
    "androidx.",
    "com.google.android.",
    "com.google.mlkit.",
    "com.google.firebase.",
    "dalvik.",
    "kotlinx.coroutines.android.",
)

# Imports that are Android namespaces but not platform dependencies. A
# `StringRes` annotation is a compile-time marker with an empty body; a
# `@Px` is the same. shim/ lists the shims that make these compile off-device.
SHIMMABLE_PREFIXES = (
    "androidx.annotation.",
)

# The one framework class with no behavioural content.
LOG_ONLY = {"android.util.Log"}

IMPORT_RE = re.compile(r"^\s*import\s+([A-Za-z_][\w.]*)", re.MULTILINE)
# `android.graphics.Rect` used without an import, e.g. inside a KDoc-free
# expression or a fully-qualified annotation.
FQN_RE = re.compile(r"\b(android|androidx)\.[A-Za-z_][\w.]*")


def classify(text: str) -> tuple[str, list[str]]:
    """Return (bucket, evidence). Buckets: portable | shimmable | log_only | android."""
    imports = set(IMPORT_RE.findall(text))
    fqns = set(FQN_RE.findall(text))

    android_imports = {i for i in imports if i.startswith(ANDROID_PREFIXES)}
    shimmy = {i for i in android_imports if i.startswith(SHIMMABLE_PREFIXES)}
    hard = android_imports - shimmy

    # An unimported, fully-qualified `android.`/`androidx.` mention means the
    # file reaches the platform even without an import statement (KDoc links
    # don't count — they're stripped first).
    #
    # The import lines themselves have to be removed before this scan, or every
    # `import android.util.Log` is counted as "reaches the platform" a second
    # time and the log-only bucket comes out empty. (It did, on the first run.
    # That is the whole reason this comment is here.)
    stripped = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    stripped = re.sub(r"//[^\n]*", "", stripped)
    stripped = IMPORT_RE.sub("", stripped)
    fq_hits = {m.group(0) for m in FQN_RE.finditer(stripped)}

    if not hard and not fq_hits:
        if shimmy:
            return "shimmable", sorted(shimmy)
        return "portable", []
    if hard <= LOG_ONLY and not fq_hits:
        return "log_only", sorted(hard)
    return "android", sorted(hard | fq_hits)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=".", help="repository root")
    ap.add_argument("--src", default="app/src/main/java", help="Android source root, relative to --repo")
    ap.add_argument("--report", help="write a markdown report here")
    ap.add_argument("--extract", help="copy the portable + shimmable files into this Kotlin source root")
    ap.add_argument("--reroot", action="store_true",
                    help="rewrite `package com.playtranslate` to the desktop package. Off by default: "
                         "keeping the upstream package name makes re-syncing from upstream a diff rather "
                         "than a merge, and the two modules never share a classpath.")
    ap.add_argument("--json", help="write the raw classification here")
    args = ap.parse_args()

    repo = Path(args.repo).resolve()
    src = repo / args.src
    if not src.is_dir():
        print(f"no such source root: {src}", file=sys.stderr)
        return 2

    files = sorted(src.rglob("*.kt"))
    buckets: dict[str, list[Path]] = defaultdict(list)
    evidence: dict[str, list[str]] = {}
    lines: dict[Path, int] = {}

    for f in files:
        text = f.read_text(encoding="utf-8", errors="replace")
        bucket, why = classify(text)
        buckets[bucket].append(f)
        evidence[str(f.relative_to(repo))] = why
        lines[f] = text.count("\n") + 1

    total = len(files)
    n = lambda b: len(buckets[b])  # noqa: E731
    pct = lambda b: (100.0 * n(b) / total) if total else 0.0  # noqa: E731

    # Test tree, classified the same way. PORTING §2.1 splits 286 test files
    # into 150 JVM / 135 Robolectric; that split is a property of the same
    # imports, so it is computed rather than restated.
    test_roots = [repo / "app/src/test/java", repo / "app/src/androidTest/java"]
    test_files = [f for r in test_roots if r.is_dir() for f in sorted(r.rglob("*.kt"))]
    test_buckets: dict[str, list[Path]] = defaultdict(list)
    for f in test_files:
        bucket, _ = classify(f.read_text(encoding="utf-8", errors="replace"))
        test_buckets[bucket].append(f)
    robolectric = sum(
        1 for f in test_files
        if "robolectric" in f.read_text(encoding="utf-8", errors="replace").lower()
    )

    by_pkg: dict[str, Counter] = defaultdict(Counter)
    for b in ("portable", "shimmable", "log_only", "android"):
        for f in buckets[b]:
            rel = f.relative_to(src)
            pkg = rel.parent.as_posix() if rel.parent.as_posix() != "." else "(root)"
            by_pkg[pkg][b] += 1

    def report_lines() -> list[str]:
        L: list[str] = []
        L.append("# Portability of the Android source, measured")
        L.append("")
        L.append("Generated by `pc/tools/scan_portable.py`. Reproduce with:")
        L.append("")
        L.append("```bash")
        L.append("python3 pc/tools/scan_portable.py --repo . --report pc/PORTABILITY.md")
        L.append("```")
        L.append("")
        L.append("PORTING.md §2.1 asserts the split below. This file is that assertion,")
        L.append("recomputed from the tree it describes, so it can also be shown to be wrong.")
        L.append("")
        L.append("## Total")
        L.append("")
        L.append("| bucket | files | share | PORTING §2.1 | agrees |")
        L.append("|---|---:|---:|---:|---|")
        for b, claim in (("portable", 125), ("log_only", 20), ("android", 346)):
            ok = "yes" if n(b) == claim else f"**no — measured {n(b)}**"
            L.append(f"| `{b}` | {n(b)} | {pct(b):.1f}% | {claim} | {ok} |")
        L.append(f"| `shimmable` | {n('shimmable')} | {pct('shimmable'):.1f}% | *(folded into `log_only` by §2.1)* | — |")
        L.append(f"| **total** | **{total}** | 100% | 491 | "
                 f"{'yes' if total == 491 else f'**no — measured {total}**'} |")
        L.append("")
        L.append("`shimmable` is the correction: PORTING §2.1 folds \"only `android.util.Log`")
        L.append("or annotations\" into one 20-file bucket, but those are two different porting")
        L.append(f"jobs. {n('log_only')} files need a `Log` shim; {n('shimmable')} need an")
        L.append("annotation shim. Neither needs an implementation replaced.")
        L.append("")
        L.append("## By package")
        L.append("")
        L.append("| package | portable | shimmable | log-only | android | total |")
        L.append("|---|---:|---:|---:|---:|---:|")
        for pkg in sorted(by_pkg, key=lambda p: -(sum(by_pkg[p].values()))):
            c = by_pkg[pkg]
            L.append(f"| `{pkg}` | {c['portable']} | {c['shimmable']} | {c['log_only']} | "
                     f"{c['android']} | {sum(c.values())} |")
        L.append("")
        L.append("## Test assets")
        L.append("")
        L.append(f"- test files: **{len(test_files)}** (PORTING §2.1: 286)")
        L.append(f"- of which the classifier calls portable: **{n('portable') if False else len(test_buckets['portable'])}** "
                 f"(PORTING: 150 pure JVM)")
        L.append(f"- test files mentioning Robolectric: **{robolectric}** (PORTING: 135)")
        L.append("")
        L.append("The portable test count is the load-bearing number: those are the tests that")
        L.append("move with `core` into the desktop module and keep guarding it. The")
        L.append("Robolectric ones stay behind as Android-side regression and must be")
        L.append("re-authored against the desktop seams, which is real work §2.1 does not cost.")
        L.append("")
        L.append("## The portable files")
        L.append("")
        L.append("These are the files `--extract` copies into `core`. Sorted by size, since")
        L.append("size is a fair proxy for how much logic (as opposed to boilerplate) is at stake.")
        L.append("")
        L.append("| lines | file |")
        L.append("|---:|---|")
        for f in sorted(buckets["portable"], key=lambda p: -lines[p]):
            L.append(f"| {lines[f]} | `{f.relative_to(repo)}` |")
        L.append("")
        if buckets["shimmable"]:
            L.append("## The annotation-shimmed files")
            L.append("")
            L.append("| lines | file | needs |")
            L.append("|---:|---|---|")
            for f in sorted(buckets["shimmable"], key=lambda p: -lines[p]):
                L.append(f"| {lines[f]} | `{f.relative_to(repo)}` | "
                         f"{', '.join(evidence[str(f.relative_to(repo))][:3])} |")
            L.append("")
        if buckets["log_only"]:
            L.append("## The Log-shimmed files")
            L.append("")
            L.append("| lines | file |")
            L.append("|---:|---|")
            for f in sorted(buckets["log_only"], key=lambda p: -lines[p]):
                L.append(f"| {lines[f]} | `{f.relative_to(repo)}` |")
            L.append("")
        return L

    out = report_lines()
    if args.report:
        Path(args.report).write_text("\n".join(out) + "\n", encoding="utf-8")
        print(f"wrote {args.report}")

    if args.json:
        Path(args.json).write_text(json.dumps({
            "total": total,
            "buckets": {b: [str(f.relative_to(repo)) for f in buckets[b]]
                        for b in ("portable", "shimmable", "log_only", "android")},
            "evidence": evidence,
            "tests": {"total": len(test_files), "robolectric": robolectric},
        }, indent=2), encoding="utf-8")

    if args.extract:
        dest = Path(args.extract).resolve()
        # core's own hand-written files live under .../core/, and the extracted
        # upstream packages go beside them. The split is recorded in
        # core/UPSTREAM.md so nobody has to guess which is which.
        root_pkg = "com/playtranslate"
        copied = []
        for b in ("portable", "shimmable", "log_only"):
            for f in buckets[b]:
                rel = f.relative_to(src)
                target = dest / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(f, target)
                copied.append((b, str(rel)))
        print(f"extracted {len(copied)} files into {dest}")
        if not args.reroot:
            print("kept upstream package name (pass --reroot to change it)")
        moved = 0
        for b, rel in (copied if args.reroot else []):
            p = dest / rel
            text = p.read_text(encoding="utf-8")
            if text.startswith(f"package {root_pkg}"):
                text = text.replace(f"package {root_pkg}", "package io.github.only_air.screengloss.upstream", 1)
                text = text.replace(f"import {root_pkg}.", "import io.github.only_air.screengloss.upstream.")
                p.write_text(text, encoding="utf-8")
                moved += 1
        print(f"re-rooted package on {moved} files")

    print(f"total {total}: portable={n('portable')} shimmable={n('shimmable')} "
          f"log_only={n('log_only')} android={n('android')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
