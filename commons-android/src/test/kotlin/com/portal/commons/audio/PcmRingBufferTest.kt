package com.portal.commons.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmRingBufferTest {

    @Test fun dropsOldestWhenAtCapacity() {
        val buf = PcmRingBuffer(maxFrames = 2)
        buf.add(byteArrayOf(1), 1)
        buf.add(byteArrayOf(2), 1)
        buf.add(byteArrayOf(3), 1)
        assertEquals(listOf(2, 3), buf.drain().map { it[0].toInt() })
    }

    @Test fun drainReturnsInsertionOrder() {
        val buf = PcmRingBuffer(maxFrames = 4)
        buf.add(byteArrayOf(10), 1)
        buf.add(byteArrayOf(20), 1)
        assertEquals(listOf(10, 20), buf.drain().map { it[0].toInt() })
        assertTrue(buf.drain().isEmpty())
    }

    @Test fun copiesPartialFrame() {
        val buf = PcmRingBuffer(maxFrames = 1)
        val frame = byteArrayOf(1, 2, 3, 4)
        buf.add(frame, 2)
        assertEquals(2, buf.drain().single().size)
    }
}
