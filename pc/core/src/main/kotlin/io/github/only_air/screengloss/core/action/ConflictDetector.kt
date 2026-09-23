package io.github.only_air.screengloss.core.action

/**
 * Conflict detection and reachability, PORTING.md §5.7 ("冲突检测三源" and
 * "加强为三档") and §5.8 (the hardened `quickMenuReachable` rule).
 *
 * Upstream had exactly one conflict check: `comboTakesTypingKey`, a single
 * advisory warning shown in the hotkey setup dialog. It was advisory because
 * the phone had nothing else competing for the key — the app *was* the
 * foreground app, and a stolen letter cost the user a keystroke in a text box
 * they had opened themselves.
 *
 * On the desktop that is no longer true, and the difference is not cosmetic:
 * the same physical key is claimed by three parties at once —
 *
 *   1. **this app** (two actions on one chord),
 *   2. **the OS / desktop environment** (`Win+L` locks the session; on
 *      Wayland the `GlobalShortcuts` portal hands out chords that are then
 *      *owned* by the compositor),
 *   3. **the game** (which has its own binding table, and on which a stolen
 *      key means a lost ability, not a lost keystroke).
 *
 * So the single advisory becomes a three-tier verdict, and — this is the part
 * that has to be enforced rather than documented — one of the tiers *refuses*:
 *
 * | verdict | trigger | behaviour |
 * |---|---|---|
 * | [Verdict.REJECT] | conflicts with an OS-reserved chord | the settings page refuses to save |
 * | [Verdict.REJECT] | two enabled actions share a chord in overlapping scopes | refuses to save |
 * | [Verdict.WARN] | takes a typing key with no command modifier | saves, shows a warning |
 * | [Verdict.HINT] | matches a key the user declared as game-owned | saves, shows a hint |
 *
 * Nothing here is allowed to *silently* accept a broken configuration: every
 * finding carries a machine-readable [Finding.code] so the web panel can
 * localise it and the tests can assert on it.
 */
object ConflictDetector {

    enum class Verdict {
        /** Fine. */
        OK,

        /** Saves, but the user should know. */
        WARN,

        /** Refuse to save: the binding cannot work as written. */
        REJECT,

        /** Saves; informational, because only the user knows their game's keys. */
        HINT,
    }

    /** Which of the three competing parties a finding came from. */
    enum class Source { INTERNAL, OPERATING_SYSTEM, GAME, TYPING }

    data class Finding(
        val verdict: Verdict,
        val source: Source,
        /** Stable code for i18n and tests. Never rename. */
        val code: String,
        /** The offending chord, empty when the finding is not chord-specific. */
        val chord: Chord = Chord.NONE,
        /** Actions involved, for the UI to highlight. */
        val actions: List<Action> = emptyList(),
        /** English fallback message. Localised text comes from the i18n bundle by [code]. */
        val message: String = "",
    ) {
        val isBlocking: Boolean get() = verdict == Verdict.REJECT
    }

    /**
     * Desktop environments, because the reserved set is a property of the
     * environment and not of the OS. This is the one place the port must keep
     * a per-environment table; PORTING §1 lists the five targets.
     */
    enum class DesktopEnvironment { WINDOWS, MACOS, KDE_PLASMA, GNOME, XFCE }

