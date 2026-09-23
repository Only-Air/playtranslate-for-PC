package io.github.only_air.screengloss.core.translation

/**
 * The translation backend contract and its capability side-interfaces.
 *
 * Upstream's `TranslationBackend` + `Capabilities.kt` (212 lines) are the
 * single most reusable interface in the codebase: nine HTTP services, ML Kit,
 * and four on-device LLM tiers all sit behind them, and the waterfall is
 * written against the interface, not the implementations. PORTING.md §7.4
 * lists `TranslationBackend` as one of the four seams to preserve verbatim.
 *
 * It is preserved — with one deliberate deviation, stated here rather than
 * discovered later:
 *
 * **`suspend` is gone.** Upstream's contract is `suspend fun translate(...)`
 * and the waterfall is a coroutine that propagates `CancellationException`
 * without wrapping. The port makes the contract *blocking*, with an explicit
 * [TranslationCall.abort] predicate instead. Two reasons:
 *
 *  1. `core` must keep zero third-party dependencies (it is the module the
 *     Android source is extracted into, and every dependency there is one the
 *     desktop cannot share with the phone);
 *  2. the actual cancellation requirement is "stop translating this stale
 *     frame", which is a *predicate the caller already knows* (the capture
 *     generation counter), not structured concurrency.
 *
 * The shell runs each waterfall pass on a worker and passes
 * `{ generation != myGeneration }` as the abort predicate. If this proves
 * insufficient once real HTTP backends land, coroutines come back — the
 * interface is the only thing that would change.
 */

/** Which layer of the waterfall a backend belongs to. Order matters: see [Waterfall]. */
enum class BackendKind {
    /** A remote HTTP service (DeepL, OpenAI, Gemini, …). */
    ONLINE,

    /** A model server the user runs themselves on loopback (PORTING §3.7). */
    LOCAL_SERVER,

    /** Bergamot/slimt direction pairs, shipped or downloaded. */
    OFFLINE_BERGAMOT,

    /** ONNX Runtime NMT for pairs Bergamot does not cover (PORTING §3.4). */
    OFFLINE_ONNX,
}

/**
 * Where a result came from. This replaces upstream's `isDegradedFallback`
 * boolean, which meant exactly one thing: "ML Kit produced this".
 *
 * PORTING.md §3.5 is explicit that the flag's *semantics* have to change rather
 * than its value, because ML Kit has no desktop build and the whole notion of a
 * guaranteed fallback is gone. What survives is the useful half: a result that
 * came from a floor layer must not enter the cache, because the cache key does
 * not record which backend produced it and a floor result is the one the user
 * is least likely to want served again.
 */
enum class ResultOrigin {
    /** A remote service the user configured. */
    ONLINE_SERVICE,

    /** The user's own Ollama / LM Studio / llama.cpp (PORTING §3.7). */
    LOCAL_LLM_SERVER,

    /** Offline Bergamot pair. */
    OFFLINE_BERGAMOT,

    /** Offline ONNX pair. */
    OFFLINE_ONNX,

    /** Served from [TranslationCache]; the origin was cached alongside it. */
    CACHE;

    /** True when the result came from a layer that exists to keep working without a network. */
    val isFloor: Boolean
        get() = this == OFFLINE_BERGAMOT || this == OFFLINE_ONNX

    /** Floor results are not cached; see the class doc. */
    val isCacheable: Boolean get() = !isFloor

    /** Drives the tray status light: amber when degraded to an offline floor. */
    val isDegraded: Boolean get() = isFloor
}

/** One translate call, including the abort predicate the shell supplies. */
data class TranslationCall(
    val text: String,
    val source: String,
    val target: String,
    /** True when the caller no longer wants this result (stale capture generation). */
    val abort: () -> Boolean = { false },
    /**
     * Epoch-ms at which the waterfall began. Passed to [CooldownState.recordSuccess]
     * so a stale in-flight success cannot erase a cooldown recorded by a later
     * failure — upstream's rule, kept because parallel fan-out is the norm here too.
     */
    val attemptStartedAtMs: Long = System.currentTimeMillis(),
)

/** The result of a completed waterfall pass. */
sealed class TranslationResult {

    /** A translation was produced. */
    data class Translated(
        val text: String,
        val backendId: String,
        val origin: ResultOrigin,
    ) : TranslationResult()

