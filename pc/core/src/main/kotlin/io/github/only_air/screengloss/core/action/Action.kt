package io.github.only_air.screengloss.core.action

/**
 * The action registry — the single source of truth PORTING.md §5.7 asks for.
 *
 * Upstream's problem is that a *gesture* was the unit: `IconAction` is three
 * enums (`DragAction`, `HoldAction`, `TapAction`), each a candidate list, and
 * the floating icon's dispatch is an exhaustive `when` per gesture. On the
 * desktop there is no gesture layer at all — a key has a down state and an up
 * state, so the gesture abstraction is pure overhead. The port keeps the
 * *actions* and drops the gestures:
 *
 * ```
 * upstream                                 PC
 * ─────────────────────────────────────────────────────────────
 * IconAction (drag/hold/tap)   ──►   Action (this registry, one truth)
 * IconGestureBindings          ──►   Binding (one action -> 0..n chords)
 * OverlayUiController dispatch ──►   HotkeyRouter (one chord -> one action)
 * ```
 *
 * Two upstream properties are preserved deliberately, because they are what
 * keeps the surface honest:
 *
 *  1. **An action that nothing can reach is a bug, not a preference.** Upstream
 *     models this as `IconGestureBindings.quickMenuReachable`. §5.8 hardens it
 *     for the desktop (tray off + no hotkey ⇒ refuse to save), implemented in
 *     [ReachabilityCheck].
 *  2. **The tray, the floating icon and the hotkeys all dispatch the same
 *     enum.** One registry, several trigger surfaces.
 */
