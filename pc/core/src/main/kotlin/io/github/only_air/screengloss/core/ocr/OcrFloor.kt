package io.github.only_air.screengloss.core.ocr

import io.github.only_air.screengloss.core.translation.BackendKind

/**
 * The OCR engine catalog for the PC port — PORTING.md §3.5, which the design
 * doc calls "全篇最重要的架构结论": ML Kit cannot be ported, and it is the OCR
 * floor for 23 of the 26 source languages.
 *
 * ## The claim in the docs, checked against the code
 *
 * PORTING.md §0 and README.md both say ML Kit is the floor for **22 of the 26**
 * source languages. Counting `SourceLanguageProfiles` in
 * `app/src/main/java/com/playtranslate/language/Language.kt` gives **23**: the
 * three without a floor are RU, AR and TH (`mlKitFloor = null`), and the other
 * twenty-three resolve to one of the five ML Kit recognizers. The design's
 * arithmetic is off by one. It does not change any decision — ML Kit still has
 * to be rebuilt — but it is recorded here because the number is quoted in two
 * documents and will be quoted again.
 *
 * ## What replaces it
 *
 * | ML Kit family | languages | PC replacement (already in the catalog) |
 * |---|---|---|
 * | Latin | 18 | `paddle-rec-unified` |
 * | Chinese | zh, zh-Hant | `paddle-rec-unified` |
 * | Japanese | ja | `meiki-ja` (+ `manga-ocr-ja` for stylised/vertical) |
 * | Korean | ko | `paddle-rec-korean` |
 * | Devanagari | hi | `paddle-rec-devanagari` — **dormant upstream** |
 * | *(none — already MNN)* | ru, ar, th | `paddle-rec-cyrillic` / `-arabic` / `-thai` |
 *
 * ## Two consequences the design's table understates
 *
 * 1. **`paddle-rec-devanagari` is dormant upstream.** `Language.kt`'s
 *    `ScriptFamily.DEVANAGARI -> {}` branch comments it as such: the pack is
 *    listed but not wired, because ML Kit's Devanagari recognizer was always
 *    available. On the desktop it is the *only* candidate for Hindi, so Hindi
 *    has no verified OCR path at all. [SourceLang.ocrUnverified] carries that.
 * 2. **VI, TR and PL defaulted to ML Kit on purpose.** Upstream's `ocrBackends`
 *    puts the ML Kit floor *first* for Vietnamese, Turkish and Polish because
 *    the shared Paddle latin recognizer handles their letters less reliably
 *    (Vietnamese's dense diacritics, Turkish's dotless ı/İ, Polish's ł/ż/ź).
 *    That preference is not a default the port can inherit — the preferred
 *    engine is gone. [SourceLang.ocrQualityRisk] carries that, and the OCR
 *    picker must surface it instead of silently downgrading three languages.
 */
enum class OcrScriptFamily { LATIN, CJK_JAPANESE, CJK_CHINESE, CJK_KOREAN, ARABIC, DEVANAGARI, CYRILLIC, THAI }

/**
 * An on-device OCR engine. **There is deliberately no ML Kit member**: the
 * port must not be able to name it, so a future contributor cannot reintroduce
 * the dependency by writing one line.
 */
sealed interface OcrEngine {

    /** Downloadable model packs needed on disk. Empty = bundled. */
    val packKeys: Set<String>

    /** Runtime requirement, replacing upstream's arm64 ABI whitelist (see [OcrRuntimeGate]). */
    val runtime: OcrRuntime

    /** Stable token used in prefs and in the picker UI. */
    val selectionToken: String

    /** Display name. */
    val displayName: String

