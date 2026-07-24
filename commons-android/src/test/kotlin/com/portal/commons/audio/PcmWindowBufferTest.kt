package com.portal.commons.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rolling window [TwoStageWakeDetector] hands to stage 2. */
class PcmWindowBufferTest {

    /** Little-endian s16 bytes for [samples], as `AudioRecord` delivers them. */
    private fun bytes(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test fun decodesLittleEndianSignedPcm() {
        val b = PcmWindowBuffer(4)
        val buf = bytes(1, -1, -32768, 32767)
        b.add(buf, buf.size)
        assertArrayEquals(shortArrayOf(1, -1, -32768, 32767), b.snapshot())
    }

    @Test fun snapshotIsOldestFirstBeforeWrapping() {
        val b = PcmWindowBuffer(8)
        val buf = bytes(10, 20, 30)
        b.add(buf, buf.size)
        assertArrayEquals(shortArrayOf(10, 20, 30), b.snapshot())
    }

    @Test fun partialWindowReturnsOnlyWhatWasCaptured() {
        // An early candidate must be verified against real audio, not leading zeros that would decode as
        // silence and reject a genuine wake spoken in the first two seconds.
        val b = PcmWindowBuffer(100)
        val buf = bytes(7, 8)
        b.add(buf, buf.size)
        assertEquals(2, b.snapshot().size)
        assertFalse(b.isFull)
    }

    @Test fun keepsTheNewestSamplesOnceFull() {
        val b = PcmWindowBuffer(4)
        val buf = bytes(1, 2, 3, 4, 5, 6)
        b.add(buf, buf.size)
        assertTrue(b.isFull)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6), b.snapshot())
    }

    @Test fun ordersCorrectlyAcrossManyWraps() {
        val b = PcmWindowBuffer(3)
        repeat(10) { i ->
            val buf = bytes(i)
            b.add(buf, buf.size)
        }
        assertArrayEquals(shortArrayOf(7, 8, 9), b.snapshot())
    }

    @Test fun honoursTheByteCountAndIgnoresTrailingGarbage() {
        // AudioRecord's read() reports how many bytes are valid; the rest of the buffer is stale audio from
        // the previous read and must not enter the window.
        val b = PcmWindowBuffer(4)
        val buf = bytes(11, 22, 999, 888)
        b.add(buf, 4) // only the first two samples are valid
        assertArrayEquals(shortArrayOf(11, 22), b.snapshot())
    }

    @Test fun ignoresAnOddTrailingByte() {
        val b = PcmWindowBuffer(4)
        val buf = bytes(11, 22)
        b.add(buf, 3) // one and a half samples
        assertArrayEquals(shortArrayOf(11), b.snapshot())
    }

    @Test fun snapshotDoesNotAliasTheRing() {
        // Stage 2 hands the snapshot to native code while the capture thread keeps writing; if they shared
        // storage the decode would read audio recorded after the candidate.
        val b = PcmWindowBuffer(2)
        var buf = bytes(1, 2)
        b.add(buf, buf.size)
        val snap = b.snapshot()
        buf = bytes(3, 4)
        b.add(buf, buf.size)
        assertArrayEquals(shortArrayOf(1, 2), snap)
    }

    @Test fun clearDropsEverythingSoAudioCannotLeakAcrossSessions() {
        val b = PcmWindowBuffer(2)
        val buf = bytes(1, 2)
        b.add(buf, buf.size)
        b.clear()
        assertFalse(b.isFull)
        assertEquals(0, b.snapshot().size)
    }

    @Test fun refillsCorrectlyAfterClear() {
        val b = PcmWindowBuffer(2)
        var buf = bytes(1, 2)
        b.add(buf, buf.size)
        b.clear()
        buf = bytes(9)
        b.add(buf, buf.size)
        assertArrayEquals(shortArrayOf(9), b.snapshot())
    }
}