enum class Action(
    /** Stable id, used in prefs and over the web bridge. Never rename. */
    val id: String,
    val group: Group,
    /** What the action does to the pointer while it is in force. See PORTING §5.5. */
    val pointerPolicy: PointerPolicy,
    /** Default trigger semantics; a user may rebind the chord but not the kind. */
    val trigger: Trigger,
    val defaultChords: List<Chord>,
    /** True when the action is only meaningful for a language with a reading-hint layer. */
    val requiresReadingHint: Boolean = false,
) {
    // ── Lookup ────────────────────────────────────────────────────────────
    /**
     * Enter/leave the lookup latch (input state 1). Upstream source:
     * `DragAction.LOOKUP_WORDS` — the magnifier drag. The lens itself is gone
     * (PORTING §5.3 item 3); the hit test and dictionary chain are reused.
     */
    LOOKUP_WORDS_LATCH(
        id = "lookup.words_latch",
        group = Group.LOOKUP,
        pointerPolicy = PointerPolicy.TAKES_POINTER,
        trigger = Trigger.HOLD,
        defaultChords = listOf(Chord.ctrlAlt(Keys.Q), Chord.single(Chord.mouse(MouseButtons.SIDE_1))),
    ),

    /**
     * Inline hover lookup (input state 0). New on PC: the pointer's resting
     * position is a free continuous sample, so the lookup needs no gesture at
     * all. Unavailable on Wayland, where there is no global pointer position.
     */
    TOGGLE_HOVER_LOOKUP(
        id = "lookup.hover_toggle",
        group = Group.LOOKUP,
        pointerPolicy = PointerPolicy.OBSERVE_ONLY,
        trigger = Trigger.TAP,
        defaultChords = listOf(Chord.ctrlAlt(Keys.V)),
    ),

    // ── Capture & translate ───────────────────────────────────────────────
    /** Upstream: `TapAction.CAPTURE_SCREEN` (the icon's one-tap capture). */
    TRANSLATE_REGION(
        id = "capture.translate_region",
        group = Group.CAPTURE,
        pointerPolicy = PointerPolicy.OBSERVE_ONLY,
        trigger = Trigger.TAP,
        defaultChords = listOf(Chord.ctrlAlt(Keys.W), Chord.single(Chord.mouse(MouseButtons.SIDE_2))),
    ),

    /** Enter input state 2 (rubber-band region select). Upstream: `RegionPickerSheet`. */
    PICK_REGION(
        id = "capture.pick_region",
        group = Group.CAPTURE,
        pointerPolicy = PointerPolicy.TAKES_POINTER,
        trigger = Trigger.TAP,
        defaultChords = listOf(Chord.ctrlAlt(Keys.R)),
    ),

    /** Upstream: the quick menu's Auto entry; `ReconcilerLiveMode`. */
    TOGGLE_AUTO_TRANSLATE(
        id = "translate.auto_toggle",
        group = Group.TRANSLATE,
        pointerPolicy = PointerPolicy.OBSERVE_ONLY,
        trigger = Trigger.TAP,
        defaultChords = listOf(Chord.ctrlAlt(Keys.E)),
    ),

    /** New on PC: the clipboard is a first-class text source. */
    TRANSLATE_CLIPBOARD(
        id = "translate.clipboard",
        group = Group.TRANSLATE,
        pointerPolicy = PointerPolicy.OBSERVE_ONLY,
        trigger = Trigger.TAP,
        defaultChords = listOf(Chord.ctrlAlt(Keys.C)),
    ),

    // ── Voice & cards ─────────────────────────────────────────────────────
    /** Upstream: `LensSpeakChip`. */
    SPEAK_LAST(
        id = "voice.speak_last",
        group = Group.VOICE,
        pointerPolicy = PointerPolicy.OBSERVE_ONLY,
        trigger = Trigger.TAP,
        defaultChords = listOf(Chord.ctrlAlt(Keys.S)),
    ),

    /** Upstream: the Anki chip; `AnkiSendPipeline`. */
    SEND_ANKI(
        id = "anki.send_last",
        group = Group.ANKI,
        pointerPolicy = PointerPolicy.OBSERVE_ONLY,
        trigger = Trigger.TAP,
        defaultChords = listOf(Chord.ctrlAlt(Keys.A)),
    ),

    // ── Windows & surfaces ────────────────────────────────────────────────
    /** Upstream: quick menu → App. Opens the panel / workspace. */
    OPEN_WORKSPACE(
        id = "window.open_workspace",
        group = Group.WINDOW,
        pointerPolicy = PointerPolicy.OBSERVE_ONLY,
        trigger = Trigger.TAP,
        defaultChords = listOf(Chord.ctrlAlt(Keys.D)),
    ),

    /**
     * Summon the on-screen quick menu. Has **no default chord** on purpose:
     * §5.8 says the tray must not be the only way in, so this action plus
     * [OPEN_WORKSPACE] are the two keyboard routes, and
     * [ReachabilityCheck] enforces that at least one of them (or the tray)
     * is actually reachable. Assigning a default here would paper over a
     * configuration that has no way in.
     */
    OPEN_QUICK_MENU(
        id = "window.quick_menu",
        group = Group.WINDOW,
        pointerPolicy = PointerPolicy.TAKES_POINTER,
        trigger = Trigger.TAP,
        defaultChords = emptyList(),
    ),

    /** Upstream: quick menu → Hide. */
    TOGGLE_OVERLAY(
        id = "window.toggle_overlay",
        group = Group.WINDOW,
        pointerPolicy = PointerPolicy.OBSERVE_ONLY,
        trigger = Trigger.TAP,
        defaultChords = listOf(Chord.ctrlAlt(Keys.H)),
    ),

    // ── System ────────────────────────────────────────────────────────────
    /**
     * New on PC, and the most important entry in the table. Drops every
     * latched state, hides every overlay, restores the pointer. §5.5's whole
     * point is that taking the pointer must be one keypress away from
     * undoable; this is that keypress.
     */
    PANIC_EXIT(
        id = "system.panic",
        group = Group.SYSTEM,
        pointerPolicy = PointerPolicy.OBSERVE_ONLY,
        trigger = Trigger.TAP,
        defaultChords = listOf(Chord.ctrlAltShift(Keys.X)),
    );

    enum class Group { LOOKUP, CAPTURE, TRANSLATE, VOICE, ANKI, WINDOW, SYSTEM }

    enum class Trigger { TAP, HOLD, CHORD, MOUSE }

    /**
     * Who owns the pointer while this action is in force (§5.5).
     *
     * The invariant the port must never break: a `TAKES_POINTER` action is
     * only ever entered by an explicit user act, is visually unmistakable,
     * and exits on one key. Upstream could rely on gesture semantics; here it
     * has to be stated, because "my game stopped aiming" is a far worse
     * failure than "I pressed the hotkey twice".
     */
    enum class PointerPolicy {
        /** Game keeps the pointer. The app only reads the cursor position. */
        OBSERVE_ONLY,

        /** The app takes the pointer; the game cannot aim until the user exits. */
        TAKES_POINTER,
    }

    companion object {
        private val byId: Map<String, Action> = entries.associateBy { it.id }

        fun fromId(id: String): Action? = byId[id]

        /** Every action that can take the pointer — the set `PANIC_EXIT` must cover. */
        val pointerGrabbing: List<Action> get() = entries.filter { it.pointerPolicy == PointerPolicy.TAKES_POINTER }

        /** The default binding set, as it ships. */
        fun defaultBindings(): List<Binding> = entries.flatMap { action ->
            action.defaultChords.map { Binding(action, it) }
        }
    }
}

