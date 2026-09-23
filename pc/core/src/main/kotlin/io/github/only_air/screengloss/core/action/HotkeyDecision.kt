package io.github.only_air.screengloss.core.action

/**
 * Port of upstream `app/src/main/java/com/playtranslate/HotkeyDecision.kt`
 * (413 lines), which PORTING.md §5.7 calls "本次移植里性价比最高的一块" — the
 * highest-value reuse in the whole port. It is ported, not rewritten: the
 * state machine below is upstream's, with three mechanical changes.
 *
 * **What changed, and why it is only mechanical:**
 *
 *  1. `HotkeyAssignment` (a 5-value enum tied to `OverlayMode`) → [Action].
 *     Upstream's enum is the *mode* dimension (`TRANSLATION_HOLD`,
 *     `FURIGANA_TAP`, …) because the phone's overlay only had two modes. The
 *     desktop has twelve actions, so the mode is now a property of the action
 *     and the identity is the action itself.
 *  2. `Set<Int>` of Android keycodes → `Set<InputToken>`. Needed for mouse
 *     side buttons (PORTING §5.7, new capability 2), and because the Android
 *     `KeyEvent`/`InputDevice` constants do not exist off-device. Note that
 *     PORTING.md §5.7 describes this file as "纯逻辑、已单测、与 Android 无关" —
 *     it is not: the shipped file imports `android.view.InputDevice` and
 *     `android.view.KeyEvent` for its source-mask and modifier constants. The
 *     logic is portable; the *constants* needed replacing. This port is the
 *     proof.
 *  3. `InputDevice.SOURCE_*` bitmask policy → [HotkeyDevicePolicy], because on
 *     the desktop "which device did this come from" is answered by Raw Input /
 *     CGEventTap / XInput2 device classes, not by an Android source mask.
 *
 * **What did not change** — and this is the part worth protecting:
 *
 *  - the shadow window (a combo that is a proper subset of another is deferred,
 *    everything else fires at zero latency);
 *  - the "never upgrade a combo mid-hold" rule;
 *  - the pending-HOLD-is-swallowed / pending-TAP-still-fires asymmetry;
 *  - instant-hold-tap-on-quick-release;
 *  - the `reachable` gate that keeps state from latching across a gate closure.
 *
 * Upstream's 617-line `HotkeyDecisionTest` is the regression suite for this
 * file; the ported cases are in `core/src/test/.../HotkeyDecisionCheck.kt`.
 */

/** How a binding is triggered. */
enum class HotkeyTrigger { HOLD, TAP }

/** A configured combo: a chord bound to an [Action]. */
data class HotkeyCombo(
    val chord: Chord,
    val action: Action,
) {
    val keys: Set<InputToken> get() = chord.tokens
    val trigger: HotkeyTrigger get() = if (action.trigger == Action.Trigger.HOLD) HotkeyTrigger.HOLD else HotkeyTrigger.TAP
}

/**
 * Assemble the live combo list from the user's bindings, dropping unset
 * (empty) ones.
 *
 * When [hasReadingHint] is false, actions marked [Action.requiresReadingHint]
 * are excluded entirely. Upstream's reason carries over verbatim: a source
 * language with no hint layer hides those rows on the Hotkeys page, so a stale
 * binding left over from a previous language must not fire invisibly — and the
 * TAP variant is the worse of the two, because it would silently start an auto
 * session.
 *
 * Holds are listed before taps so a chord bound to both resolves to the hold in
 * [decideHotkeyAction] (`maxByOrNull` returns the first of equal-size matches),
 * preserving instant-hold + tap-on-release.
 */
fun buildHotkeyCombos(bindings: BindingSet, hasReadingHint: Boolean): List<HotkeyCombo> =
    bindings.bindings
        .filter { it.enabled }
        .filter { hasReadingHint || !it.action.requiresReadingHint }
        .filterNot { it.chord.isEmpty }
        .sortedBy { if (it.action.trigger == Action.Trigger.HOLD) 0 else 1 }
        .map { HotkeyCombo(it.chord, it.action) }

/** Snapshot of mutable hotkey state, used as input to [decideHotkeyAction]. */
data class HotkeyState(
    val active: Action?,
    val pending: Action?,
) {
    companion object {
        val IDLE = HotkeyState(active = null, pending = null)
    }
}

/** Action the caller should apply after calling [decideHotkeyAction]. */
sealed class HotkeyAction {
    /** No state transition — nothing to do. */
    object NoChange : HotkeyAction()

    /** Activate [action] immediately. Any pending activation should be cancelled. */
    data class ActivateNow(val action: Action) : HotkeyAction()

