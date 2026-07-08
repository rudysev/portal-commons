package com.portal.commons.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the pure helpers of [OpenWakeWordDetector]. */
class OpenWakeWordDetectorTest {

    private fun word(phrase: String, id: String? = null) =
        WakeWord.fromPhrase(phrase, id = id, minConf = WakeWord.DEFAULT_MIN_CONF)!!

    @Test fun builtinAssetForJarvis() {
        assertEquals(OpenWakeWordDetector.JARVIS_ASSET, OpenWakeWordDetector.builtinAssetFor(word("hey jarvis")))
    }

    @Test fun builtinAssetForAlexa() {
        assertEquals(OpenWakeWordDetector.ALEXA_ASSET, OpenWakeWordDetector.builtinAssetFor(word("hey alexa", id = "alexa")))
    }

    @Test fun builtinAssetNullForUnknownWord() {
        assertNull(OpenWakeWordDetector.builtinAssetFor(word("hey computer")))
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
}