/** One action bound to one chord. */
data class Binding(
    val action: Action,
    val chord: Chord,
    /**
     * Per-application profile scope. `null` = global. When set, the binding
     * only fires while the foreground window matches (PORTING §5.7, new PC
     * capability 1) — the game gets `Ctrl+Alt+*`, the desktop gets something
     * more comfortable.
     */
    val scope: WindowScope? = null,
    val enabled: Boolean = true,
)

/**
 * A foreground-window matcher. Upstream has nothing like it; it exists because
 * on a desktop the same key means different things in different applications,
 * and because the *conflict* set differs per application too.
 */
data class WindowScope(
    /** Executable name, e.g. `game.exe`, `ffxiv_dx11.exe`. Case-insensitive. */
    val process: String? = null,
    /** Window-title regex, matched with [Regex] semantics. */
    val titlePattern: String? = null,
) {
    fun matches(processName: String?, windowTitle: String?): Boolean {
        if (process != null) {
            if (processName == null) return false
            if (!processName.equals(process, ignoreCase = true)) return false
        }
        val pattern = titlePattern
        if (pattern != null) {
            if (windowTitle == null) return false
            val ok = runCatching { Regex(pattern).containsMatchIn(windowTitle) }.getOrDefault(false)
            if (!ok) return false
        }
        return true
    }

    companion object {
        val ANY = WindowScope()
    }
}

/** The complete, user-editable binding set. */
data class BindingSet(val bindings: List<Binding>) {

    /** Chords that may currently fire, in the priority order the router uses. */
    fun activeChords(foregroundProcess: String?, foregroundTitle: String?): List<Chord> =
        bindings.asSequence()
            .filter { it.enabled }
            .filter { it.scope == null || it.scope.matches(foregroundProcess, foregroundTitle) }
            .map { it.chord }
            .filterNot { it.isEmpty }
            .toList()

    fun forAction(action: Action, foregroundProcess: String? = null, foregroundTitle: String? = null): List<Binding> =
        bindings.filter {
            it.action == action && (it.scope == null || it.scope.matches(foregroundProcess, foregroundTitle))
        }

    fun chordsFor(action: Action): List<Chord> = bindings.filter { it.action == action }.map { it.chord }

    fun with(binding: Binding): BindingSet = BindingSet(bindings + binding)

    fun without(action: Action): BindingSet = BindingSet(bindings.filterNot { it.action == action })

    companion object {
        val DEFAULT = BindingSet(Action.defaultBindings())
    }
}
