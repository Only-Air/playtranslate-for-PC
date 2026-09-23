package io.github.only_air.screengloss.core.region

import io.github.only_air.screengloss.core.action.WindowScope
import io.github.only_air.screengloss.core.geometry.DisplayInfo
import io.github.only_air.screengloss.core.geometry.PtFractionRect
import io.github.only_air.screengloss.core.geometry.PtRect

/**
 * Capture regions, desktop-shaped.
 *
 * Upstream stores a region as `RegionEntry` in `Prefs.kt`:
 *
 * ```kotlin
 * data class RegionEntry(label, top, bottom, left, right, id)
 * // + Prefs.selectedRegionIdForDisplay: Map<displayId, regionId>
 * ```
 *
 * Four fractions of the screen, selected per display. That works on a phone
 * because a phone's "game window" *is* the screen: it cannot be moved,
 * resized, or dragged to another monitor, so the screen frame and the window
 * frame are the same frame.
 *
 * On a desktop they are not, and PORTING.md §5.3 item 8 draws the two
 * consequences:
 *
 *  1. **A region needs two frames of reference.** A dialogue box measured
 *     relative to the *window* survives the player moving or resizing the
 *     window; measured relative to the *display* it does not. So a
 *     [RegionSpec] carries both, and the capture path prefers the window frame
 *     when the window is known and the display frame when it is not.
 *  2. **The persistence key grows a third dimension.** Upstream keys on the
 *     display id. The port keys on `(display, process, window-title pattern)`,
 *     because the same monitor holds a different region for a different game —
 *     and, for a two-monitor setup, the same game at a different position.
 *
 * The lookup is a precedence ladder rather than an exact match, because an
 * exact match would mean "no region" the moment a game's title bar gains a
 * version number.
 */
data class RegionSpec(
    val id: String,
    val label: String,
    /** Which monitor the region was drawn on. */
    val displayId: String,
    /** Window matcher, or null for a display-wide region. */
    val window: WindowScope?,
    /** Fractions of the display's bounds. Always present — the fallback frame. */
    val displayFraction: PtFractionRect,
    /**
     * Fractions of the window's client rect. Null when the region was drawn
     * before a window was known, or deliberately as a display-wide region.
     */
    val windowFraction: PtFractionRect? = null,
    val snap: SnapKind = SnapKind.NONE,
    val createdAtMs: Long = 0L,
) {
    val isFullScreen: Boolean get() = displayFraction.isFullScreen && snap == SnapKind.NONE

    /**
     * The region in global device pixels, preferring the window frame.
     *
     * [windowRectGlobalPx] is the window's client area in global pixels, or null
     * when the window is unknown (minimised, closed, or the user is on the
     * desktop). Falling back to the display frame is not a degradation the
     * caller should have to think about — it is the normal case for a
     * display-wide region.
     */
    fun resolveGlobalPx(display: DisplayInfo, windowRectGlobalPx: PtRect?): PtRect {
        val windowFractionLocal = windowFraction
        if (windowRectGlobalPx != null && windowFractionLocal != null && !windowRectGlobalPx.isEmpty) {
            return windowFractionLocal.toRect(windowRectGlobalPx)
        }
        return displayFraction.toRect(display.boundsPx)
    }

    /** Which frame [resolveGlobalPx] would use — the UI shows this. */
    fun frameKind(windowRectGlobalPx: PtRect?): Frame =
        if (windowRectGlobalPx != null && windowFraction != null && !windowRectGlobalPx.isEmpty) Frame.WINDOW
        else Frame.DISPLAY

    enum class Frame { WINDOW, DISPLAY }
}

/**
 * The three snap levels of input state 2 (PORTING §5.2): a rubber-band
 * selection snaps to a detected text box, a window edge, or a display edge,
 * and `Tab` cycles between the levels. Upstream's `RegionPickerSheet` has no
 * equivalent because a phone has no window edges to snap to.
 */
enum class SnapKind { NONE, OCR_BOX, WINDOW_EDGE, DISPLAY_EDGE }