    /**
     * No configured backend could serve this pair.
     *
     * **This is the state upstream does not have, and it is not an error.** On
     * Android the waterfall ended at ML Kit, which always produced *something*,
     * so "the app returned no translation" was a bug. On the desktop the honest
     * answer for an uncovered pair with no network is "there is no offline model
     * for this pair", and PORTING §3.5 requires saying so explicitly rather than
     * showing a machine-translated guess from a layer that does not exist.
     */
    data class NoOfflineModel(
        val source: String,
        val target: String,
        /** What the user could do about it, for the UI to render. */
        val remedies: List<Remedy> = Remedy.defaults,
    ) : TranslationResult() {
        enum class Remedy {
            CONFIGURE_ONLINE_SERVICE, START_LOCAL_SERVER, DOWNLOAD_BERGAMOT_PAIR, USE_CLIPBOARD;

            companion object {
                /**
                 * What the UI offers by default. Ordered by what actually
                 * unblocks the user: a local server is one command away, an
                 * online service needs a key, a Bergamot pair is a download.
                 */
                val defaults: List<Remedy> = listOf(
                    CONFIGURE_ONLINE_SERVICE, START_LOCAL_SERVER, DOWNLOAD_BERGAMOT_PAIR, USE_CLIPBOARD,
                )
            }
        }
    }

    /** Every backend was tried and every one failed. Distinct from [NoOfflineModel]. */
    data class AllFailed(
        val failures: List<BackendFailure>,
    ) : TranslationResult()

    /** The caller's abort predicate fired; the work was abandoned. */
    object Aborted : TranslationResult()
}

/** One backend's failure, kept for the diagnostics page and the forensics log. */
data class BackendFailure(
    val backendId: String,
    val kind: BackendKind,
    val cause: CooldownCause,
    val message: String,
    val retryAtMs: Long?,
)

/**
 * A translation backend. Implementations are pair-agnostic singletons, as
 * upstream requires: the *pair* is a call argument, never constructor state,
 * so one instance serves every language the user switches between.
 */
interface TranslationBackend {

    /** Stable id used in prefs, in the cache key, and over the bridge. Never rename. */
    val id: String

    val displayName: String

    val kind: BackendKind

    /**
     * Default waterfall position: ascending, with the id as a tiebreaker for
     * determinism. Kept from upstream (`TranslationBackend.priority`) because
     * the ordering is user-visible — the services page lists backends in this
     * order and the user can reorder them, which overrides this.
     */
    val priority: Int get() = 100

    /**
     * Whether this backend can serve the pair. Cheap and side-effect free —
     * the waterfall calls it for every backend on every pass.
     */
    fun supports(source: String, target: String): Boolean

    /**
     * Translate one string.
     *
     * Must throw [TranslationFailure] on a failure the waterfall should treat as
     * "try the next backend", and must return the abort path (checking
     * [TranslationCall.abort]) rather than completing a stale request.
     */
    fun translate(call: TranslationCall): String
}

/** Thrown by backends; carries the typed cause the cooldown layer keys off. */
class TranslationFailure(
    /** Typed cause. Named `cooldownCause` because `cause` is `Throwable.cause`. */
    val cooldownCause: CooldownCause,
    message: String,
    /** Epoch-ms the backend asks to be skipped until, or null to stay in the rotation. */
    val retryAtMs: Long? = null,
    causeException: Throwable? = null,
) : Exception(message, causeException)

/** Typed failure classes. Drives both cooldown policy and the localised message. */
enum class CooldownCause {
    /** 429. */
    RATE_LIMITED,

    /** 456 / monthly character cap. */
    MONTHLY_QUOTA,

    /** `insufficient_quota` / billing. */
    BILLING_EXHAUSTED,

    /** 5xx. */
    SERVER_ERROR,

    /**
     * The device lost its network. Deliberately distinct from the four above,
     * because it describes *the machine* and not *the provider* — which is why
     * it is the only cause a connectivity-restored event may clear.
     */
    CONNECTION_FAILED,

    /** 4xx the user has to fix (bad model id, malformed request). Never a cooldown. */
    STRUCTURAL,

    /** 401/403. Never a cooldown: the user has to fix something, not wait. */
    AUTH,
}

/**
 * Backends that participate in the waterfall's skip-when-down policy.
 *
 * Ported from upstream's `Cooldownable` side-interface, including the subtle
 * parts that are easy to lose in a rewrite:
 *
 *  - `recordSuccess` **refuses** to clear a cooldown newer than the attempt it
 *    is reporting on. Without that guard, a parallel waterfall pass that
 *    started before the failure and succeeded after it would erase the
 *    cooldown, and the next pass would hammer the provider again.
 *  - `onConnectivityRestored` clears a [CooldownCause.CONNECTION_FAILED]
 *    cooldown *and nothing else*. A rate limit does not expire because the
 *    Wi-Fi came back.
 */
interface Cooldownable {

    /** Epoch-ms until which the waterfall should skip this backend, or null when ready. */
    fun unavailableUntil(): Long?

    /** Typed cause, for message selection. Null when not cooling down. */
    fun unavailableCause(): CooldownCause?