    /**
     * Chords the environment owns. Deliberately *not* exhaustive — an
     * exhaustive list is unmaintainable and would produce false rejections as
     * soon as a user customises their DE. It contains the chords where a
     * collision is certain and harmful, and everything else is left to the
     * HINT tier.
     */
    private fun osReserved(env: DesktopEnvironment): Set<Chord> = when (env) {
        DesktopEnvironment.WINDOWS -> setOf(
            Chord.of(Keys.META_LEFT, Keys.L),      // lock
            Chord.of(Keys.META_LEFT, Keys.D),      // show desktop
            Chord.of(Keys.META_LEFT, Keys.E),      // file explorer
            Chord.of(Keys.META_LEFT, Keys.R),      // run
            Chord.of(Keys.META_LEFT, Keys.TAB),    // task view
            Chord.of(Keys.META_LEFT, Keys.SPACE),  // input-language switch
            Chord.of(Keys.ALT_LEFT, Keys.TAB),
            Chord.of(Keys.ALT_LEFT, Keys.F4),
            Chord.of(Keys.CTRL_LEFT, Keys.SHIFT_LEFT, Keys.ESCAPE),
            Chord.of(Keys.META_LEFT, Keys.SHIFT_LEFT, Keys.S),
            Chord.of(Keys.META_LEFT, Keys.PRINT_SCREEN),
        )
        DesktopEnvironment.MACOS -> setOf(
            Chord.of(Keys.META_LEFT, Keys.Q),      // quit
            Chord.of(Keys.META_LEFT, Keys.W),      // close window
            Chord.of(Keys.META_LEFT, Keys.TAB),
            Chord.of(Keys.META_LEFT, Keys.SPACE),  // Spotlight
            Chord.of(Keys.META_LEFT, Keys.H),      // hide
            Chord.of(Keys.META_LEFT, Keys.M),      // minimise
            Chord.of(Keys.META_LEFT, Keys.SHIFT_LEFT, Keys.NUM_3),
            Chord.of(Keys.META_LEFT, Keys.SHIFT_LEFT, Keys.NUM_4),
            Chord.of(Keys.META_LEFT, Keys.SHIFT_LEFT, Keys.NUM_5),
        )
        DesktopEnvironment.KDE_PLASMA -> setOf(
            Chord.of(Keys.META_LEFT, Keys.D),          // show desktop
            Chord.of(Keys.META_LEFT, Keys.L),          // lock
            Chord.of(Keys.META_LEFT, Keys.P),          // present windows
            Chord.of(Keys.META_LEFT, Keys.ARROW_LEFT), // tile left
            Chord.of(Keys.META_LEFT, Keys.ARROW_RIGHT),
            Chord.of(Keys.CTRL_LEFT, Keys.ALT_LEFT, Keys.T), // Konsole
            Chord.of(Keys.CTRL_LEFT, Keys.ALT_LEFT, Keys.L), // lock
            Chord.of(Keys.ALT_LEFT, Keys.SPACE),             // window menu
            Chord.of(Keys.ALT_LEFT, Keys.F2),                // KRunner
        )
        DesktopEnvironment.GNOME -> setOf(
            Chord.of(Keys.META_LEFT, Keys.D),      // show desktop
            Chord.of(Keys.META_LEFT, Keys.L),      // lock
            Chord.of(Keys.META_LEFT, Keys.SPACE),  // overview / input switch
            Chord.of(Keys.META_LEFT, Keys.ARROW_LEFT),
            Chord.of(Keys.META_LEFT, Keys.ARROW_RIGHT),
            Chord.of(Keys.CTRL_LEFT, Keys.ALT_LEFT, Keys.T), // terminal
            Chord.of(Keys.ALT_LEFT, Keys.F2),                // run dialog
            Chord.of(Keys.PRINT_SCREEN),
        )
        DesktopEnvironment.XFCE -> setOf(
            Chord.of(Keys.META_LEFT, Keys.D),
            Chord.of(Keys.CTRL_LEFT, Keys.ALT_LEFT, Keys.L), // lock
            Chord.of(Keys.ALT_LEFT, Keys.F2),
            Chord.of(Keys.ALT_LEFT, Keys.F3),                // xfrun
            Chord.of(Keys.CTRL_LEFT, Keys.ALT_LEFT, Keys.T),
        )
    }

    /**
     * Keys the user has declared as belonging to the running game. There is no
     * way to read a game's binding table portably (and reading it would be the
     * injection behaviour §5.6 forbids), so the set is *declared*, not
     * discovered. Empty by default: the port does not guess.
     */
    data class GameKeySet(val tokens: Set<InputToken> = emptySet(), val profile: String? = null)

