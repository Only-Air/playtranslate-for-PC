package io.github.only_air.screengloss.core.geometry

/**
 * Multi-monitor, mixed-DPI screen model (PORTING.md §1, §5.3 item 9, §6.3).
 *
 * Upstream already abstracts "screen space / bitmap space / OCR crop space"
 * (`FrameCoordinates`, 9118 lines) and keeps a per-display region and state.
 * The desktop adds a dimension upstream does not have: **each monitor carries
 * its own DPI scale factor**, and a 4K-at-150% panel next to a 1080p-at-100%
 * panel is the normal case, not the exception.
 *
 * The rule this file enforces: *everything* is stored in device pixels, and
 * every conversion between the two frames goes through [DisplayInfo]. A
 * caller that wants "halfway across the second monitor" computes it in
 * fractions and converts once — there is no second place that knows about DPI.
 */
data class DisplayInfo(
    /** Stable across reconnects on Windows/X11 as long as the port mapping holds. */
    val id: String,
    val name: String,
    /** Top-left of this display in the global virtual-desktop pixel space. */
    val originX: Int,
    val originY: Int,
    /** Resolution in physical (device) pixels. */
    val widthPx: Int,
    val heightPx: Int,
    /** Effective UI scale, 1.0 = 100%, 1.5 = 150%. */
    val scale: Float,
    val isPrimary: Boolean = false,
) {
    val boundsPx: PtRect get() = PtRect(originX.toFloat(), originY.toFloat(), (originX + widthPx).toFloat(), (originY + heightPx).toFloat())

    /** Logical size the OS reports to a DPI-unaware window. */
    val widthLogical: Float get() = widthPx / scale
    val heightLogical: Float get() = heightPx / scale

    fun contains(globalPx: PtPointF): Boolean = boundsPx.contains(globalPx)

    /** Display-local pixel point -> global virtual-desktop pixels. */
    fun localToGlobalPx(local: PtPointF): PtPointF = PtPointF(local.x + originX, local.y + originY)

    /** Global virtual-desktop pixels -> display-local pixels. */
    fun globalToLocalPx(global: PtPointF): PtPointF = PtPointF(global.x - originX, global.y - originY)
}

/**
 * The set of attached displays plus the conversions that need more than one of
 * them. Immutable: a monitor change builds a new instance, which is what makes
 * "region is stale after the monitor was unplugged" a value-level question.
 */
class ScreenGeometry(val displays: List<DisplayInfo>) {

    init {
        require(displays.isNotEmpty()) { "ScreenGeometry needs at least one display" }
    }

    val primary: DisplayInfo = displays.firstOrNull { it.isPrimary } ?: displays.first()

    fun byId(id: String): DisplayInfo? = displays.firstOrNull { it.id == id }

    /** The display a global point lands on, or null when it is outside every display. */
    fun displayAt(globalPx: PtPointF): DisplayInfo? = displays.firstOrNull { it.contains(globalPx) }

    /**
     * Nearest display to a global point. Used when the pointer sits in the dead
     * space between two monitors of different heights (a real layout, and the
     * reason upstream's "last interacted display" rule is not enough on its own).
     */
    fun nearestDisplay(globalPx: PtPointF): DisplayInfo =
        displays.minByOrNull { display ->
            val b = display.boundsPx
            val dx = maxOf(b.left - globalPx.x, 0f, globalPx.x - b.right)
            val dy = maxOf(b.top - globalPx.y, 0f, globalPx.y - b.bottom)
            dx * dx + dy * dy
        } ?: primary

    /**
     * A rectangle stored as fractions of [display] into global pixels. This is
     * the one conversion the region model is built on: regions persist as
     * fractions, captures happen in pixels.
     */
    fun fractionToGlobalPx(display: DisplayInfo, fraction: PtFractionRect): PtRect =
        fraction.toRect(display.boundsPx)

    fun globalPxToFraction(display: DisplayInfo, rect: PtRect): PtFractionRect =
        PtFractionRect.of(rect, display.boundsPx)

    /**
     * Translate a display-local crop rect into global pixels, which is what a
     * screen-capture API wants. The display's own origin is the only offset —
     * the DPI scale is deliberately *not* applied, because capture APIs on all
     * three platforms speak device pixels.
     */
    fun localToGlobal(display: DisplayInfo, local: PtRect): PtRect =
        local.offset(display.originX.toFloat(), display.originY.toFloat())

    fun globalToLocal(display: DisplayInfo, global: PtRect): PtRect =
        global.offset(-display.originX.toFloat(), -display.originY.toFloat())

    /**
     * A window rect given in *logical* pixels on [display] -> global device
     * pixels. Window geometry from the OS is DPI-unaware on Windows unless the
     * process opts in, and logical on macOS by default; this is the single
     * place that reconciles that, so the platform backends stay dumb.
     */
    fun windowLogicalToGlobalPx(display: DisplayInfo, logical: PtRect): PtRect =
        PtRect(
            display.originX + logical.left * display.scale,
            display.originY + logical.top * display.scale,
            display.originX + logical.right * display.scale,
            display.originY + logical.bottom * display.scale,
        )

    /**
     * Union of every display, i.e. the virtual desktop. Regions are clamped to
     * this so a window dragged off the edge of the world cannot produce a
     * capture request for coordinates no API will accept.
     */
    val virtualBoundsPx: PtRect
        get() = PtRect.unionOf(displays.map { it.boundsPx })

    fun isLayoutCompatibleWith(other: ScreenGeometry): Boolean =
        displays.size == other.displays.size &&
            displays.zip(other.displays).all { (a, b) ->
                a.id == b.id && a.widthPx == b.widthPx && a.heightPx == b.heightPx && a.scale == b.scale
            }
}
