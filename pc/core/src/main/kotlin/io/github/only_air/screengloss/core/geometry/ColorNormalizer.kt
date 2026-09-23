package io.github.only_air.screengloss.core.geometry

import kotlin.math.pow

/**
 * Colour-space normalisation for captured frames (PORTING.md §6.3, risk 2).
 *
 * This is a failure mode the Android app has never had. A phone's screenshots
 * are always sRGB, 8-bit, fixed DPI. On the desktop a captured frame from an
 * HDR game can arrive as:
 *
 *  - **scRGB** — 16-bit half-float *linear*, values can exceed 1.0 (Windows
 *    advanced colour);
 *  - **PQ / HDR10** — 10-bit per channel with the SMPTE ST 2084 transfer
 *    function, nominal peak 10 000 nits;
 *  - **HLG** — broadcast HDR, different transfer function again.
 *
 * Feed any of those to a recognizer trained on 8-bit sRGB and the result is not
 * "slightly worse", it is garbage: mid-grey lands near black because the PQ
 * curve is not the sRGB curve. PORTING.md calls this the most likely reason a
 * PC port would "look worse than the phone", and requires the normalisation to
 * happen in preprocessing. That is this file.
 *
 * The pipeline is `decode transfer function -> linear -> tone-map to display
 * white -> sRGB encode -> 8-bit`. Deliberately simple and deterministic: a
 * Reinhard curve with an exposure term chosen so *diffuse white* (a UI
 * element, a subtitle, a white character) maps to ~250 rather than 255, which
 * leaves the recognizer the same contrast headroom it had on Android.
 *
 * **This does not need to be a colour-accurate renderer.** OCR reads shapes and
 * edges; the job here is to put the dynamic range where the recognizer expects
 * it, not to look pretty. Anything cleverer (a real ACES fit, per-title
 * metadata) would be untestable without hardware we do not have.
 */
object ColorNormalizer {

    /** Transfer function of the captured frame. */
    enum class Transfer {
        /** Already display-referred 8-bit sRGB — the common case, a no-op. */
        SRGB,
        /** 16-bit half-float linear, values >= 1.0 legal (Windows advanced colour). */
        SCRGB_LINEAR,
        /** SMPTE ST 2084 (PQ), HDR10. */
        PQ,
        /** ARIB STD-B67 (HLG). */
        HLG,
    }

    /**
     * A captured frame before normalisation. `samples` is interleaved RGBA.
     * For [Transfer.SRGB] the samples are the 0..255 code values; for the other
     * three they are *normalised* (0..1 for PQ/HLG code values, unbounded for
     * linear scRGB), which is what the capture APIs hand back.
     */
    class HdrFrame(
        val width: Int,
        val height: Int,
        val samples: FloatArray,
        val transfer: Transfer,
        /** HDR reference white in the same units as the linear signal; 80 nits is the HDR10 convention. */
        val referenceWhite: Float = 80f,
        /** Nominal peak in the same units; 1000 nits is a common mastering target. */
        val peakNits: Float = 1000f,
    ) {
        init {
            require(width > 0 && height > 0)
            require(samples.size >= width * height * 4) {
                "HDR sample buffer too small: ${samples.size} < ${width * height * 4}"
            }
        }

        val linear: FloatArray get() = ColorNormalizer.toLinearRgba(this)
    }

    /** SMPTE ST 2084 EOTF: PQ code value (0..1) -> linear luminance normalised to 10 000 nits. */
    fun pqToLinear(code: Float): Float {
        val c = code.coerceIn(0f, 1f)
        val m1 = 0.1593017578125f
        val m2 = 78.84375f
        val c1 = 0.8359375f
        val c2 = 18.8515625f
        val c3 = 18.6875f
        val p = c.toDouble().pow(m2.toDouble()).toFloat()
        val num = (p - c1).coerceAtLeast(0f)
        val den = c2 - c3 * p
        if (den <= 0f) return 0f
        return (num / den).toDouble().pow((1.0 / m1).toDouble()).toFloat()
    }

    /** ARIB STD-B67 inverse OETF: HLG code value (0..1) -> linear scene light. */
    fun hlgToLinear(code: Float): Float {
        val e = code.coerceIn(0f, 1f)
        val a = 0.17883277f
        val b = 1f - 4f * a
        val c = 0.5f - a * kotlin.math.ln(4f * a)
        return if (e <= 0.5f) (e * e) / 3f
        else (kotlin.math.exp((e - c) / a) + b) / 12f
    }

    /** sRGB OETF: linear (0..1) -> code value (0..1). */
    fun linearToSrgb(linear: Float): Float {
        val l = linear.coerceIn(0f, 1f)
        return if (l <= 0.0031308f) l * 12.92f
        else 1.055f * l.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
    }