    /**
     * Analyse the whole binding set. [foregroundProcess] / [foregroundTitle]
     * select which per-application profiles are in play, so a chord used
     * globally and a different chord used in-game are not reported as clashing
     * when they can never both be live.
     */
    fun analyse(
        bindings: BindingSet,
        env: DesktopEnvironment,
        gameKeys: GameKeySet = GameKeySet(),
        foregroundProcess: String? = null,
        foregroundTitle: String? = null,
    ): List<Finding> {
        val findings = mutableListOf<Finding>()
        val reserved = osReserved(env)
        val live = bindings.bindings.filter { it.enabled }
            .filter { it.scope == null || it.scope.matches(foregroundProcess, foregroundTitle) }

        // ── 1. Internal: two actions on one chord ────────────────────────
        for ((chord, group) in live.groupBy { it.chord }) {
            if (chord.isEmpty) continue
            // The same action on two profiles is not a clash; two *different*
            // actions on one chord is, because the state machine breaks the tie
            // by list order — i.e. by an implementation detail the user cannot
            // see.
            val actions = group.map { it.action }.distinct()
            if (actions.size > 1) {
                findings += Finding(
                    verdict = Verdict.REJECT,
                    source = Source.INTERNAL,
                    code = "hotkey.duplicate_chord",
                    chord = chord,
                    actions = actions,
                    message = "Chord ${chord.label()} is bound to ${actions.size} actions: " +
                        actions.joinToString(", ") { it.id },
                )
            }
        }

        // ── 2. OS / desktop environment ─────────────────────────────────
        for (binding in live) {
            if (binding.chord in reserved) {
                findings += Finding(
                    verdict = Verdict.REJECT,
                    source = Source.OPERATING_SYSTEM,
                    code = "hotkey.os_reserved",
                    chord = binding.chord,
                    actions = listOf(binding.action),
                    message = "Chord ${binding.chord.label()} is reserved by ${env.name}",
                )
            }
        }

        // ── 3. Typing keys (upstream's comboTakesTypingKey, one tier up) ─
        val typingKeys: Set<InputToken> = live.flatMap { it.chord.tokens }
            .filter { it is KeyToken && Keys.isTypingKey(it.code) }
            .toSet()
        for (binding in live) {
            if (binding.chord.isEmpty) continue
            if (!binding.chord.hasCommandModifier() && binding.chord.tokens.any { it in typingKeys }) {
                findings += Finding(
                    verdict = Verdict.WARN,
                    source = Source.TYPING,
                    code = "hotkey.takes_typing_key",
                    chord = binding.chord,
                    actions = listOf(binding.action),
                    message = "Chord ${binding.chord.label()} has no command modifier and will " +
                        "swallow typing (and likely a game control)",
                )
            }
        }

        // ── 4. Game-declared keys ───────────────────────────────────────
        if (gameKeys.tokens.isNotEmpty()) {
            for (binding in live) {
                val clash = binding.chord.tokens.intersect(gameKeys.tokens)
                if (clash.isNotEmpty()) {
                    findings += Finding(
                        verdict = Verdict.HINT,
                        source = Source.GAME,
                        code = "hotkey.game_key_clash",
                        chord = binding.chord,
                        actions = listOf(binding.action),
                        message = "Chord ${binding.chord.label()} overlaps keys the user declared " +
                            "as game-owned (${clash.joinToString(", ") { it.label }})",
                    )
                }
            }
        }

        // ── 5. Registrability, and the no-key-up platforms ──────────────
        for (binding in live) {
            if (!HotkeyDevicePolicy.isRegistrable(binding.chord)) {
                findings += Finding(
                    verdict = Verdict.REJECT,
                    source = Source.INTERNAL,
                    code = "hotkey.not_registrable",
                    chord = binding.chord,
                    actions = listOf(binding.action),
                    message = "Chord is empty or modifiers-only and cannot be registered",
                )
            }
        }

        return findings
    }

    /** The subset that blocks a save. */
    fun blocking(findings: List<Finding>): List<Finding> = findings.filter { it.isBlocking }

    /**
     * Whether a chord should be *offered* during recording. The setup dialog
     * should still allow recording a reserved chord (the user may know their DE
     * does not use it), so this is used to colour the warning, not to refuse
     * the capture — mirroring upstream's "steer, do not refuse" stance, with
     * the refusal moved to save time where the user can see the whole set.
     */
    fun isReservedByEnvironment(chord: Chord, env: DesktopEnvironment): Boolean =
        chord in osReserved(env)
}

