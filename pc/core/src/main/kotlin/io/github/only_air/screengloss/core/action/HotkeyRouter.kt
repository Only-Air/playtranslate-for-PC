package io.github.only_air.screengloss.core.action

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The dispatcher: one binding → one action, and the only place the shadow
 * window's timer lives.
 *
 * Upstream's equivalent is `OverlayUiController`'s gesture dispatch plus the
 * deferred runnable inside `PlayTranslateAccessibilityService`. Both are
 * Android-bound; the state machine they drive is not, so the port keeps the
 * machine ([decideHotkeyAction]) and rebuilds the plumbing around a
 * [Scheduler] interface. That interface is not architectural decoration — it is
 * what lets the 617-line upstream hotkey test suite be ported at all, because
 * the deferred-activation cases need to fire the timer deterministically
 * instead of sleeping.
 *
 * Two responsibilities that are easy to get wrong and are therefore stated:
 *
 *  - **The router never swallows a key it did not bind.** PORTING §5.7: "不吞游戏
 *    正在使用的按键（只读不吞，除非用户显式要求）". [onKeyDown] returns whether the
 *    event was consumed, and it returns `false` for every chord that is not
 *    registered, so the platform layer can pass it on.
 *  - **Esc always exits.** A latched state is entered by an explicit act and
 *    must be exitable without knowing which act it was. Upstream had no
 *    equivalent because the phone's gesture ended on finger-lift; on the
 *    desktop a HOLD action whose key-up was lost would otherwise latch
 *    forever, and the user would be left with a game they cannot aim in.
 */
