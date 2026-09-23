### Closure: the portable set is not a module

141 files carry §2.1's `portable` label. **57** of them name at least one same-package sibling that is *not* in the set.

Those names are not imports, so no dependency scanner sees them and no `import` graph will find them: in Kotlin a file can reference any top-level declaration in its own package with no import at all. This is why the extracted set produced compiler errors that are not about Android APIs.

| non-portable symbol | referenced by (files) |
|---|---:|
| `CooldownCause` | 5 |
| `OverlayToolkit` | 4 |
| `OcrManager` | 4 |
| `RecognizedRegion` | 4 |
| `BackendStatus` | 4 |
| `ScanlineReconciler` | 3 |
| `OcrImage` | 3 |
| `KeyStatus` | 3 |
| `ServiceType` | 3 |
| `CooldownLadder` | 3 |
| `CooldownState` | 3 |
| `YomitanStyledData` | 3 |
| `CaptureService` | 2 |
| `CaptureSource` | 2 |
| `TargetGlossLookup` | 2 |
| `TargetSense` | 2 |
| `LayoutAnalyzer` | 2 |
| `DetectedRegion` | 2 |
| `UsageTracker` | 2 |
| `SentenceAnkiContentView` | 2 |
| `CardOutputs` | 2 |
| `CardMode` | 2 |
| `WordEnrichment` | 2 |
| `YomitanDataStore` | 2 |
| `Homography` | 1 |
| `TrackMeasurement` | 1 |
| `LiveCaptureSource` | 1 |
| `DictionaryManager` | 1 |
| `SourceLanguageEngine` | 1 |
| `DictionaryResponse` | 1 |

The worst offenders are the ones §2.1 lists as *reusable* while they in fact sit on top of the display/capture stack:

- `OverlayToolkit` — 4 referrer(s), e.g. `LivePanelRecord.kt, PanelPresenter.kt, ReadingArbiter.kt, TranslationPresenter.kt`

**Consequence for the plan.** §2.1's arithmetic — "125 files can be compiled into the desktop module" — is true of each file *individually* and false of the set. What actually moves is the closure, and the closure either drags in `FrameCoordinates` (9118 lines, bitmap/display arithmetic) or requires each referrer to be cut. Either way it is a per-file decision. `pc/PORTABILITY.md` and this file are the input to that decision; §2.1 alone is not.

