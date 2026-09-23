### What the extracted set actually needs

| tier | files | lines | meaning |
|---|---:|---:|---|
| `jdk` | 108 | 12332 | JDK + kotlin stdlib only |
| `shim` | 14 | 2578 | JDK + `android.util.Log` / annotation shims |
| `thirdparty` | 26 | 5819 | JDK + an external JVM library that also exists on desktop |
| `unmovable` | 0 | 0 | reaches the Android framework — the static scan over-admitted it |

Libraries the extract pulls onto the desktop classpath, by how many files need each:

| library | files | note |
|---|---:|---|
| `kotlinx-coroutines-core` | 15 | desktop-clean; the portable set assumes it heavily |
| `okhttp` | 8 | same library on desktop |
| `gson` | 5 | same library on desktop |
| `sudachi` | 1 | Sudachi JVM; desktop-clean, ships its own dictionary |
| `lucene-analyzers-common` | 1 | used by the Snowball stemmers; desktop-clean |
| `opencv` | 1 | the Android OpenCV AAR; desktop needs the native OpenCV build |