class HotkeyRouter(
    private val scheduler: Scheduler,
    private val onActivate: (Action) -> Unit,
    private val onRelease: (Action) -> Unit,
    /** How long a shadowed chord waits for its superset. Upstream's window. */
    var comboWindowMs: Long = 120,
    /** The longest press that still counts as a tap, for instant-hold/tap-on-release. */
    var tapThresholdMs: Long = 250,
) {

    private var bindings: BindingSet = BindingSet.DEFAULT
    private var foregroundProcess: String? = null
    private var foregroundTitle: String? = null
    private var hasReadingHint: Boolean = true
    private var reachable: Boolean = true

    private var held: Set<InputToken> = emptySet()
    private var state: HotkeyState = HotkeyState.IDLE
    private var activeSinceMs: Long = 0L
    private var pendingFuture: Cancellable? = null

    /** Fired whenever the pointer-owning action changes, so the shell can draw the cue (§5.5). */
    var onPointerOwnerChanged: (Action?) -> Unit = {}

    /** The action currently in force, if any. */
    val activeAction: Action? get() = state.active

    /** The action holding the pointer right now, or null when the game has it. */
    val pointerOwner: Action?
        get() = state.active?.takeIf { it.pointerPolicy == Action.PointerPolicy.TAKES_POINTER }

    fun updateBindings(newBindings: BindingSet) {
        bindings = newBindings
        // A rebind while latched must not leave the old action stuck on.
        if (state.active != null) releaseActive(fireTapOnRelease = false)
    }

    fun setForegroundWindow(process: String?, title: String?) {
        foregroundProcess = process
        foregroundTitle = title
    }

    fun setHasReadingHint(value: Boolean) {
        hasReadingHint = value
    }

    /**
     * Upstream's `reachable` gate: "the user has no visible indication the app
     * is listening — icon hidden and app backgrounded". On the desktop the
     * equivalent is "no overlay visible and no tray state light", which the
     * shell computes.
     */
    fun setReachable(value: Boolean) {
        reachable = value
        evaluate()
    }

    /** Chord list currently live, for the settings page and for tests. */
    fun liveCombos(): List<HotkeyCombo> = buildHotkeyCombos(bindings, hasReadingHint)

    /**
     * @return true when the event was consumed by a registered chord.
     */
    fun onKeyDown(token: InputToken): Boolean {
        // Esc is the universal exit, before anything else looks at it.
        if (token is KeyToken && token.code == Keys.ESCAPE && state.active != null) {
            releaseActive(fireTapOnRelease = false)
            return true
        }
        val next = held + token
        if (next == held) return false
        val before = state
        held = next
        val consumed = evaluate()
        // Report consumption when we either changed state or are sitting inside
        // a registered chord (a partially-held chord must stay ours, or the
        // second key of `Ctrl+Alt+Q` would reach the game).
        return consumed || state != before || isChordPrefix(held)
    }

    fun onKeyUp(token: InputToken): Boolean {
        val next = held - token
        if (next == held) return false
        held = next
        val consumed = evaluate()
        return consumed || state.active != null || state.pending != null
    }

    /**
     * Drop every latched state and hide everything. §5.5's `PANIC_EXIT`, and
     * also what the shell calls when the foreground window changes to something
     * we do not own.
     */
    fun panic() {
        pendingFuture?.cancel()
        pendingFuture = null
        if (state.active != null) releaseActive(fireTapOnRelease = false) else state = HotkeyState.IDLE
        held = emptySet()
    }

    /** Called by the shell when the overlay is hidden: nothing may stay latched. */
    fun onOverlayHidden() = panic()

    // ── internals ─────────────────────────────────────────────────────────

    private fun evaluate(): Boolean {
        val combos = buildHotkeyCombos(bindings, hasReadingHint)
        val action = decideHotkeyAction(held, state, combos, reachable)
        return when (action) {
            is HotkeyAction.NoChange -> false
            is HotkeyAction.ActivateNow -> {
                cancelPending()
                activate(action.action)
                true
            }
            is HotkeyAction.DeferActivation -> {
                cancelPending()
                state = HotkeyState(active = null, pending = action.action)
                pendingFuture = scheduler.schedule(comboWindowMs) { commitPending() }
                true
            }
            is HotkeyAction.Release -> {
                releaseActive(fireTapOnRelease = true)
                true
            }
            is HotkeyAction.ClearPending -> {
                cancelPending()
                state = HotkeyState(active = null, pending = null)
                true
            }
        }
    }

    private fun commitPending() {
        val pending = state.pending ?: return
        pendingFuture = null
        activate(pending)
    }

    private fun activate(action: Action) {
        val chord = buildHotkeyCombos(bindings, hasReadingHint).firstOrNull { it.action == action }?.chord
        val keys = chord?.tokens ?: emptySet()
        val previousOwner = pointerOwner
        if (shouldLatchActive(keys, held)) {
            state = HotkeyState(active = action, pending = null)
            activeSinceMs = scheduler.nowMs()
        } else {
            // Fired on the release of a shadowed chord: nothing left to release.
            state = HotkeyState(active = null, pending = null)
            activeSinceMs = 0L
        }
        onActivate(action)
        if (pointerOwner != previousOwner) onPointerOwnerChanged(pointerOwner)
    }

    private fun releaseActive(fireTapOnRelease: Boolean) {
        val active = state.active ?: return
        val previousOwner = pointerOwner
        val heldFor = if (activeSinceMs == 0L) Long.MAX_VALUE else scheduler.nowMs() - activeSinceMs
        state = HotkeyState(active = null, pending = null)
        activeSinceMs = 0L
        // The preview ends first, then the tap fires: the user sees the
        // momentary state drop and the toggled state come up, in that order.
        onRelease(active)
        if (fireTapOnRelease) {
            val tap = tapOnQuickRelease(active, heldFor, tapThresholdMs, buildHotkeyCombos(bindings, hasReadingHint))
            if (tap != null) {
                onActivate(tap)
                if (tap.pointerPolicy == Action.PointerPolicy.TAKES_POINTER) onPointerOwnerChanged(tap)
            }
        }
        if (pointerOwner != previousOwner) onPointerOwnerChanged(pointerOwner)
    }

    private fun cancelPending() {
        pendingFuture?.cancel()
        pendingFuture = null
    }

    /** True when [tokens] is a prefix of some registered chord (see [onKeyDown]). */
    private fun isChordPrefix(tokens: Set<InputToken>): Boolean =
        tokens.isNotEmpty() && buildHotkeyCombos(bindings, hasReadingHint).any { combo ->
            combo.keys.size > tokens.size && combo.keys.containsAll(tokens)
        }

    interface Cancellable {
        fun cancel()
    }

    /**
     * The timer seam. Runtime implementation is a single-threaded scheduled
     * executor (the shadow window is a 120 ms deadline, not a task worth a
     * thread pool); tests substitute a manual one.
     */
    interface Scheduler {
        fun nowMs(): Long
        fun schedule(delayMs: Long, task: () -> Unit): Cancellable
    }

    /** Real scheduler: one daemon thread, monotonic clock. */
    class ExecutorScheduler : Scheduler, AutoCloseable {
        private val executor: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "screengloss-hotkey-timer").apply { isDaemon = true }
            }

        override fun nowMs(): Long = System.nanoTime() / 1_000_000L

        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val future: ScheduledFuture<*> = executor.schedule({ task() }, delayMs, TimeUnit.MILLISECONDS)
            return object : Cancellable {
                override fun cancel() {
                    future.cancel(false)
                }
            }
        }

        override fun close() {
            executor.shutdownNow()
        }
    }

    /**
     * Deterministic scheduler for tests and for the headless self-check: time
     * only moves when [advanceMs] is called, so every shadow-window branch is
     * reachable without sleeping.
     */
    class ManualScheduler : Scheduler {
        private var now = 0L
        private var nextId = 0L
        private class Task(val at: Long, val id: Long, val body: () -> Unit)
        private val tasks = ArrayList<Task>()

        override fun nowMs(): Long = now

        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val entry = Task(now + delayMs, nextId++, task)
            tasks += entry
            return object : Cancellable {
                override fun cancel() {
                    tasks.remove(entry)
                }
            }
        }

        fun advanceMs(delta: Long) {
            now += delta
            while (true) {
                val due = tasks.filter { it.at <= now }.minByOrNull { it.at * 1_000_000 + it.id } ?: break
                tasks.remove(due)
                due.body.invoke()
            }
        }

        val pendingCount: Int get() = tasks.size
    }
}