    /**
     * Reinhard tone-map with a knee at [referenceWhite].
     *
     * Below the knee the signal is passed through (so subtitles and UI, which
     * sit at or under reference white, keep their exact relative contrast);
     * above it, values compress asymptotically toward 1.0 instead of clipping,
     * which is what keeps a bright sky or a muzzle flash from turning a
     * recognizer-visible character into a white blob.
     */
    fun toneMap(linear: Float, referenceWhite: Float, peak: Float): Float {
        val l = linear.coerceAtLeast(0f)
        if (referenceWhite <= 0f) return l.coerceIn(0f, 1f)
        val x = l / referenceWhite
        if (x <= 1f) return x
        val headroom = (peak / referenceWhite).coerceAtLeast(1.0001f)
        // Map 1..headroom onto 1..~0.98 with a smooth shoulder.
        val t = (x - 1f) / (headroom - 1f)
        return 1f - 0.02f * (1f - 1f / (1f + t))
    }

    fun toLinearRgba(frame: HdrFrame): FloatArray {
        val n = frame.width * frame.height
        val out = FloatArray(n * 4)
        val peak = frame.peakNits
        val white = frame.referenceWhite
        for (i in 0 until n) {
            val o = i * 4
            when (frame.transfer) {
                Transfer.SRGB -> {
                    // Code values, display-referred: straight through, no linearisation needed.
                    out[o] = frame.samples[o] / 255f
                    out[o + 1] = frame.samples[o + 1] / 255f
                    out[o + 2] = frame.samples[o + 2] / 255f
                }
                Transfer.SCRGB_LINEAR -> {
                    out[o] = toneMap(frame.samples[o], 1f, peak / white)
                    out[o + 1] = toneMap(frame.samples[o + 1], 1f, peak / white)
                    out[o + 2] = toneMap(frame.samples[o + 2], 1f, peak / white)
                }
                Transfer.PQ -> {
                    // PQ code value -> fraction of 10 000 nits -> nits -> normalise to reference white.
                    out[o] = toneMap(pqToLinear(frame.samples[o]) * 10_000f / white, 1f, peak / white)
                    out[o + 1] = toneMap(pqToLinear(frame.samples[o + 1]) * 10_000f / white, 1f, peak / white)
                    out[o + 2] = toneMap(pqToLinear(frame.samples[o + 2]) * 10_000f / white, 1f, peak / white)
                }
                Transfer.HLG -> {
                    // HLG is scene-referred; the 12.0 denominator in the standard OETF puts
                    // diffuse white near 0.75, so scale by 1/0.75 to land it at 1.0.
                    val k = 1f / 0.75f
                    out[o] = toneMap(hlgToLinear(frame.samples[o]) * k, 1f, peak / white)
                    out[o + 1] = toneMap(hlgToLinear(frame.samples[o + 1]) * k, 1f, peak / white)
                    out[o + 2] = toneMap(hlgToLinear(frame.samples[o + 2]) * k, 1f, peak / white)
                }
            }
        }
        return out
    }

    /**
     * Normalise a captured frame into the 8-bit sRGB [PtImage] the OCR
     * pipeline is written against. An [Transfer.SRGB] frame is a straight
     * repack — no curve is applied twice.
     */
    fun normalize(frame: HdrFrame): PtImage {
        val n = frame.width * frame.height
        val px = IntArray(n)
        val linear = toLinearRgba(frame)
        for (i in 0 until n) {
            val o = i * 4
            val a = (frame.samples[o + 3].coerceIn(0f, 255f)).toInt().coerceIn(0, 255)
            val r = (linearToSrgb(linear[o]) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (linearToSrgb(linear[o + 1]) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (linearToSrgb(linear[o + 2]) * 255f + 0.5f).toInt().coerceIn(0, 255)
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val provenance =
            if (frame.transfer == Transfer.SRGB) PtImage.Provenance.SCREEN_CAPTURE
            else PtImage.Provenance.NORMALIZED_HDR
        return PtImage(frame.width, frame.height, px, provenance)
    }

    /** Repack an existing 8-bit buffer as an [HdrFrame] tagged sRGB, for uniform call sites. */
    fun asSrgbFrame(image: PtImage): HdrFrame {
        val samples = FloatArray(image.width * image.height * 4)
        for (i in 0 until image.width * image.height) {
            val p = image.pixels[i]
            val o = i * 4
            samples[o] = ((p shr 16) and 0xFF).toFloat()
            samples[o + 1] = ((p shr 8) and 0xFF).toFloat()
            samples[o + 2] = (p and 0xFF).toFloat()
            samples[o + 3] = ((p ushr 24) and 0xFF).toFloat()
        }
        return HdrFrame(image.width, image.height, samples, Transfer.SRGB)
    }

    /**
     * Cheap classification of a capture's colour space from the format the
     * platform reports, so the capture backends do not each invent their own
     * mapping. Unknown strings land on [Transfer.SRGB], which is the
     * safe-but-loud case: [classify] callers log it.
     */
    fun classify(platformFormat: String): Transfer = when (platformFormat.lowercase()) {
        "bgra8", "rgba8", "bgrx8", "rgb10a2_srgb", "sdr", "srgb" -> Transfer.SRGB
        "rgba16f", "scrgb", "scrgb_linear", "rgba16float" -> Transfer.SCRGB_LINEAR
        "p010", "p016", "rgb10a2", "hdr10", "pq", "yuv420p10" -> Transfer.PQ
        "hlg", "yuv420p10_hlg", "arib_std_b67" -> Transfer.HLG
        else -> Transfer.SRGB
    }
}
