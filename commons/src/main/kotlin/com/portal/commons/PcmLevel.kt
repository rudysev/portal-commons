package com.portal.commons

import kotlin.math.sqrt

/**
 * Loudness math for little-endian 16-bit mono PCM, shared by the Portal apps.
 *
 * Used for a recording-level indicator (the orange bar) via [normalized]; [rms] exposes the raw
 * amplitude for any other level-based check.
 *
 * **Format assumptions:** little-endian, signed samples [PcmCaptureFormat.BYTES_PER_SAMPLE] bytes wide
 * (16-bit today — the reconstruction below is intrinsically 16-bit; widening the format means revisiting
 * it, which [check] guards). Mono is assumed; [rms] always reads from index 0 of [buf], so callers with
 * ring buffers must copy or slice the window they want measured.
 *
 * Samples are reconstructed as signed 16-bit: `(low and 0xff) or (high shl 8)`, where the high byte
 * sign-extends — so negative samples are handled correctly.
 */
object PcmLevel {

    init {
        // The sample reconstruction below hard-assumes 16-bit. Fail loudly at load if the shared format
        // is widened, rather than silently returning wrong levels.
        check(PcmCaptureFormat.BYTES_PER_SAMPLE == 2) {
            "PcmLevel assumes 16-bit samples; PcmCaptureFormat.BYTES_PER_SAMPLE=${PcmCaptureFormat.BYTES_PER_SAMPLE}"
        }
    }

    /** Input RMS that maps to a full-scale (1.0) indicator reading in [normalized]. */
    const val FULL_SCALE = 6_000.0

    /**
     * RMS amplitude of the first [length] bytes of [buf] (little-endian 16-bit PCM). Odd trailing
     * bytes are ignored; returns 0.0 for an empty/sub-sample buffer.
     */
    fun rms(buf: ByteArray, length: Int = buf.size): Double {
        val stride = PcmCaptureFormat.BYTES_PER_SAMPLE
        val n = minOf(length, buf.size).let { it - (it % stride) }
        if (n <= 0) return 0.0
        // Integer accumulation: a 16-bit sample s has |s| <= 32768, so s*s <= 2^30 < Int.MAX (no Int
        // overflow), and the Long sum can't overflow until ~8.6e9 samples (vs 1600 per frame). This is
        // faster than per-sample Double conversion and exact — no floating-point accumulation error.
        var sum = 0L
        var i = 0
        while (i < n) {
            val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
            sum += (s * s).toLong()
            i += stride
        }
        return sqrt(sum.toDouble() / (n / stride))
    }

    /** [rms] mapped to a 0..1 indicator level (full scale = [FULL_SCALE]). */
    fun normalized(buf: ByteArray, length: Int = buf.size): Float = (rms(buf, length) / FULL_SCALE).toFloat().coerceIn(0f, 1f)
}
