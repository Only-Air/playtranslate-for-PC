package io.github.only_air.screengloss.core.action

/**
 * The platform-neutral input vocabulary.
 *
 * Upstream stored a hotkey as a `Set<Int>` of Android `KeyEvent.KEYCODE_*`
 * values (see `Prefs` / `HotkeyDecision`). The desktop cannot reuse those
 * constants — they are Android-only, and the desktop has input the phone never
 * had (mouse side buttons, media keys, per-device handles). But it *can* reuse
 * the **shape**: an opaque integer code per physical input, and a set of them
 * as the chord identity.
 *
 * So: [InputToken] is the portable stand-in for "a keycode", with the one
 * extension the desktop needs — a token may be a *mouse* button. Keeping mouse
 * buttons in the same vocabulary is what makes "mouse button 4" a first-class
 * binding rather than a special case threaded through the router
 * (PORTING.md §5.7: side buttons are free real estate, games rarely take them).
 *
 * Code ranges, chosen so that a stored binding can never be misread as another
 * kind of token:
 *  - `0x0000..0x0FFF` keyboard, using USB HID usage codes where one exists
 *    (letters/digits are ASCII for legibility in stored prefs);
 *  - `0x1000..0x1FFF` mouse buttons;
 *  - `0x2000..0x2FFF` wheel / scroll axes.
 */
sealed interface InputToken {
    val code: Int
    val label: String

    /** True for modifiers that make a combo a command rather than text. */
    val isCommandModifier: Boolean get() = false
}

data class KeyToken(override val code: Int) : InputToken {
    override val label: String get() = Keys.label(code)
    override val isCommandModifier: Boolean get() = code in Keys.COMMAND_MODIFIER_CODES
}

data class MouseToken(override val code: Int) : InputToken {
    override val label: String get() = MouseButtons.label(code)
}

/**
 * Keyboard codes. Letters and digits use their ASCII value; the rest are
 * assigned from the USB HID usage page so the mapping to a platform scancode
 * is a table lookup in the platform layer and not a judgement call here.
 */
object Keys {
    val A = 0x41; val B = 0x42; val C = 0x43; val D = 0x44; val E = 0x45; val F = 0x46
    val G = 0x47; val H = 0x48; val I = 0x49; val J = 0x4A; val K = 0x4B; val L = 0x4C
    val M = 0x4D; val N = 0x4E; val O = 0x4F; val P = 0x50; val Q = 0x51; val R = 0x52
    val S = 0x53; val T = 0x54; val U = 0x55; val V = 0x56; val W = 0x57; val X = 0x58
    val Y = 0x59; val Z = 0x5A

    val NUM_0 = 0x30; val NUM_1 = 0x31; val NUM_2 = 0x32; val NUM_3 = 0x33; val NUM_4 = 0x34
    val NUM_5 = 0x35; val NUM_6 = 0x36; val NUM_7 = 0x37; val NUM_8 = 0x38; val NUM_9 = 0x39

    val ESCAPE = 0x0B01
    val TAB = 0x0B02
    val CAPS_LOCK = 0x0B03
    val SPACE = 0x0B04
    val ENTER = 0x0B05
    val BACKSPACE = 0x0B06
    val INSERT = 0x0B07
    val DELETE = 0x0B08
    val HOME = 0x0B09
    val END = 0x0B0A
    val PAGE_UP = 0x0B0B
    val PAGE_DOWN = 0x0B0C
    val ARROW_LEFT = 0x0B0D
    val ARROW_RIGHT = 0x0B0E
    val ARROW_UP = 0x0B0F
    val ARROW_DOWN = 0x0B10

    val CTRL_LEFT = 0x0B20
    val CTRL_RIGHT = 0x0B21
    val SHIFT_LEFT = 0x0B22
    val SHIFT_RIGHT = 0x0B23
    val ALT_LEFT = 0x0B24
    val ALT_RIGHT = 0x0B25
    val META_LEFT = 0x0B26
    val META_RIGHT = 0x0B27

    val F1 = 0x0B40; val F2 = 0x0B41; val F3 = 0x0B42; val F4 = 0x0B43; val F5 = 0x0B44
    val F6 = 0x0B45; val F7 = 0x0B46; val F8 = 0x0B47; val F9 = 0x0B48; val F10 = 0x0B49
    val F11 = 0x0B4A; val F12 = 0x0B4B

