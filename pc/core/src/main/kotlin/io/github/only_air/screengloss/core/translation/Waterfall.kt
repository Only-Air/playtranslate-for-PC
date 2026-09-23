package io.github.only_air.screengloss.core.translation

/**
 * The waterfall — upstream `translation/TranslationBackendRegistry.kt`'s core
 * loop, ported, plus the one structural change PORTING.md §3.5 demands.
 *
 * ## What is preserved
 *
 * The loop itself, including four behaviours that are easy to lose:
 *
 *  1. **Order is `(priority, id)` with a user override on top.** Unknown ids in
 *     the override are skipped, missing ids are appended in default order — so
 *     a service the user deleted cannot leave a hole in the rotation.
 *  2. **Cooldown skips are per-iteration.** `now` is re-read for every backend,
 *     because an earlier backend may have hung for seconds before failing,
 *     during which a later backend's shorter cooldown could have elapsed.
 *  3. **The "preferred backend" identity excludes cooled-down backends.** This
 *     is what makes the cache invalidate itself when a backend goes down and
 *     comes back, instead of shadowing fresh results with stale floor output.
 *  4. **A displaced on-device LLM suppresses caching.** Upstream tracks
 *     `displacedLlmId` so one low-memory moment cannot freeze a worse result
 *     into the cache. The port generalises it to [ResultOrigin.isCacheable].
 *
 * ## What changed
 *
 * Upstream's waterfall **cannot fail**. Its last rung is ML Kit, which always
 * produces something, so `translate` either returns a result or throws
 * `IllegalStateException("All translation backends failed")` — an error that
 * in practice means "the app is broken". ML Kit has no desktop build, and
 * PORTING §3.5 is explicit that the desktop must not invent a replacement
 * floor:
 *
 * ```
 * user-selected backend (online LLM / local server / Bergamot)
 *   -> Bergamot          (offline, covered pairs)
 *   -> ONNX Runtime NMT  (offline, remaining pairs)
 *   -> [no "always returns something" rung]  -> say so, explicitly
 * ```
 *
 * So [Waterfall.translate] returns [TranslationResult.NoOfflineModel] where
 * upstream would have returned an ML Kit guess. The user-visible consequence is
 * the one §3.5 asks for: "该语言对无离线模型" is a *state*, not a failure, and the
 * UI can offer the three things that would fix it.
 */