    /**
     * Defer activation of [action] by the combo window. Any prior pending
     * activation should be cancelled and a new deferred activation scheduled.
     */
    data class DeferActivation(val action: Action) : HotkeyAction()

    /** The currently active combo was released. Fire release and clear state. */
    object Release : HotkeyAction()

    /** The pending combo was released before the window expired. Clear pending. */
    object ClearPending : HotkeyAction()
}

/**
 * Decide what action the hotkey state machine should take given the current set
 * of held tokens, current state, and configured combos.
 *
 * Design notes (unchanged from upstream):
 *  - Active combos are not upgraded to larger combos while held. Once `A+B` has
 *    activated, pressing an extra `C` — even if `A+B+C` is configured — does
 *    not swap actions; the user must release and re-press.
 *  - A "shadowed" combo (proper subset of another configured combo) is always
 *    deferred. Non-shadowed combos fire immediately.
 *  - When a combo is pending and a larger combo subsequently becomes fully
 *    held, the pending combo is superseded by the larger one, which is itself
 *    either activated immediately or re-deferred depending on whether it is
 *    also shadowed.
 *  - If [reachable] is false, new activations are suppressed. Release of an
 *    already-active combo still flows through, so state cannot latch across a
 *    gate closure.
 */
fun decideHotkeyAction(
    held: Set<InputToken>,
    state: HotkeyState,
    combos: List<HotkeyCombo>,
    reachable: Boolean = true,
): HotkeyAction {
    // 0. Gate: if the user can't see the app and no combo is currently active,
    //    ignore new presses. A pending activation must be cleared so the
    //    deferred runnable does not fire into empty space. An already-active
    //    combo is allowed to flow through so it can release cleanly.
    if (!reachable && state.active == null) {
        return if (state.pending != null) HotkeyAction.ClearPending else HotkeyAction.NoChange
    }

    // 1. If a combo is already active, only check whether it is still held.
    //    Deliberately no mid-hold upgrade to a larger combo.
    state.active?.let { active ->
        val activeCombo = combos.firstOrNull { it.action == active }
        return if (activeCombo == null || !held.containsAll(activeCombo.keys)) {
            HotkeyAction.Release
        } else {
            HotkeyAction.NoChange
        }
    }

    // 2. Find the longest configured combo currently satisfied by held keys.
    val best = combos
        .filter { it.keys.isNotEmpty() && held.containsAll(it.keys) }
        .maxByOrNull { it.keys.size }

    // 3. If a combo is pending, either keep waiting, supersede it with a larger
    //    match, or clear it if the user released.
    state.pending?.let { pending ->
        if (best == null) {
            // Released before the window expired. A HOLD is swallowed (a brief
            // subset press must not flash a preview); a TAP is exactly a quick
            // tap of a shadowed combo and must still fire its toggle.
            return if (pending.trigger == Action.Trigger.TAP) {
                HotkeyAction.ActivateNow(pending)
            } else {
                HotkeyAction.ClearPending
            }
        }
        if (best.action == pending) {
            // Same pending combo is still the best match. Let the timer tick.
            return HotkeyAction.NoChange
        }
        // A larger combo is now matched. Supersede the pending one.
        return if (isShadowed(best, combos)) {
            HotkeyAction.DeferActivation(best.action)
        } else {
            HotkeyAction.ActivateNow(best.action)
        }
    }

    // 4. No active, no pending. If a combo now matches, activate or defer.
    if (best == null) return HotkeyAction.NoChange
    return if (isShadowed(best, combos)) {
        HotkeyAction.DeferActivation(best.action)
    } else {
        HotkeyAction.ActivateNow(best.action)
    }
}

/**
 * True if [combo] is a proper subset of any other combo in [all]. A shadowed
 * combo cannot fire immediately: we must wait for the detection window to see
 * whether the user is building toward its superset. Self-comparison is harmless
 * — a set cannot be strictly larger than itself.
 */
private fun isShadowed(combo: HotkeyCombo, all: List<HotkeyCombo>): Boolean =
    all.any { other -> other.keys.size > combo.keys.size && other.keys.containsAll(combo.keys) }

