package com.portal.commons.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behavioral parity tests for openWakeWord accept gating — pre-seam policy without ONNX.
 */
class OpenWakeWordDetectorAcceptTest {

    @Test fun flushBufferedFramesStopsOnHandoffCooldown() {
        val processed = mutableListOf<Int>()
        var cooling = false
        val frames = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))

        OpenWakeWordDetector.flushBufferedFrames(
            frames = frames,
            isAnyCoolingDown = { cooling },
            acceptFrame = { data, _ -> processed.add(data[0].toInt()) },
        )
        assertEquals(listOf(1, 2, 3), processed)

        processed.clear()
        cooling = true
        OpenWakeWordDetector.flushBufferedFrames(
            frames = frames,
            isAnyCoolingDown = { cooling },
            acceptFrame = { data, _ -> processed.add(data[0].toInt()) },
        )
        assertEquals(emptyList<Int>(), processed)
    }

    @Test fun flushBufferedFramesStopsMidDrainWhenHandoffArms() {
        val processed = mutableListOf<Int>()
        var coolingAfter = 2

        OpenWakeWordDetector.flushBufferedFrames(
            frames = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4)),
            isAnyCoolingDown = { --coolingAfter < 0 },
            acceptFrame = { data, _ -> processed.add(data[0].toInt()) },
        )
        assertEquals(listOf(1, 2), processed)
    }
}