/**
 * The persistence key: `(display, process, window-title pattern)`.
 *
 * Nullable fields are wildcards, which is what makes the lookup a ladder.
 */
data class RegionKey(
    val displayId: String,
    val process: String? = null,
    val titlePattern: String? = null,
)

/**
 * The region store, with the precedence ladder.
 *
 * Ladder, most specific first:
 * ```
 * (display, process, title)  ->  (display, process, *)  ->  (display, *, *)
 * ```
 * A miss at every rung returns null, and the caller uses the full-screen
 * default — which is exactly upstream's behaviour for an unconfigured display
 * (`Prefs.selectedRegionIdForDisplay` absent), so a first run looks the same on
 * both platforms.
 */
class RegionStore(initial: List<RegionSpec> = emptyList()) {

    private val byKey = LinkedHashMap<RegionKey, RegionSpec>()

    init {
        for (spec in initial) {
            byKey[RegionKey(spec.displayId, spec.window?.process, spec.window?.titlePattern)] = spec
        }
    }

    val size: Int get() = byKey.size

    fun all(): List<RegionSpec> = byKey.values.toList()

    fun save(spec: RegionSpec): RegionStore {
        byKey[RegionKey(spec.displayId, spec.window?.process, spec.window?.titlePattern)] = spec
        return this
    }

    fun remove(key: RegionKey): Boolean = byKey.remove(key) != null

    /** Every region saved for one monitor, for the region picker's list. */
    fun forDisplay(displayId: String): List<RegionSpec> =
        byKey.values.filter { it.displayId == displayId }

    /**
     * Resolve the region for a foreground window. [process] and [title] are the
     * *current* foreground values; the ladder matches the stored wildcards
     * against them.
     */
    fun resolve(displayId: String, process: String?, title: String?): RegionSpec? {
        val ladder = listOf(
            RegionKey(displayId, process, title),
            RegionKey(displayId, process, null),
            RegionKey(displayId, null, null),
        )
        for (key in ladder) {
            byKey[key]?.let { return it }
        }
        // Last resort: a region stored for this display with a title pattern but
        // no process (hand-authored config, or a migration from a display-only
        // build), matched against the live title.
        if (title == null) return null
        return byKey.values.firstOrNull { spec ->
            val pattern = spec.window?.titlePattern
            spec.displayId == displayId &&
                spec.window?.process == null &&
                pattern != null &&
                runCatching { Regex(pattern).containsMatchIn(title) }.getOrDefault(false)
        }
    }

    /**
     * Drop every region whose monitor is no longer attached. PORTING §1's
     * multi-monitor model: an unplugged monitor must not leave a region that
     * resolves to coordinates no capture API will accept.
     */
    fun pruneTo(attachedDisplayIds: Set<String>): List<RegionSpec> {
        val removed = byKey.values.filterNot { it.displayId in attachedDisplayIds }
        byKey.entries.removeAll { it.key.displayId !in attachedDisplayIds }
        return removed
    }

    fun encode(): List<String> = byKey.values.map { spec ->
        listOf(
            spec.id,
            spec.displayId,
            spec.window?.process ?: "",
            spec.window?.titlePattern ?: "",
            fmt(spec.displayFraction.left), fmt(spec.displayFraction.top),
            fmt(spec.displayFraction.right), fmt(spec.displayFraction.bottom),
            spec.windowFraction?.let { "${fmt(it.left)},${fmt(it.top)},${fmt(it.right)},${fmt(it.bottom)}" } ?: "",
            spec.snap.name,
            spec.label.replace("|", " "),
        ).joinToString("|")
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.ROOT, "%.5f", v)

    companion object {
        /** The implicit full-screen region a display has before the user draws one. */
        fun fullScreen(displayId: String, label: String = ""): RegionSpec = RegionSpec(
            id = "full-$displayId",
            label = label,
            displayId = displayId,
            window = null,
            displayFraction = PtFractionRect.FULL,
            windowFraction = null,
            snap = SnapKind.NONE,
        )
    }
}