/**
 * Reachability — PORTING.md §5.8, the hardened form of upstream's
 * `IconGestureBindings.quickMenuReachable`.
 *
 * Upstream's rule was a warning: if no gesture opened the quick menu, the icon
 * told the user. The desktop version is a **hard check**, because the failure
 * mode is different in kind. On the phone the icon is always on screen; on the
 * desktop the entry points are a tray icon that GNOME does not show by default,
 * a floating icon the user may have turned off, and hotkeys. If the user turns
 * the tray off and binds nothing, the application becomes unreachable except
 * through a launcher — and a *running* instance that cannot be summoned is
 * worse than one that never started, because it is still holding hotkeys.
 *
 * Hence: `trayEnabled == false` **and** no binding on either of the two
 * keyboard routes ⇒ refuse to save.
 */
object ReachabilityCheck {

    data class Result(
        val ok: Boolean,
        val blocking: List<ConflictDetector.Finding>,
        val advisory: List<ConflictDetector.Finding>,
    )

    /** The two actions that can summon the UI from the keyboard (§5.8). */
    private val keyboardRoutes = listOf(Action.OPEN_QUICK_MENU, Action.OPEN_WORKSPACE)

    fun check(
        bindings: BindingSet,
        trayEnabled: Boolean,
        floatingIconEnabled: Boolean = false,
        touchscreenDetected: Boolean = false,
    ): Result {
        val blocking = mutableListOf<ConflictDetector.Finding>()
        val advisory = mutableListOf<ConflictDetector.Finding>()

        val hasKeyboardRoute = keyboardRoutes.any { action ->
            bindings.forAction(action).any { it.enabled && !it.chord.isEmpty }
        }

        if (!trayEnabled && !floatingIconEnabled && !hasKeyboardRoute) {
            blocking += ConflictDetector.Finding(
                verdict = ConflictDetector.Verdict.REJECT,
                source = ConflictDetector.Source.INTERNAL,
                code = "reach.tray_off_without_hotkey",
                actions = keyboardRoutes,
                message = "The tray is off and neither ${Action.OPEN_QUICK_MENU.id} nor " +
                    "${Action.OPEN_WORKSPACE.id} is bound: there would be no way to summon " +
                    "the running application",
            )
        }

        if (trayEnabled && !hasKeyboardRoute) {
            // GNOME ships no tray unless the AppIndicator extension is installed
            // (PORTING §5.8), so a tray-only configuration is one extension
            // install away from being unreachable.
            advisory += ConflictDetector.Finding(
                verdict = ConflictDetector.Verdict.WARN,
                source = ConflictDetector.Source.INTERNAL,
                code = "reach.tray_only",
                actions = keyboardRoutes,
                message = "Only the tray can summon the app; on GNOME the tray needs the " +
                    "AppIndicator extension. Consider binding a hotkey.",
            )
        }

        // PANIC_EXIT is the one-key undo for every pointer-grabbing state
        // (§5.5). Esc also exits a latched state, so an unbound panic key is a
        // warning rather than a refusal.
        if (bindings.forAction(Action.PANIC_EXIT).none { it.enabled && !it.chord.isEmpty }) {
            advisory += ConflictDetector.Finding(
                verdict = ConflictDetector.Verdict.WARN,
                source = ConflictDetector.Source.INTERNAL,
                code = "reach.no_panic_key",
                actions = listOf(Action.PANIC_EXIT),
                message = "No panic key bound: a latched state would only exit on Esc",
            )
        }

        if (touchscreenDetected && !floatingIconEnabled) {
            advisory += ConflictDetector.Finding(
                verdict = ConflictDetector.Verdict.HINT,
                source = ConflictDetector.Source.INTERNAL,
                code = "reach.touchscreen_icon_off",
                actions = emptyList(),
                message = "A touchscreen is present; the floating icon (optional mode) may suit " +
                    "this device better than the tray",
            )
        }

        return Result(ok = blocking.isEmpty(), blocking = blocking, advisory = advisory)
    }
}
