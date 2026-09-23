package io.github.only_air.screengloss.core.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Platform-neutral replacements for the three `android.graphics` types that
 * actually leak into the Android core (PORTING.md §2.1: `Rect` × 17,
 * `Bitmap` × 8, `PointF` × 5).
 *
 * `PtRect` and `PtPointF` mirror `android.graphics.RectF` exactly — same
 * `Float` coordinates, same left/top/right/bottom convention (right/bottom
 * exclusive) — so the ported layout algorithms (`LayoutAnalyzer`,
 * `DeskewGeometry`, `FrameCoordinates`) keep their arithmetic, including its
 * rounding behaviour. Do not "improve" these to `Double`: the upstream
 * golden-set tests were written against float arithmetic.
 */
data class PtPointF(val x: Float, val y: Float) {
    fun offset(dx: Float, dy: Float): PtPointF = PtPointF(x + dx, y + dy)

    fun distanceTo(other: PtPointF): Float {
        val dx = x - other.x
        val dy = y - other.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun isFinite(): Boolean = x.isFinite() && y.isFinite()
}

/** Integer twin of [PtPointF], for buffer offsets. */
data class PtPoint(val x: Int, val y: Int)

/**
 * Axis-aligned rectangle in `Float`, right/bottom exclusive.
 *
 * `isEmpty` follows the Android convention (a rect is empty when its width or
 * height is <= 0), which callers rely on to drop degenerate OCR boxes.
 */
data class PtRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val center: PtPointF get() = PtPointF(centerX, centerY)
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
    val area: Float get() = if (isEmpty) 0f else width * height

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    fun contains(p: PtPointF): Boolean = contains(p.x, p.y)

    fun contains(other: PtRect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    fun intersects(other: PtRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** Intersection area, 0 when they do not overlap. Used by the hover hit test. */
    fun intersectionArea(other: PtRect): Float {
        if (!intersects(other)) return 0f
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        return if (w <= 0f || h <= 0f) 0f else w * h
    }

    fun intersect(other: PtRect): PtRect {
        val l = max(left, other.left)
        val t = max(top, other.top)
        val r = min(right, other.right)
        val b = min(bottom, other.bottom)
        return if (l >= r || t >= b) EMPTY else PtRect(l, t, r, b)
    }

    fun union(other: PtRect): PtRect {
        if (isEmpty) return other
        if (other.isEmpty) return this
        return PtRect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
    }

    fun inset(dx: Float, dy: Float = dx): PtRect =
        PtRect(left + dx, top + dy, right - dx, bottom - dy)

    fun offset(dx: Float, dy: Float): PtRect = PtRect(left + dx, top + dy, right + dx, bottom + dy)

    fun scale(sx: Float, sy: Float = sx): PtRect =
        PtRect(left * sx, top * sy, right * sx, bottom * sy)

    /** Integer bounds, right/bottom exclusive, expanded outward so no pixel is lost. */
    fun toIntBounds(): PtIntRect = PtIntRect(
        kotlin.math.floor(left).toInt(),
        kotlin.math.floor(top).toInt(),
        kotlin.math.ceil(right).toInt(),
        kotlin.math.ceil(bottom).toInt(),
    )

    fun isFinite(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

    /** Clamp into [bounds]; returns [EMPTY] when the rect falls outside entirely. */
    fun clampTo(bounds: PtRect): PtRect = intersect(bounds)

    /** Intersection-over-union, the standard hit-quality score for OCR boxes. */
    fun iou(other: PtRect): Float {
        val inter = intersectionArea(other)
        if (inter <= 0f) return 0f
        val union = area + other.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** Intersection over this rect's own area — "how much of the box is covered". */
    fun coverageBy(other: PtRect): Float {
        val a = area
        if (a <= 0f) return 0f
        return intersectionArea(other) / a
    }

    fun approxEquals(other: PtRect, epsilon: Float = 0.01f): Boolean =
        abs(left - other.left) <= epsilon && abs(top - other.top) <= epsilon &&
            abs(right - other.right) <= epsilon && abs(bottom - other.bottom) <= epsilon

    companion object {
        val EMPTY = PtRect(0f, 0f, 0f, 0f)

        fun ofSize(width: Float, height: Float): PtRect = PtRect(0f, 0f, width, height)

        fun fromCenter(cx: Float, cy: Float, width: Float, height: Float): PtRect =
            PtRect(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)

        /** The smallest rect containing all of [rects]; [EMPTY] for an empty list. */
        fun unionOf(rects: Iterable<PtRect>): PtRect = rects.fold(EMPTY) { acc, r -> acc.union(r) }

        /**
         * Normalise a possibly-inverted rect (right < left, as a rubber-band
         * selection dragged up-and-left produces) into canonical form.
         */
        fun normalized(x0: Float, y0: Float, x1: Float, y1: Float): PtRect =
            PtRect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
    }
}

/** Integer rectangle, right/bottom exclusive. Buffer-space twin of [PtRect]. */
data class PtIntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun toFloatRect(): PtRect = PtRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    companion object {
        val EMPTY = PtIntRect(0, 0, 0, 0)
    }
}

/**
 * A rectangle expressed as fractions of a parent rect (0..1), which is how
 * upstream stores capture regions (`RegionEntry` in `Prefs.kt`) so they survive
 * a resolution or orientation change.
 *
 * The PC port keeps the fraction model but adds the *second* frame of
 * reference the desktop needs — see `RegionSpec` and PORTING.md §5.3 item 8.
 */
data class PtFractionRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val isFullScreen: Boolean get() = top <= 0f && bottom >= 1f && left <= 0f && right >= 1f

    fun toRect(parent: PtRect): PtRect = PtRect(
        parent.left + left * parent.width,
        parent.top + top * parent.height,
        parent.left + right * parent.width,
        parent.top + bottom * parent.height,
    )

    fun clamp01(): PtFractionRect = PtFractionRect(
        left.coerceIn(0f, 1f),
        top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f),
        bottom.coerceIn(0f, 1f),
    )

    companion object {
        val FULL = PtFractionRect(0f, 0f, 1f, 1f)

        fun of(rect: PtRect, parent: PtRect): PtFractionRect {
            if (parent.width <= 0f || parent.height <= 0f) return FULL
            return PtFractionRect(
                (rect.left - parent.left) / parent.width,
                (rect.top - parent.top) / parent.height,
                (rect.right - parent.left) / parent.width,
                (rect.bottom - parent.top) / parent.height,
            )
        }
    }
}