    val PRINT_SCREEN = 0x0B60
    val SCROLL_LOCK = 0x0B61
    val PAUSE = 0x0B62
    val MENU = 0x0B63

    /**
     * Modifiers that turn a combo into a *command*. Carried over verbatim from
     * upstream `COMMAND_MODIFIER_KEYCODES`, including its two deliberate
     * absences, which are decisions worth preserving rather than rediscovering:
     *
     *  - **Shift is not here.** Shift+T still types a letter.
     *  - **Right Alt is not here.** It is AltGr on most layouts and types too
     *    (German AltGr+E is €). Microsoft's own guidance is to keep shortcuts
     *    away from AltGr rather than try to detect it; excluding the key is that
     *    advice applied. The cost is a spurious warning for US-layout users.
     */
    val COMMAND_MODIFIER_CODES: Set<Int> = setOf(CTRL_LEFT, CTRL_RIGHT, META_LEFT, META_RIGHT, ALT_LEFT)

    private val NAMED: Map<Int, String> = buildMap {
        for (c in 'A'..'Z') put(c.code, c.toString())
        for (c in '0'..'9') put(c.code, c.toString())
        put(ESCAPE, "Esc"); put(TAB, "Tab"); put(CAPS_LOCK, "CapsLock"); put(SPACE, "Space")
        put(ENTER, "Enter"); put(BACKSPACE, "Backspace"); put(INSERT, "Ins"); put(DELETE, "Del")
        put(HOME, "Home"); put(END, "End"); put(PAGE_UP, "PgUp"); put(PAGE_DOWN, "PgDn")
        put(ARROW_LEFT, "Left"); put(ARROW_RIGHT, "Right"); put(ARROW_UP, "Up"); put(ARROW_DOWN, "Down")
        put(CTRL_LEFT, "Ctrl"); put(CTRL_RIGHT, "RCtrl"); put(SHIFT_LEFT, "Shift"); put(SHIFT_RIGHT, "RShift")
        put(ALT_LEFT, "Alt"); put(ALT_RIGHT, "AltGr"); put(META_LEFT, "Meta"); put(META_RIGHT, "RMeta")
        for (i in 1..12) put(0x0B40 + i - 1, "F$i")
        put(PRINT_SCREEN, "PrtSc"); put(SCROLL_LOCK, "ScrLk"); put(PAUSE, "Pause"); put(MENU, "Menu")
    }

    fun label(code: Int): String = NAMED[code] ?: "Key#${code.toString(16)}"

    /** True when this code produces a glyph on a typical layout — the typing-warning input set. */
    fun isTypingKey(code: Int): Boolean =
        (code in A..Z) || (code in NUM_0..NUM_9) || code == SPACE
}

/**
 * Mouse buttons. Side buttons get their own names because on Windows they
 * arrive as `WM_XBUTTON1/2`, on X11 as buttons 8/9, on macOS as
 * `NSEvent.buttonNumber` 3/4 — three different numbers for the same physical
 * button, which is exactly why the *portable* code is assigned here rather
 * than taken from any one platform.
 */
object MouseButtons {
    const val LEFT = 0x1000
    const val RIGHT = 0x1001
    const val MIDDLE = 0x1002
    /** The two thumb buttons. `SIDE_1` is "back" on Windows, "button 8" on X11. */
    const val SIDE_1 = 0x1003
    const val SIDE_2 = 0x1004
    const val SIDE_3 = 0x1005
    const val SIDE_4 = 0x1006

    const val WHEEL_UP = 0x2000
    const val WHEEL_DOWN = 0x2001
    const val WHEEL_LEFT = 0x2002
    const val WHEEL_RIGHT = 0x2003

    private val NAMED: Map<Int, String> = mapOf(
        LEFT to "Mouse Left", RIGHT to "Mouse Right", MIDDLE to "Mouse Middle",
        SIDE_1 to "Mouse 4", SIDE_2 to "Mouse 5", SIDE_3 to "Mouse 6", SIDE_4 to "Mouse 7",
        WHEEL_UP to "Wheel Up", WHEEL_DOWN to "Wheel Down",
        WHEEL_LEFT to "Wheel Left", WHEEL_RIGHT to "Wheel Right",
    )