class Waterfall(
    backends: List<TranslationBackend>,
    private val cache: TranslationCache = TranslationCache(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Sink for the one-line waterfall trail, mirroring upstream's `TranslateDiag`. */
    private val onTrail: (String) -> Unit = {},
) {

    @Volatile
    private var backends: List<TranslationBackend> = backends

    @Volatile
    private var orderOverride: List<String>? = null

    private val cooldowns: MutableMap<String, CooldownState> = HashMap()

    /** Registered backends in the active order. */
    fun orderedBackends(): List<TranslationBackend> {
        val all = backends
        val byId = all.associateBy { it.id }
        val defaultOrder = all.sortedWith(compareBy({ it.priority }, { it.id }))
        val override = orderOverride ?: return defaultOrder
        val seen = HashSet<String>()
        val out = ArrayList<TranslationBackend>(all.size)
        for (id in override) {
            val backend = byId[id] ?: continue
            if (seen.add(id)) out.add(backend)
        }
        for (backend in defaultOrder) if (seen.add(backend.id)) out.add(backend)
        return out
    }

    /** Replace the rotation, e.g. after the user adds or deletes a service. */
    fun setBackends(newBackends: List<TranslationBackend>) {
        backends = newBackends
        // Drop cooldowns for backends that no longer exist, so a re-added
        // service does not come back pre-cooled.
        cooldowns.keys.retainAll(newBackends.map { it.id }.toSet())
    }

    /** User-supplied order; null restores the default. */
    fun setOrder(orderedIds: List<String>?) {
        orderOverride = orderedIds
        cache.reconcileRoutingIdentity(routingIdentity())
    }

    fun cooldownFor(backendId: String): CooldownState = cooldowns.getOrPut(backendId) { CooldownState() }

    /**
     * The backend the waterfall would engage first for the pair: the first
     * usable, non-floor backend that is not cooling down. Null when the user has
     * configured nothing that can serve it — which is *not* an error, it is the
     * input to [TranslationResult.NoOfflineModel].
     */
    fun preferredBackend(source: String, target: String): TranslationBackend? {
        val now = clock()
        return orderedBackends().firstOrNull { backend ->
            backend.kind != BackendKind.OFFLINE_BERGAMOT && backend.kind != BackendKind.OFFLINE_ONNX &&
                backend.supports(source, target) &&
                !cooldownFor(backend.id).isCoolingDown(now)
        }
    }

    /**
     * The cache's routing identity: the preferred backend plus the pair, so a
     * change of preferred backend invalidates stale entries. Upstream folds two
     * user-facing knobs into this string as well (the LLM-context bypass and the
     * Bergamot toggle); both still exist on the desktop, and both are read from
     * the same place, so the identity function keeps its shape.
     */
    fun routingIdentity(source: String = "", target: String = ""): String {
        val preferred = if (source.isNotEmpty() && target.isNotEmpty()) {
            preferredBackend(source, target)?.id ?: "none"
        } else {
            orderedBackends().firstOrNull {
                it.kind == BackendKind.ONLINE || it.kind == BackendKind.LOCAL_SERVER
            }?.id ?: "none"
        }
        return preferred
    }

    fun preferredOnlineId(source: String, target: String): String = routingIdentity(source, target)

    /**
     * Run the waterfall for one string.
     *
     * Never throws for "no backend could serve this" — that is
     * [TranslationResult.NoOfflineModel]. Throws only for a programming error
     * (no backends registered at all), matching upstream's stance that an empty
     * registry is a wiring bug rather than a user state.
     */
    fun translate(call: TranslationCall): TranslationResult {
        val ordered = orderedBackends()
        if (ordered.isEmpty()) {
            throw IllegalStateException("Waterfall has no backends — was it wired up?")
        }

        // Cache first, keyed on the pair so a cached JA->EN gloss can never be
        // served for JA->ES (upstream's TranslationCache doc, kept verbatim).
        val identity = routingIdentity(call.source, call.target)
        cache.reconcileRoutingIdentity(identity)
        cache.get(call.text, call.source, call.target)?.let { hit ->
            return TranslationResult.Translated(hit.text, hit.backendId, ResultOrigin.CACHE)
        }

        val trail = ArrayList<String>(ordered.size)
        val failures = ArrayList<BackendFailure>(ordered.size)
        var eventful = false

        for (backend in ordered) {
            if (call.abort()) return TranslationResult.Aborted
            if (!backend.supports(call.source, call.target)) continue

            val now = clock()
            val cool = (backend as? Cooldownable)?.unavailableUntil()
            if (cool != null && cool > now) {
                trail += "${backend.displayName}[cooldown ${(cool - now) / 1000}s]"
                eventful = true
                continue
            }

            val attemptStartedAt = clock()
            try {
                val translated = backend.translate(call)
                (backend as? Cooldownable)?.recordSuccess(attemptStartedAt)
                trail += "${backend.displayName}[ok]"
                val origin = originFor(backend)
                if (origin.isCacheable) {
                    cache.put(call.text, call.source, call.target, translated, backend.id)
                }
                if (origin.isDegraded && eventful) onTrail("waterfall(single): ${trail.joinToString(" -> ")}")
                return TranslationResult.Translated(translated, backend.id, origin)
            } catch (failure: TranslationFailure) {
                trail += "${backend.displayName}[${failure.cooldownCause}]"
                eventful = true
                val recorded = cooldownFor(backend.id).recordFailure(failure, attemptStartedAt)
                failures += BackendFailure(
                    backendId = backend.id,
                    kind = backend.kind,
                    cause = failure.cooldownCause,
                    message = failure.message ?: failure.cooldownCause.name,
                    retryAtMs = if (recorded) cooldownFor(backend.id).unavailableUntil() else null,
                )
            }
        }

        if (eventful) onTrail("waterfall(single): ${trail.joinToString(" -> ")}")

        // Distinguish the two ends of the road. "Everything I have failed" is
        // actionable (fix a key, restart the server); "nothing I have covers
        // this pair" is a capability statement. Upstream could not tell them
        // apart because its floor never failed.
        val anyCovered = ordered.any { it.supports(call.source, call.target) }
        return if (anyCovered && failures.isNotEmpty()) {
            TranslationResult.AllFailed(failures)
        } else {
            TranslationResult.NoOfflineModel(call.source, call.target)
        }
    }

    /** Called when the machine regains a network. @return ids whose cooldown was cleared. */
    fun onConnectivityRestored(): List<String> {
        val cleared = mutableListOf<String>()
        for (backend in orderedBackends()) {
            val cool = backend as? Cooldownable ?: continue
            if (cool.onConnectivityRestored()) cleared += backend.id
        }
        return cleared
    }

    /** The user pressed "try again" for one backend. */
    fun resetCooldown(backendId: String) {
        cooldowns[backendId]?.resetCooldown()
        cache.reconcileRoutingIdentity(routingIdentity())
    }

    /**
     * Snapshot for the settings page and the tray status light. `preferred` is
     * the same computation the waterfall performs, so what the UI shows cannot
     * drift from what the rotation does.
     */
    fun status(source: String, target: String): List<BackendStatus> {
        val now = clock()
        val preferred = preferredBackend(source, target)?.id
        return orderedBackends().map { backend ->
            val cool = cooldownFor(backend.id)
            BackendStatus(
                id = backend.id,
                displayName = backend.displayName,
                kind = backend.kind,
                supportsPair = backend.supports(source, target),
                isPreferred = backend.id == preferred,
                coolingDownUntilMs = cool.unavailableUntil()?.takeIf { it > now },
                cooldownCause = cool.unavailableCause(),
                cooldownDescription = cool.unavailableDescription(),
            )
        }
    }

    private fun originFor(backend: TranslationBackend): ResultOrigin = when (backend.kind) {
        BackendKind.ONLINE -> ResultOrigin.ONLINE_SERVICE
        BackendKind.LOCAL_SERVER -> ResultOrigin.LOCAL_LLM_SERVER
        BackendKind.OFFLINE_BERGAMOT -> ResultOrigin.OFFLINE_BERGAMOT
        BackendKind.OFFLINE_ONNX -> ResultOrigin.OFFLINE_ONNX
    }
}

/** One row of the services page / tray status. */
data class BackendStatus(
    val id: String,
    val displayName: String,
    val kind: BackendKind,
    val supportsPair: Boolean,
    val isPreferred: Boolean,
    val coolingDownUntilMs: Long?,
    val cooldownCause: CooldownCause?,
    val cooldownDescription: String?,
)

/**
 * LRU translation cache. Ported from upstream `TranslationCache.kt`.
 *
 * Key is the `(text, source, target)` triple, so cross-pair stale reads are
 * impossible by construction. The **routing identity** — the preferred
 * backend's id — is tracked separately and any change clears the cache, so
 * translations produced under the old routing are not shadowed into the new
 * one.
 *
 * One addition the desktop needs: a cached entry records the [ResultOrigin] of
 * the backend that produced it, because the tray status light and the "why is
 * this translation different from yesterday" question both need it. Upstream
 * stored the display name; the port stores the id plus the origin.
 */
class TranslationCache(private val capacity: Int = 500) {

    data class Key(val text: String, val source: String, val target: String)

    data class Entry(val text: String, val backendId: String)

    private val lru = object : LinkedHashMap<Key, Entry>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean = size > capacity
    }

    private var lastRoutingIdentity: String? = null

    /**
     * Any change of routing identity clears the cache. Pair changes are not
     * handled here — the key does that job.
     */
    fun reconcileRoutingIdentity(identity: String) {
        if (lastRoutingIdentity == null) {
            lastRoutingIdentity = identity
            return
        }
        if (lastRoutingIdentity != identity) {
            lru.clear()
            lastRoutingIdentity = identity
        }
    }

    fun get(text: String, source: String, target: String): Entry? = lru[Key(text, source, target)]

    fun put(text: String, source: String, target: String, translated: String, backendId: String) {
        lru[Key(text, source, target)] = Entry(translated, backendId)
    }

    fun clear() {
        lru.clear()
    }

    val size: Int get() = lru.size

    val routingIdentity: String? get() = lastRoutingIdentity
}
