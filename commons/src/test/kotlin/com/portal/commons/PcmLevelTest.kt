package com.portal.commons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** RMS / level math on little-endian 16-bit mono PCM. */
class PcmLevelTest {

    /** A buffer of [count] samples, each the signed 16-bit value [v], little-endian. */
    private fun pcm(v: Int, count: Int): ByteArray {
        val b = ByteArray(count * 2)
        for (i in 0 until count) {
            b[i * 2] = (v and 0xff).toByte()
            b[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return b
    }

    /** A buffer from explicit signed sample values. */
    private fun pcm(vararg samples: Int): ByteArray {
        val b = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            b[i * 2] = (samples[i] and 0xff).toByte()
            b[i * 2 + 1] = ((samples[i] shr 8) and 0xff).toByte()
        }
        return b
    }

    @Test fun silenceIsZero() {
        assertEquals(0.0, PcmLevel.rms(pcm(0, 100)), 1e-9)
        assertEquals(0f, PcmLevel.normalized(pcm(0, 100)), 1e-6f)
    }

    @Test fun constantAmplitudeRmsEqualsAmplitude() {
        assertEquals(1000.0, PcmLevel.rms(pcm(1000, 50)), 1e-6)
        assertEquals(1000.0, PcmLevel.rms(pcm(-1000, 50)), 1e-6) // sign-extension handled
    }

    @Test fun extremesAreReconstructedCorrectly() {
        assertEquals(32767.0, PcmLevel.rms(pcm(32767, 16)), 1e-6) // max positive
        assertEquals(32768.0, PcmLevel.rms(pcm(-32768, 16)), 1e-6) // min negative
    }

    @Test fun rmsMatchesHandComputedValue() {
        // samples {30, -40, 0, 50} → sqrt((900+1600+0+2500)/4) = sqrt(1250)
        assertEquals(sqrt(1250.0), PcmLevel.rms(pcm(30, -40, 0, 50)), 1e-9)
    }

    @Test fun normalizedScalesAndClamps() {
        assertEquals(1000.0 / PcmLevel.FULL_SCALE, PcmLevel.normalized(pcm(1000, 20)).toDouble(), 1e-4)
        assertEquals(1f, PcmLevel.normalized(pcm(32000, 20)), 1e-6f) // way over full-scale → clamps to 1
        assertEquals(0.5f, PcmLevel.normalized(pcm((PcmLevel.FULL_SCALE / 2).toInt(), 20)), 1e-3f)
    }

    @Test fun lengthParameterBoundsTheScan() {
        // 4 loud samples followed by garbage; only the first 2 samples (4 bytes) are measured.
        val buf = pcm(1000, 1000, 9999, 9999)
        assertEquals(1000.0, PcmLevel.rms(buf, length = 4), 1e-6)
    }

    @Test fun lengthBeyondBufferIsClamped() {
        val buf = pcm(700, 4)
        assertEquals(700.0, PcmLevel.rms(buf, length = 9999), 1e-6) // length capped at buf.size
    }

    @Test fun emptyOrSubSampleBufferIsZero() {
        assertEquals(0.0, PcmLevel.rms(ByteArray(0)), 1e-9)
        assertEquals(0.0, PcmLevel.rms(ByteArray(1)), 1e-9) // odd trailing byte ignored
        assertEquals(0.0, PcmLevel.rms(pcm(1000, 3), length = 1), 1e-9) // sub-sample length
    }

    @Test fun oddTrailingByteIsIgnoredNotMisread() {
        val twoSamples = pcm(800, 2) // 4 bytes
        val plusStrayByte = twoSamples + byteArrayOf(0x7f) // 5 bytes; trailing byte must be dropped
        assertEquals(PcmLevel.rms(twoSamples), PcmLevel.rms(plusStrayByte), 1e-9)
    }

    @Test fun normalizedNeverEscapesUnitRange() {
        assertTrue(PcmLevel.normalized(pcm(-32768, 8)) <= 1f)
        assertTrue(PcmLevel.normalized(pcm(0, 8)) >= 0f)
    }
}
