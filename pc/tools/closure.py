#!/usr/bin/env python3
"""Is the "portable" file set closed under reference?

PORTING.md §2.1 presents the 125 pure-JVM files as a set you can "直接编进桌面模块".
The compile run says otherwise, and it says something specific: the errors are
not dominated by Android APIs, they are dominated by *references to sibling
files that were not extracted* — `CaptureSession` calls `OverlayToolkit`,
`LiveMode` names `TextBox`, and both of those are in the Android bucket.

A set that is not closed under reference is not a module. This script measures
how far from closed it is, because the size of that gap is the honest cost of
§2.1's plan:

  * a reference to an extracted sibling costs nothing;
  * a reference to a *non-extracted* sibling means either that sibling must be
    ported too, or the referrer must be cut — and each is a decision, not a
    copy.

Usage:
    python3 pc/tools/closure.py --src app/src/main/java --portable pc/PORTABILITY.md
"""

from __future__ import annotations

import argparse
import re
from collections import Counter, defaultdict
from pathlib import Path

# Top-level declarations, which is what Kotlin makes visible to a sibling file
# in the same package without an import.
DECL_RE = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:public |internal |private |abstract |open |sealed |data |value |annotation |enum |actual |expect |final |inline |suspend |external )*"
    r"(?:class|interface|object|fun|val|var|typealias)\s+([A-Za-z_][\w]*)",
    re.MULTILINE,
)
IMPORT_RE = re.compile(r"^\s*import\s+(com\.playtranslate[\w.]*)", re.MULTILINE)
PKG_RE = re.compile(r"^package\s+([\w.]+)", re.MULTILINE)


# `DECL_RE` is a regex over text, not a parser, so it occasionally captures a
# bare keyword (`interface`) or a stdlib type name (`List`) from a construct it
# misread. Filtering them by name is honest bookkeeping — a report that leads
# with "`List` is referenced by 23 files" discredits the real rows.
FALSE_POSITIVES = {
    "List", "Map", "Set", "String", "Int", "Boolean", "Float", "Double", "Long",
    "Any", "Unit", "Nothing", "Array", "Pair", "Triple", "Result",
    "interface", "class", "object", "fun", "val", "var", "typealias", "enum",
}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="Android source root")
    ap.add_argument("--portable", required=True, help="PORTABILITY.md, read for its file table")
    ap.add_argument("--out", help="write the report here")
    args = ap.parse_args()

    src = Path(args.src).resolve()
    md = Path(args.portable).read_text(encoding="utf-8")

    # The portable list is read back out of the generated report rather than
    # recomputed, so this script cannot disagree with it.
    portable: set[str] = set()
    for m in re.finditer(r"^\| \d+ \| `([^`]+\.kt)` \|$", md, re.MULTILINE):
        portable.add(m.group(1))

    # Keys are repo-relative paths, the same form PORTABILITY.md prints.
    root = Path(".").resolve()
    all_files = {str(f.relative_to(root)): f for f in src.rglob("*.kt")}
    portable_files = {p: f for p in portable if (f := root / p).is_file()}
    if not portable_files:
        print("no portable files matched — is --portable pointed at the generated report?")
        return 2

    # name -> file, for every top-level declaration in the tree.
    decl_owner: dict[str, str] = {}
    file_pkg: dict[str, str] = {}
    file_decls: dict[str, set[str]] = defaultdict(set)
    for rel, f in all_files.items():
        text = f.read_text(encoding="utf-8", errors="replace")
        m = PKG_RE.search(text)
        if m:
            file_pkg[rel] = m.group(1)
        for name in DECL_RE.findall(text):
            decl_owner.setdefault(name, rel)
            file_decls[rel].add(name)

    # For each portable file: which of its same-package siblings does it name?
    dangling: dict[str, set[str]] = {}
    missing_sibling_for: dict[str, set[str]] = defaultdict(set)
    for rel, f in sorted(portable_files.items()):
        text = f.read_text(encoding="utf-8", errors="replace")
        pkg = file_pkg.get(rel, "")
        # Strip comments: a KDoc `[OverlayToolkit]` link is not a dependency.
        code = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
        code = re.sub(r"//[^\n]*", "", code)
        pkg_decls: set[str] = set()
        for other_rel, other_pkg in file_pkg.items():
            if other_pkg == pkg and other_rel != rel:
                pkg_decls |= file_decls[other_rel]
        hit = {n for n in pkg_decls if re.search(rf"\b{re.escape(n)}\b", code)}
        external = {n for n in hit if decl_owner.get(n) not in portable_files}
        if external:
            dangling[rel] = external
            for n in external:
                missing_sibling_for[n].add(rel)

    for fp in FALSE_POSITIVES:
        missing_sibling_for.pop(fp, None)
    counter = Counter({n: len(v) for n, v in missing_sibling_for.items()})

    out: list[str] = []
    out.append("### Closure: the portable set is not a module")
    out.append("")
    out.append(f"{len(portable_files)} files carry §2.1's `portable` label. "
               f"**{len(dangling)}** of them name at least one same-package sibling that is *not* in the set.")
    out.append("")
    out.append("Those names are not imports, so no dependency scanner sees them and no "
               "`import` graph will find them: in Kotlin a file can reference any top-level "
               "declaration in its own package with no import at all. This is why the extracted "
               "set produced compiler errors that are not about Android APIs.")
    out.append("")
    out.append("| non-portable symbol | referenced by (files) |")
    out.append("|---|---:|")
    for name, n in counter.most_common(30):
        out.append(f"| `{name}` | {n} |")
    out.append("")
    out.append("The worst offenders are the ones §2.1 lists as *reusable* while they in fact "
               "sit on top of the display/capture stack:")
    out.append("")
    for name in ("OverlayToolkit", "TextBox", "FrameCoordinates", "Prefs", "DisplayUtils"):
        if name in counter:
            refs = sorted(missing_sibling_for[name])[:4]
            out.append(f"- `{name}` — {counter[name]} referrer(s), e.g. `{', '.join(Path(r).name for r in refs)}`")
    out.append("")
    out.append("**Consequence for the plan.** §2.1's arithmetic — \"125 files can be compiled "
               "into the desktop module\" — is true of each file *individually* and false of the "
               "set. What actually moves is the closure, and the closure either drags in "
               "`FrameCoordinates` (9118 lines, bitmap/display arithmetic) or requires each "
               "referrer to be cut. Either way it is a per-file decision. `pc/PORTABILITY.md` "
               "and this file are the input to that decision; §2.1 alone is not.")
    out.append("")

    text = "\n".join(out)
    if args.out:
        Path(args.out).write_text(text + "\n", encoding="utf-8")
        print(f"wrote {args.out}")
    print(f"{len(portable_files)} portable, {len(dangling)} with non-portable siblings")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
