# `pc/` — the PC application

`PORTING.md` is the design. This directory is the part of the design that has been
written. It follows §7.3's module layout, and where it deviates the reason is in
the source file's own comment.

The one-line status: **the bridge, the panel, the action model and the packaging
path are written and verified end to end; the platform backends, the native
overlay and the dictionary stack are not.** `pc/GAP_REPORT.md` has the full list
and the evidence for every claim below.

## Build

There is no Gradle build for `pc/` yet — that is deliberate for now: Gradle needs
network access for every plugin, and the point of this stage was to find out
whether the code compiles *at all* against a plain JVM. It does. The build below
is what was used to produce every result in `GAP_REPORT.md`, and it is a single
`kotlinc` invocation.

```bash
# Prerequisite: a Kotlin 2.x compiler and JDK 17+.
#   https://github.com/JetBrains/kotlin/releases  -> kotlin-compiler-<ver>.zip

KOTLINC=${KOTLINC:-kotlinc/bin/kotlinc}
STDLIB=${KOTLIN_STDLIB:-kotlinc/lib/kotlin-stdlib.jar}
COROUTINES=${COROUTINES:-libs/kotlinx-coroutines-core-jvm.jar}

# 1) generate R.kt and the panel's i18n from the Android resource tree
python3 pc/tools/gen_resources.py --res app/src/main/res \
    --r-kt pc/core/src/main/kotlin/com/playtranslate/R.kt \
    --i18n pc/ui-web/i18n

# 2) compile (core + shims + bridge + platform-linux + shell)
mkdir -p pc/build/classes
"$KOTLINC" -jvm-target 17 -cp "$COROUTINES" -d pc/build/classes \
  $(find pc/core/src/main/kotlin pc/core/src/shims/kotlin \
          pc/bridge/src pc/platform-linux/src pc/shell/src -name '*.kt')

# 3) run
java -cp "pc/build/classes:$STDLIB:$COROUTINES" \
     io.github.only_air.screengloss.shell.Main --selftest
```

`pc/core/src/upstream/kotlin/` is **not** in that `find`, on purpose — see
"Staging area" below.

## Run

The application is a CLI today, because a GUI shell needs the platform backends
that do not exist yet. Three commands:

```
--probe        print the P-1 capability decision record for this machine (§9)
--probe-json   the same as JSON (what the panel's status page reads)
--serve        start the bridge + panel, print the panel URL. Default with no args.
--selftest     prove §7.2.3's security constraints hold (14 checks)
--version
```

`--serve` prints a URL with a per-launch bearer token in the fragment. Open it in
the bundled webview once the JCEF host exists, or **in your own browser** — §7.2.3
wants the second to be possible, and it is the fallback that makes GNOME-Wayland
(no overlay) survivable.

## Layout

```
pc/
├─ core/src/main/kotlin/            hand-written core, zero platform deps
│   └─ io/github/only_air/screengloss/core/
│       ├─ geometry/    PtRect, PtPointF, PtImage, ColorNormalizer, ScreenGeometry
│       ├─ action/      Input, Action registry, HotkeyDecision, HotkeyRouter,
│       │               ConflictDetector  (§5.7's single source of truth)
│       ├─ translation/ Backend contract, Waterfall  (§3.5's explicit failure)
│       ├─ region/      RegionSpec  (§5.3 item 8: two frames of reference)
│       ├─ ocr/         OcrFloor    (§3.5: what replaces ML Kit, per language)
│       └─ platform/    Seams, CapabilityProbe  (§7.4)
├─ core/src/main/kotlin/com/playtranslate/R.kt   GENERATED from res/values/*.xml
├─ core/src/shims/kotlin/           android.util.Log + androidx.annotation shims
├─ core/src/upstream/kotlin/        staging area, does not compile — see below
├─ bridge/src/main/kotlin/          loopback HTTP + WebSocket + token (§7.2.3)
├─ platform-linux/src/main/kotlin/  the P-1 probe (§9)
├─ shell/src/main/kotlin/           entry point, BridgeServer.Api, SelfTest
├─ ui-web/                          the panel (§7.2.4's route table) + i18n
├─ tools/                           reproducible analysis + payload builder
└─ build/                           output, git-ignored
```

## Staging area: `core/src/upstream/`

148 files copied out of `app/src/main/java/` by `pc/tools/scan_portable.py`, into
the package `io.github.only_air.screengloss.upstream` — except the package was
kept as `com.playtranslate` on purpose, so that re-syncing from upstream is a diff
rather than a merge (the two modules never share a classpath).

**These files do not compile, and are not built.** That is the finding, not an
oversight: `pc/evidence/closure.md` shows 57 of the 141 "portable" files reference
same-package siblings that were not extracted, and Kotlin resolves those with no
import, so no dependency scanner sees them. Moving the set is a per-file decision.
Putting it in the tree rather than throwing it away makes `closure.md` and
`triage.md` the work order for that phase.

## What is verified, and how

| claim | artifact | how to reproduce |
|---|---|---|
| core + bridge + shell compile against a plain JVM | `pc/build/classes` (199 classes) | step 2 above |
| §7.2.3's five security constraints hold | 14/14 in `--selftest` | step 3 above |
| the packaging scripts accept a real payload unchanged | `screengloss_0.2.0_amd64.deb` (3.4 MB) | `pc/tools/make_payload.sh` then `packaging/linux/build-deb.sh` |
| the packaged launcher + jar run | `--version`, `--selftest`, `--serve` from the payload tree | run `payload/usr/bin/screengloss` |
| §2.1's file/line baseline, recomputed | `pc/PORTABILITY.md` | `pc/tools/scan_portable.py` |
| the "portable" set is not closed under reference | `pc/evidence/closure.md` | `pc/tools/closure.py` |
| what the extracted set needs on the classpath | `pc/evidence/triage.md` | `pc/tools/triage_deps.py` |
| 11 508 translated strings exist upstream and convert | `pc/ui-web/i18n/*.json` | `pc/tools/gen_resources.py` |

## Conventions this directory follows

These are the ones that were decided in `PORTING.md` and are easy to violate by
accident:

1. **`core` has no dependencies.** That is what makes it the module upstream code
   can be extracted into. Coroutines are the single current exception and it is
   recorded in `triage.md`; if a second one appears, `core` has stopped being the
   extraction target.
2. **A platform capability that is missing is a `Support.No`, not an exception.**
   `Support` has three states because "not portable", "portable but different" and
   "portable" are three different product answers. See `core/platform/Seams.kt`.
3. **Every route in the panel names its upstream file.** §7.2.4's table is in
   `ui-web/app.js` as data, `disposition` field included, so the correspondence can
   be read rather than trusted.
4. **A screen with no implementation says so.** `unported()` in `app.js`.
5. **Generated files say they are generated** and regenerate reproducibly; `R.kt`
   uses crc32, not Python's salted `hash()`.
6. **When a design document's number turns out to be wrong, the correction goes in
   the source as a comment**, and in `pc/GAP_REPORT.md` §2. `OcrFloor.kt` is the
   worked example (23 of 26 languages, not 22).
