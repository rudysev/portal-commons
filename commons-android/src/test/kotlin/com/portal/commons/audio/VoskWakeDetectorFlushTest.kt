package com.portal.commons.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class VoskWakeDetectorFlushTest {

    @Test fun flushStopsOnceCooldownArms() {
        val acceptedSizes = mutableListOf<Int>()
        var cooling = false
        val frames = listOf(ByteArray(10), ByteArray(20), ByteArray(30))

        VoskWakeDetector.flushBufferedFrames(
            frames = frames,
            isAnyCoolingDown = { cooling },
            acceptFrame = { _, n ->
                acceptedSizes.add(n)
                // First match arms cooldown synchronously, as WakeMicEventHandler.onWake does.
                cooling = true
            },
        )

        assertEquals(listOf(10), acceptedSizes)
    }

    @Test fun flushProcessesAllFramesWhenNeverCooling() {
        val acceptedSizes = mutableListOf<Int>()
        val frames = listOf(ByteArray(10), ByteArray(20), ByteArray(30))

        VoskWakeDetector.flushBufferedFrames(
            frames = frames,
            isAnyCoolingDown = { false },
            acceptFrame = { _, n -> acceptedSizes.add(n) },
        )

        assertEquals(listOf(10, 20, 30), acceptedSizes)
    }

    @Test fun flushSkipsAllFramesWhenAlreadyCooling() {
        val acceptedSizes = mutableListOf<Int>()
        val frames = listOf(ByteArray(10), ByteArray(20))

        VoskWakeDetector.flushBufferedFrames(
            frames = frames,
            isAnyCoolingDown = { true },
            acceptFrame = { _, n -> acceptedSizes.add(n) },
        )

        assertEquals(emptyList<Int>(), acceptedSizes)
    }
}