    /**
     * PaddleOCR recognizer pack + speed tier. Both tiers share one pack: the
     * tier is runtime configuration only, so it costs no extra download.
     */
    data class Paddle(val recPackKey: String, val fast: Boolean = false) : OcrEngine {
        override val packKeys: Set<String> = setOf(recPackKey)
        override val runtime: OcrRuntime = OcrRuntime.MNN
        override val selectionToken: String = if (fast) "$recPackKey@fast" else "$recPackKey@accurate"
        override val displayName: String = buildString {
            append(recPackKey.removePrefix("paddle-rec-"))
            append(if (fast) " (fast)" else " (accurate)")
        }
    }

    /** Meiki: detector + horizontal + vertical recognizers in one pack. */
    data class Meiki(val packKey: String) : OcrEngine {
        override val packKeys: Set<String> = setOf(packKey)
        override val runtime: OcrRuntime = OcrRuntime.MNN
        override val selectionToken: String = packKey
        override val displayName: String = "Meiki"
    }

    /** manga-ocr refinement for stylised and vertical Japanese. */
    data class MangaOcr(val packKey: String) : OcrEngine {
        override val packKeys: Set<String> = setOf(packKey)
        override val runtime: OcrRuntime = OcrRuntime.MNN
        override val selectionToken: String = packKey
        override val displayName: String = "manga-ocr"
    }

    /**
     * The null object. Upstream's `EmptyOcrEngine` — the waterfall's last rung
     * when the user's selection is missing *and* the floor is missing. On the
     * desktop this is reachable for real (a pack that was never downloaded),
     * so it has to produce a user-visible state, not just nothing.
     */
    data object None : OcrEngine {
        override val packKeys: Set<String> = emptySet()
        override val runtime: OcrRuntime = OcrRuntime.NONE
        override val selectionToken: String = "none"
        override val displayName: String = "None"
    }
}

/**
 * What a native runtime a backend needs. This replaces upstream's binary ABI
 * gate.
 *
 * Upstream's `:mnn` module ships `arm64-v8a` only and
 * `OnDeviceLlmBackend.supportsRequiredAbi()` hides the backend on 32-bit
 * devices — a binary whitelist. PORTING.md §3.2 flags the change explicitly:
 * on a PC, "can it run" is a *continuum* (does the CPU have AVX2, is there
 * enough VRAM, how much RAM is free), not a whitelist, so the gate must become
 * a capability probe. [OcrRuntimeGate] is that probe's interface.
 */
enum class OcrRuntime { MNN, ONNX, NONE }

/** Whether the machine can actually run a given runtime. */
interface OcrRuntimeGate {
    fun isAvailable(runtime: OcrRuntime): Boolean

    /** Human-readable reason the runtime is unavailable, for the picker. */
    fun unavailableReason(runtime: OcrRuntime): String?
}

/** The gate a fresh desktop process uses: everything is probed, nothing assumed. */
class CapabilityOcrRuntimeGate(
    private val mnnAvailable: Boolean = false,
    private val onnxAvailable: Boolean = false,
) : OcrRuntimeGate {
    override fun isAvailable(runtime: OcrRuntime): Boolean = when (runtime) {
        OcrRuntime.MNN -> mnnAvailable
        OcrRuntime.ONNX -> onnxAvailable
        OcrRuntime.NONE -> true
    }

    override fun unavailableReason(runtime: OcrRuntime): String? = when (runtime) {
        OcrRuntime.MNN -> if (mnnAvailable) null else "MNN runtime not built for this platform yet"
        OcrRuntime.ONNX -> if (onnxAvailable) null else "ONNX Runtime not bundled"
        OcrRuntime.NONE -> null
    }
}

/**
 * The 26 source languages, with the PC OCR candidate list.
 *
 * Candidates are in priority order, highest first — the same shape as
 * upstream's `SourceLanguageProfile.ocrBackends`, so the picker and the
 * fall-through logic port unchanged.
 */
