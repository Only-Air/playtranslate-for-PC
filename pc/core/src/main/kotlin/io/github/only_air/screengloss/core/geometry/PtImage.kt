package io.github.only_air.screengloss.core.geometry

/**
 * Platform-neutral image buffer, replacing `android.graphics.Bitmap` for the
 * PC core (PORTING.md §2.1).
 *
 * Scope note, because it is easy to over-build here: the PC port only ever
 * hands *pixels to OCR* and *a crop to the overlay/Anki screenshot*. It never
 * draws, never composites, and never needs a graphics context. So this is a
 * plain 8-bit sRGB RGBA buffer plus the operations the OCR pipeline performs —
 * crop, scale, grayscale, contrast — and nothing else. The moment it grows a
 * `drawText`, the native overlay is doing something wrong.
 *
 * Pixels are packed `0xAARRGGBB` (Android's `ARGB_8888` layout) so ported
 * upstream code that indexes a `Bitmap` keeps working unchanged.
 *
 * HDR sources never reach here: `ColorNormalizer` tone-maps them into an
 * `PtImage` first (PORTING.md §6.3, risk 2).
 */
class PtImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    /** Where this buffer came from, for diagnostics and for the OCR debug overlay. */
    val provenance: Provenance = Provenance.UNKNOWN,
) {
    init {
        require(width > 0 && height > 0) { "PtImage needs a positive size, got ${width}x$height" }
        require(pixels.size >= width * height) {
            "pixel buffer too small: ${pixels.size} < ${width * height}"
        }
    }

    enum class Provenance {
        /** Windows.Graphics.Capture / DXGI, macOS ScreenCaptureKit, X11 XComposite, PipeWire. */
        SCREEN_CAPTURE,
        /** Already tone-mapped from an HDR source by [ColorNormalizer]. */
        NORMALIZED_HDR,
        /** A still image the user dropped on the window (visual novel / manga / PDF). */
        FILE_IMPORT,
        /** Clipboard paste. */
        CLIPBOARD,
        /** Test fixture; never produced at runtime. */
        FIXTURE,
        UNKNOWN,
    }

    val size: PtIntRect get() = PtIntRect(0, 0, width, height)

    fun pixelAt(x: Int, y: Int): Int = pixels[y * width + x]

    fun alphaAt(x: Int, y: Int): Int = (pixels[y * width + x] ushr 24) and 0xFF

    fun lumaAt(x: Int, y: Int): Int {
        val p = pixels[y * width + x]
        return luma((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
    }

    /** Bounds-checked crop; out-of-range areas come back transparent black. */
    fun crop(rect: PtIntRect): PtImage {
        val w = rect.width.coerceAtLeast(1)
        val h = rect.height.coerceAtLeast(1)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val sy = rect.top + y
            if (sy < 0 || sy >= height) continue
            for (x in 0 until w) {
                val sx = rect.left + x
                if (sx < 0 || sx >= width) continue
                out[y * w + x] = pixels[sy * width + sx]
            }
        }
        return PtImage(w, h, out, provenance)
    }

    /** Nearest-neighbour scale. The OCR pipeline uses this only to shrink oversized frames. */
    fun scaledTo(targetWidth: Int, targetHeight: Int): PtImage {
        require(targetWidth > 0 && targetHeight > 0)
        if (targetWidth == width && targetHeight == height) return this
        val out = IntArray(targetWidth * targetHeight)
        val sx = width.toFloat() / targetWidth
        val sy = height.toFloat() / targetHeight
        for (y in 0 until targetHeight) {
            val srcY = (y * sy).toInt().coerceIn(0, height - 1)
            for (x in 0 until targetWidth) {
                val srcX = (x * sx).toInt().coerceIn(0, width - 1)
                out[y * targetWidth + x] = pixels[srcY * width + srcX]
            }
        }
        return PtImage(targetWidth, targetHeight, out, provenance)
    }

    /**
     * 8-bit single-channel luma, the input form every OCR recognizer in the
     * catalog expects (`paddle_det.mnn` takes a normalised float blob built
     * from exactly this).
     */
    fun toLuma8(): ByteArray {
        val out = ByteArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i] = luma((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF).toByte()
        }
        return out
    }

    /** Fraction of pixels that are not fully transparent — the blank-frame gate. */
    fun opaqueFraction(): Float {
        if (pixels.isEmpty()) return 0f
        var n = 0
        for (p in pixels) if ((p ushr 24) and 0xFF > 8) n++
        return n.toFloat() / pixels.size
    }

    companion object {
        fun filled(width: Int, height: Int, argb: Int, provenance: Provenance = Provenance.FIXTURE): PtImage =
            PtImage(width, height, IntArray(width * height) { argb }, provenance)

        /**
         * A deterministic test fixture: a light background with darker blocks
         * laid out in a grid, which is enough for the OCR pipeline's geometry
         * (box detection, reading order) to have something real to chew on
         * without shipping a screenshot into the repo.
         */
        fun fixture(width: Int, height: Int, blocks: List<PtIntRect>): PtImage {
            val px = IntArray(width * height) { 0xFFF2F2F2.toInt() }
            for (b in blocks) {
                for (y in b.top until b.bottom) {
                    if (y < 0 || y >= height) continue
                    for (x in b.left until b.right) {
                        if (x < 0 || x >= width) continue
                        px[y * width + x] = 0xFF202020.toInt()
                    }
                }
            }
            return PtImage(width, height, px, Provenance.FIXTURE)
        }

        fun luma(r: Int, g: Int, b: Int): Int = (r * 299 + g * 587 + b * 114) / 1000
    }
}
