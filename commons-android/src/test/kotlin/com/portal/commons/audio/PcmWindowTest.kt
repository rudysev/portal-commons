package com.portal.commons.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PcmWindowTest {

    private val w = PcmWindow(4)

    @Test fun keepsEverythingUntilFull() {
        w.add(bytes(1, 2, 9), 2)
        assertArrayEquals(bytes(1, 2), w.snapshot())
    }

    @Test fun wrapsKeepingTheNewestBytesOldestFirst() {
        w.add(bytes(1, 2, 3), 3)
        w.add(bytes(4, 5, 6), 3)
        assertArrayEquals(bytes(3, 4, 5, 6), w.snapshot())
    }

    @Test fun aWriteLongerThanTheWindowKeepsItsTail() {
        w.add(bytes(1, 2, 3, 4, 5, 6), 6)
        assertArrayEquals(bytes(3, 4, 5, 6), w.snapshot())
    }

    @Test fun clearEmptiesIt() {
        w.add(bytes(1, 2, 3), 3)
        w.clear()
        w.add(bytes(7), 1)
        assertArrayEquals(bytes(7), w.snapshot())
    }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
}
