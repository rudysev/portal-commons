package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure helpers of [OpenWakeWordDetector]. */
class OpenWakeWordDetectorTest {

    private fun word(phrase: String, id: String? = null) =
        WakeWord.fromPhrase(phrase, id = id, minConf = WakeMatcher.BASELINE_CONF)!!

    @Test fun builtinAssetForJarvis() {
        assertEquals(OpenWakeWordDetector.JARVIS_ASSET, OpenWakeWordDetector.builtinAssetFor(word("hey jarvis")))
    }

    @Test fun builtinAssetForAlexa() {
        assertEquals(OpenWakeWordDetector.ALEXA_ASSET, OpenWakeWordDetector.builtinAssetFor(word("hey alexa", id = "alexa")))
    }

    @Test fun builtinAssetNullForUnknownWord() {
        assertNull(OpenWakeWordDetector.builtinAssetFor(word("hey computer")))
    }

    @Test fun ownedWakeIdFindsJarvisPhrase() {
        val words = listOf(word("hey jarvis"), word("hey alexa", id = "alexa"))
        assertEquals("jarvis", OpenWakeWordDetector.ownedWakeId(words))
    }

    @Test fun melTimeStepsReadsSecondToLastDim() {
        assertEquals(8, OpenWakeWordDetector.melTimeSteps(longArrayOf(1, 1, 8, 32)))
        assertEquals(5, OpenWakeWordDetector.melTimeSteps(longArrayOf(1, 5, 32)))
    }

    @Test fun melTimeStepsZeroForDegenerateShape() {
        assertEquals(0, OpenWakeWordDetector.melTimeSteps(longArrayOf()))
        assertEquals(0, OpenWakeWordDetector.melTimeSteps(longArrayOf(32)))
    }

    @Test fun decodesLittleEndianSignedPcm() {
        val buf = byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F)
        assertArrayEquals(shortArrayOf(1, -1, -32768, 32767), OpenWakeWordDetector.pcm16ToShorts(buf, buf.size))
    }

    @Test fun inferenceStepIs80msNotCaptureFrameMs() {
        assertEquals(80, OpenWakeWordDetector.INFERENCE_STEP_MS)
        assertEquals(100, PcmCaptureFormat.FRAME_MS)
        assertEquals(1_280, PcmCaptureFormat.SAMPLE_RATE * OpenWakeWordDetector.INFERENCE_STEP_MS / 1000)
    }

    @Test fun preReadyBufferCoversBudget() {
        val frames = OpenWakeWordDetector.preReadyMaxFrames()
        assertEquals(50, frames) // 5_000 ms / 100 ms per frame, rounded up
        assertTrue(frames * PcmCaptureFormat.FRAME_MS >= OpenWakeWordDetector.PRE_READY_BUDGET_MS)
    }

    @Test fun buildBundledThresholdUsesScoreThresholdField() {
        val word = WakeWord("jarvis", "jarvis", "hey", minConf = 0.99, scoreThreshold = 0.31)
        assertEquals(0.31f, word.scoreThreshold.toFloat().coerceIn(0f, 1f))
    }
}