/**
 * "Instant hold, tap on quick release" — lets the user bind the *same* chord to
 * both a HOLD and a TAP action.
 *
 * Upstream shipped this for one reason: the phone had one gesture surface, so
 * "hold to preview, tap to toggle" had to share it. The desktop keeps it for a
 * different reason — the defaults put a mouse side button on both
 * `LOOKUP_WORDS_LATCH` (HOLD) and `TRANSLATE_REGION` (TAP), and a user who
 * rebinds both to one button should not have to relearn which of the two fires.
 *
 * @param releasedHold the action whose hold preview just ended.
 * @param heldDurationMs how long that hold was active before release.
 * @param thresholdMs the longest press that still counts as a tap.
 * @param combos the currently-configured combos (empty chords filtered out).
 * @return the TAP action to fire, or null to leave it a pure hold.
 */
fun tapOnQuickRelease(
    releasedHold: Action,
    heldDurationMs: Long,
    thresholdMs: Long,
    combos: List<HotkeyCombo>,
): Action? {
    if (releasedHold.trigger != Action.Trigger.HOLD) return null
    if (heldDurationMs >= thresholdMs) return null
    val holdChord = combos.firstOrNull { it.action == releasedHold }?.chord
    if (holdChord == null || holdChord.isEmpty) return null
    return combos.firstOrNull {
        it.trigger == HotkeyTrigger.TAP && it.chord == holdChord
    }?.action
}

/**
 * Whether a just-activated combo should be tracked as the "active" action so a
 * later key-up releases it. True only while its keys are still held.
 *
 * A tap fired on quick-release of a shadowed combo (its keys already up) has
 * nothing left to release; latching it would make an immediate re-tap of the
 * same key look like the combo is "still active" and get swallowed.
 */
fun shouldLatchActive(activatedKeys: Set<InputToken>, heldKeys: Set<InputToken>): Boolean =
    activatedKeys.isNotEmpty() && heldKeys.containsAll(activatedKeys)

/**
 * Whether binding [held] would take a key away from typing — true when the
 * chord includes a key that types ([typingKeys], the subset the caller found to
 * produce a character) and carries no command modifier to distinguish it.
 *
 * Advisory only. The user may bind it anyway; this decides whether to say so.
 * On the desktop the warning matters *more* than on Android, not less: the game
 * has its own key bindings, so a bare letter stolen by the translator is a
 * stolen game control (PORTING §5.7, "加强为三档" — see [ConflictDetector]).
 */
fun comboTakesTypingKey(held: Set<InputToken>, typingKeys: Set<InputToken>): Boolean =
    typingKeys.isNotEmpty() && held.none { it.isCommandModifier } && held.any { it in typingKeys }

/**
 * The desktop replacement for upstream's `isHotkeySource` / `isKeyboardSource`
 * / `isGameInputSource` trio.
 *
 * Upstream answers one question — "may events from this device drive a hotkey?"
 * — from an Android `InputDevice` source mask, and answers a second question —
 * "is the player actively working the controls?" — from the same mask, held to
 * gamepad sources so that typing does not tear down live overlays.
 *
 * The desktop splits these differently, and the split is the interesting part:
 *
 *  - **Eligibility is not a policy decision any more.** Raw Input /
 *    CGEventTap / XInput2 deliver everything; the app chooses what to look at.
 *    So the policy collapses to "is this device class one we route".
 *  - **The gameplay signal is gone.** A phone knows a button press is gameplay
 *    because it comes from a gamepad source. A PC cannot tell "W is walk
 *    forward" from "W is a word". Upstream's rule — suppress presentation while
 *    a control is held — therefore has no desktop equivalent, and the port must
 *    not invent one: guessing would mean the overlay flickering on every
 *    keystroke of a game's chat box. Instead the *user* declares the game's
 *    keys (see [ConflictDetector]'s game-source set), and everything else is
 *    treated as a command.
 */
object HotkeyDevicePolicy {

    /** Device classes the router accepts events from. */
    enum class DeviceClass { KEYBOARD, MOUSE, GAMEPAD, UNKNOWN }

    /**
     * Whether a chord built from [tokens] may be registered at all.
     *
     * Rejected: an empty chord, and a chord that is nothing but modifiers —
     * upstream's `IconGestureBindings` had the same shape of rule (a binding
     * must name something), and a modifiers-only chord would fire on every
     * Ctrl press in the game.
     */
    fun isRegistrable(chord: Chord): Boolean =
        !chord.isEmpty && chord.tokens.any { !it.isCommandModifier }

    /**
     * Whether a chord is safe to register on a platform that cannot deliver
     * key-up events (Wayland's `GlobalShortcuts` portal — PORTING §5.7).
     * HOLD and any chord used by a HOLD action degrade to a toggle there; this
     * answers the narrower question of whether the *chord* still works.
     */
    fun survivesWithoutKeyUp(chord: Chord): Boolean = !chord.isEmpty
}