enum class SourceLang(
    val code: String,
    val scriptFamily: OcrScriptFamily,
    /** True when the only candidate for this language is a pack upstream never wired. */
    val ocrUnverified: Boolean = false,
    /**
     * True when upstream *preferred* the ML Kit floor over the Paddle pack for
     * this language because of a known accuracy problem, and the PC port
     * therefore inherits a quality regression that must be surfaced rather than
     * hidden.
     */
    val ocrQualityRisk: Boolean = false,
    /** True for zh-Hant, which shares zh's pack but prefers traditional headwords. */
    val preferTraditional: Boolean = false,
) {
    JA("ja", OcrScriptFamily.CJK_JAPANESE),
    EN("en", OcrScriptFamily.LATIN),
    ZH("zh", OcrScriptFamily.CJK_CHINESE),
    ZH_HANT("zh-Hant", OcrScriptFamily.CJK_CHINESE, preferTraditional = true),
    ES("es", OcrScriptFamily.LATIN),
    FR("fr", OcrScriptFamily.LATIN),
    DE("de", OcrScriptFamily.LATIN),
    IT("it", OcrScriptFamily.LATIN),
    PT("pt", OcrScriptFamily.LATIN),
    NL("nl", OcrScriptFamily.LATIN),
    /** Upstream: ML Kit preferred — dotless ı/İ, ğ, ş. */
    TR("tr", OcrScriptFamily.LATIN, ocrQualityRisk = true),
    /** Upstream: ML Kit preferred — dense diacritics. */
    VI("vi", OcrScriptFamily.LATIN, ocrQualityRisk = true),
    ID("id", OcrScriptFamily.LATIN),
    SV("sv", OcrScriptFamily.LATIN),
    DA("da", OcrScriptFamily.LATIN),
    NO("no", OcrScriptFamily.LATIN),
    FI("fi", OcrScriptFamily.LATIN),
    HU("hu", OcrScriptFamily.LATIN),
    RO("ro", OcrScriptFamily.LATIN),
    CA("ca", OcrScriptFamily.LATIN),
    KO("ko", OcrScriptFamily.CJK_KOREAN),
    RU("ru", OcrScriptFamily.CYRILLIC),
    AR("ar", OcrScriptFamily.ARABIC),
    TH("th", OcrScriptFamily.THAI),
    /** Upstream: `paddle-rec-devanagari` is dormant, ML Kit was the only path. */
    HI("hi", OcrScriptFamily.DEVANAGARI, ocrUnverified = true),
    /** Upstream: ML Kit preferred — ł/ż/ź degrade at low resolution. */
    PL("pl", OcrScriptFamily.LATIN, ocrQualityRisk = true);

    /** OCR candidates in priority order. Never contains an ML Kit entry — it cannot. */
    val ocrCandidates: List<OcrEngine>
        get() = buildList {
            when (scriptFamily) {
                OcrScriptFamily.CJK_JAPANESE -> {
                    // Meiki first (highest accuracy), then manga-ocr as the
                    // stylised/vertical refinement, then the shared Paddle
                    // recognizer as the always-downloadable generalist.
                    add(OcrEngine.Meiki("meiki-ja"))
                    add(OcrEngine.MangaOcr("manga-ocr-ja"))
                    addPaddleTiers("paddle-rec-unified")
                }
                OcrScriptFamily.CJK_CHINESE -> addPaddleTiers("paddle-rec-unified")
                OcrScriptFamily.CJK_KOREAN -> addPaddleTiers("paddle-rec-korean")
                OcrScriptFamily.LATIN -> addPaddleTiers("paddle-rec-unified")
                OcrScriptFamily.CYRILLIC -> addPaddleTiers("paddle-rec-cyrillic")
                OcrScriptFamily.ARABIC -> addPaddleTiers("paddle-rec-arabic")
                OcrScriptFamily.THAI -> addPaddleTiers("paddle-rec-thai")
                OcrScriptFamily.DEVANAGARI -> addPaddleTiers("paddle-rec-devanagari")
            }
        }

    /**
     * Each Paddle recognizer is offered as two speed tiers over one pack:
     * accurate (fp32, full-res detection — the default) and fast (fp16 +
     * reduced detector input; opt-in, may miss very small text). Same pack key,
     * so download / dedup / delete see one pack.
     */
    private fun MutableList<OcrEngine>.addPaddleTiers(recPackKey: String) {
        add(OcrEngine.Paddle(recPackKey))
        add(OcrEngine.Paddle(recPackKey, fast = true))
    }

    /** The floor: the candidate used when the user's selection is unavailable. */
    val ocrFloor: OcrEngine get() = ocrCandidates.firstOrNull() ?: OcrEngine.None

    /** Every distinct pack key this language may need. */
    val packKeys: Set<String> get() = ocrCandidates.flatMap { it.packKeys }.toSet()

    companion object {
        fun fromCode(code: String?): SourceLang? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Engine resolution — upstream `ocr/registry/OcrModelManager.kt`'s
 * `resolve`, ported.
 *
 * Upstream's rule, with ML Kit as the floor:
 * ```
 * available.firstOrNull { it.selectionToken == token } ?: mlKitFloor ?: available.firstOrNull()
 * ```
 * The port keeps the rule and swaps the floor for [SourceLang.ocrFloor]. The
 * subtlety worth preserving: a **stale** selection token (the user picked a
 * pack that has since been deleted) resolves to the *floor*, not to "nothing",
 * so a user who never touches the OCR page still gets OCR.
 */
object OcrRegistry {

    data class Resolution(
        val engine: OcrEngine,
        val reason: Reason,
    ) {
        enum class Reason {
            /** The user's stored selection is installed. */
            USER_SELECTION,

            /** The stored token is stale or the pack is missing; the floor took over. */
            FLOOR_FALLBACK,

            /** No candidate is installed; the caller must offer a download. */
            NOTHING_INSTALLED,
        }
    }

    /**
     * @param selectedToken the user's stored selection, or null.
     * @param installedPackKeys packs actually present on disk.
     */
    fun resolve(
        lang: SourceLang,
        selectedToken: String?,
        installedPackKeys: Set<String>,
        gate: OcrRuntimeGate,
    ): Resolution {
        val runnable = lang.ocrCandidates.filter { candidate ->
            candidate.packKeys.all { it in installedPackKeys } && gate.isAvailable(candidate.runtime)
        }
        if (runnable.isEmpty()) return Resolution(OcrEngine.None, Resolution.Reason.NOTHING_INSTALLED)

        val selected = runnable.firstOrNull { it.selectionToken == selectedToken }
        if (selected != null) return Resolution(selected, Resolution.Reason.USER_SELECTION)

        return Resolution(runnable.first(), Resolution.Reason.FLOOR_FALLBACK)
    }

    /**
     * Upstream's "rescue" path: when the user's chosen engine produced nothing
     * usable for a frame, retry once on the floor before giving up. Kept
     * because the desktop has an extra reason to need it — a window that is
     * partly occluded, or an HDR frame whose normalisation was imperfect, can
     * defeat a stylised-text recognizer while a generalist still reads it.
     */
    fun rescueEngine(
        lang: SourceLang,
        primary: OcrEngine,
        installedPackKeys: Set<String>,
        gate: OcrRuntimeGate,
    ): OcrEngine? = lang.ocrCandidates.firstOrNull { candidate ->
        candidate.selectionToken != primary.selectionToken &&
            candidate.packKeys.all { it in installedPackKeys } &&
            gate.isAvailable(candidate.runtime)
    }

    /**
     * A language whose only candidate is [SourceLang.ocrUnverified] cannot
     * promise anything; the UI must say so rather than show an empty result.
     */
    fun needsDownload(lang: SourceLang, installedPackKeys: Set<String>): Set<String> =
        lang.ocrCandidates.firstOrNull { it.packKeys.all { p -> p in installedPackKeys } }
            ?.let { emptySet() }
            ?: (lang.ocrCandidates.firstOrNull()?.packKeys ?: emptySet())
}