    fun label(code: Int): String = NAMED[code] ?: "Mouse#${code.toString(16)}"

    fun token(code: Int): InputToken = if (code >= 0x2000) MouseToken(code) else MouseToken(code)

    val SIDE_BUTTONS: List<Int> = listOf(SIDE_1, SIDE_2, SIDE_3, SIDE_4)
}

/**
 * A key chord: the set of inputs that must be held together.
 *
 * Same identity model as upstream (a set), extended to carry mouse tokens.
 * Ordering does not matter, and that is load-bearing: the state machine's
 * shadow-window logic is defined on subsets, not sequences.
 */
data class Chord(val tokens: Set<InputToken>) {

    val isEmpty: Boolean get() = tokens.isEmpty()
    val size: Int get() = tokens.size

    fun containsAll(other: Chord): Boolean = tokens.containsAll(other.tokens)

    fun contains(other: Chord): Boolean = tokens.containsAll(other.tokens)

    /** Modifiers only, in a stable display order, then the base key. */
    fun label(): String {
        if (tokens.isEmpty()) return ""
        val mods = tokens.filter { it.isCommandModifier }.sortedBy { modifierOrder(it.code) }
        val rest = tokens.filterNot { it.isCommandModifier }.sortedBy { it.code }
        return (mods + rest).joinToString("+") { it.label }
    }

    private fun modifierOrder(code: Int): Int = when (code) {
        Keys.CTRL_LEFT, Keys.CTRL_RIGHT -> 0
        Keys.ALT_LEFT, Keys.ALT_RIGHT -> 1
        Keys.SHIFT_LEFT, Keys.SHIFT_RIGHT -> 2
        Keys.META_LEFT, Keys.META_RIGHT -> 3
        else -> 4
    }

    fun hasCommandModifier(): Boolean = tokens.any { it.isCommandModifier }

    fun hasMouseToken(): Boolean = tokens.any { it is MouseToken }

    /**
     * True when this chord is a proper subset of [other] — the definition the
     * shadow window is built on.
     */
    fun isProperSubsetOf(other: Chord): Boolean =
        other.size > size && other.tokens.containsAll(tokens)

    /**
     * Round-trip encoding for storage. Tokens are `k<hex>` / `m<hex>`, so a
     * future token kind cannot be silently parsed as a key by an older build.
     */
    fun encode(): String = tokens.sortedBy { it.code }
        .joinToString("+") { (if (it is MouseToken) "m" else "k") + it.code.toString(16) }

    companion object {
        val NONE = Chord(emptySet())

        fun of(vararg codes: Int): Chord = Chord(codes.map { key(it) }.toSet())

        /** A chord of exactly one token — the mouse-side-button defaults. */
        fun single(token: InputToken): Chord = Chord(setOf(token))

        fun key(code: Int): InputToken = KeyToken(code)

        fun mouse(code: Int): InputToken = MouseToken(code)

        /** Ctrl+Alt+<key>, the shape every default binding in the design uses. */
        fun ctrlAlt(keyCode: Int): Chord = Chord(setOf(KeyToken(Keys.CTRL_LEFT), KeyToken(Keys.ALT_LEFT), KeyToken(keyCode)))

        fun ctrlAltShift(keyCode: Int): Chord = Chord(
            setOf(KeyToken(Keys.CTRL_LEFT), KeyToken(Keys.ALT_LEFT), KeyToken(Keys.SHIFT_LEFT), KeyToken(keyCode))
        )

        fun decode(stored: String): Chord {
            if (stored.isBlank()) return NONE
            val tokens = stored.split("+").mapNotNull { part ->
                val t = part.trim()
                if (t.isEmpty()) return@mapNotNull null
                when {
                    t.startsWith("m") -> t.drop(1).toIntOrNull(16)?.let { MouseToken(it) }
                    t.startsWith("k") -> t.drop(1).toIntOrNull(16)?.let { KeyToken(it) }
                    // Legacy/lenient: a bare number is a key code.
                    else -> t.toIntOrNull(16)?.let { KeyToken(it) }
                }
            }.toSet()
            return Chord(tokens)
        }
    }
}