    /** Called by the waterfall when this backend wins. */
    fun recordSuccess(attemptStartedAtMs: Long)

    /** Called when the machine regains a network. @return true if a cooldown was cleared. */
    fun onConnectivityRestored(): Boolean

    /** Clean slate on a user gesture that means "try it again now". */
    fun resetCooldown()
}

/**
 * The cooldown state machine.
 *
 * The network ladder is the one piece of upstream policy that is easy to drop
 * and expensive to rediscover: a single connection failure is *forgiven*, so a
 * laptop that suspends and wakes does not immediately cool every backend down.
 * Only the second consecutive connection failure starts a cooldown. Upstream
 * calls this the "forgiven first failure" tracker; the behaviour is kept.
 */
class CooldownState(
    /** How long a rate-limited backend is skipped. */
    private val rateLimitMs: Long = 60_000,
    /** How long a server-error backend is skipped. */
    private val serverErrorMs: Long = 30_000,
) : Cooldownable {

    private var untilMs: Long? = null
    private var cause: CooldownCause? = null
    private var setAtMs: Long = 0L
    private var consecutiveConnectionFailures: Int = 0

    override fun unavailableUntil(): Long? = untilMs

    override fun unavailableCause(): CooldownCause? = cause

    /** Short English reason; localised text is chosen from the cause by the UI. */
    fun unavailableDescription(): String? = when (cause) {
        null -> null
        CooldownCause.RATE_LIMITED -> "Rate limited"
        CooldownCause.MONTHLY_QUOTA -> "Monthly quota used"
        CooldownCause.BILLING_EXHAUSTED -> "Billing exhausted"
        CooldownCause.SERVER_ERROR -> "Provider error"
        CooldownCause.CONNECTION_FAILED -> "No connection"
        CooldownCause.STRUCTURAL -> "Rejected request"
        CooldownCause.AUTH -> "Invalid key"
    }

    /**
     * Record a failure. AUTH and STRUCTURAL never set a cooldown: the user has
     * something to fix, and skipping the backend for a minute would hide the
     * problem instead of surfacing it.
     *
     * @return true when a cooldown was recorded.
     */
    fun recordFailure(failure: TranslationFailure, nowMs: Long = System.currentTimeMillis()): Boolean {
        when (failure.cooldownCause) {
            CooldownCause.AUTH, CooldownCause.STRUCTURAL -> return false
            CooldownCause.CONNECTION_FAILED -> {
                consecutiveConnectionFailures++
                // Forgiven first failure: one blip (a suspend/resume, a Wi-Fi
                // handover) must not cool the whole waterfall down.
                if (consecutiveConnectionFailures < 2) return false
            }
            else -> consecutiveConnectionFailures = 0
        }
        val delay = failure.retryAtMs?.let { it - nowMs } ?: when (failure.cooldownCause) {
            CooldownCause.RATE_LIMITED, CooldownCause.MONTHLY_QUOTA, CooldownCause.BILLING_EXHAUSTED -> rateLimitMs
            CooldownCause.SERVER_ERROR -> serverErrorMs
            CooldownCause.CONNECTION_FAILED -> serverErrorMs
            else -> serverErrorMs
        }
        untilMs = nowMs + delay.coerceAtLeast(0L)
        cause = failure.cooldownCause
        setAtMs = nowMs
        return true
    }

    override fun recordSuccess(attemptStartedAtMs: Long) {
        consecutiveConnectionFailures = 0
        val set = setAtMs
        // A cooldown recorded *after* this attempt started belongs to a later
        // failure and must not be cleared by this success.
        if (untilMs != null && set > attemptStartedAtMs) return
        untilMs = null
        cause = null
        setAtMs = 0L
    }

    override fun onConnectivityRestored(): Boolean {
        consecutiveConnectionFailures = 0
        if (cause != CooldownCause.CONNECTION_FAILED) return false
        untilMs = null
        cause = null
        setAtMs = 0L
        return true
    }

    override fun resetCooldown() {
        untilMs = null
        cause = null
        setAtMs = 0L
        consecutiveConnectionFailures = 0
    }

    fun isCoolingDown(nowMs: Long = System.currentTimeMillis()): Boolean = (untilMs ?: 0L) > nowMs

    /** Exposed for the tests that pin the forgiven-first-failure rule. */
    val connectionFailureStreak: Int get() = consecutiveConnectionFailures
}

/**
 * Backends that can translate a list in ONE remote call.
 *
 * The all-or-nothing contract is upstream's and is preserved because it is what
 * makes the waterfall's fall-through correct: a partial batch has no
 * representation, so a backend that cannot complete the batch throws and the
 * next backend gets the *whole* list.
 */
interface BatchTranslator {
    fun translateBatch(texts: List<String>, source: String, target: String, abort: () -> Boolean): List<String>
}
