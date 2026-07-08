package com.portal.commons.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure helpers of [OpenWakeWordDetector] — the bits with real bug surface that don't need
 * ONNX / Android: which wake id the fixed model owns in a discovered set, the melspec time-axis extraction
 * (the exact spot a shape-parsing bug once hid), and the PCM byte decode.
 */
class OpenWakeWordDetectorTest {

    private fun word(phrase: String, id: String? = null) =
        WakeWord.fromPhrase(phrase, id = id, minConf = 0.5)!!

    // ---- ownedWakeId (the routing/firing id, shared with WakeService) ------------------------------

    @Test fun ownsTheJarvisWordByPhrase() {
        val words = listOf(word("alexa"), word("hey jarvis"))
        assertEquals("jarvis", OpenWakeWordDetector.ownedWakeId(words))
    }

    @Test fun ownsByPhraseEvenWhenIdIsRemapped() {
        // A plugin that keeps the phrase "hey jarvis" but overrides the id must still be matched by phrase —
        // this is what stops a double-fire (oww fires this id; the router suppresses Vosk's fire of the same id).
        val words = listOf(word("alexa"), word("hey jarvis", id = "myassistant"))
        assertEquals("myassistant", OpenWakeWordDetector.ownedWakeId(words))
    }

    @Test fun fallsBackToIdMatchWhenPhraseDiffers() {
        // No literal "hey jarvis" phrase, but a word already keyed "jarvis" (e.g. a bare "jarvis" declaration).
        val words = listOf(word("alexa"), word("jarvis"))
        assertEquals("jarvis", OpenWakeWordDetector.ownedWakeId(words))
    }

    @Test fun ownsNothingWhenNoJarvisWord() {
        assertNull(OpenWakeWordDetector.ownedWakeId(listOf(word("alexa"), word("hey computer"))))
        assertNull(OpenWakeWordDetector.ownedWakeId(emptyList()))
    }

    @Test fun prefersPhraseMatchOverAStrayJarvisId() {
        // Both present: a real "hey jarvis" phrase (id "jarvis") and an unrelated word mis-keyed "jarvis".
        // The phrase match wins so oww routes the acoustically-correct target.
        val words = listOf(word("hey vega", id = "jarvis"), word("hey jarvis"))
        assertEquals("jarvis", OpenWakeWordDetector.ownedWakeId(words))
    }

    // ---- melTimeSteps (guards the [1,1,T,32] shape-parsing regression) -----------------------------

    @Test fun melTimeStepsReadsSecondToLastDim() {
        assertEquals(8, OpenWakeWordDetector.melTimeSteps(longArrayOf(1, 1, 8, 32)))
        assertEquals(5, OpenWakeWordDetector.melTimeSteps(longArrayOf(1, 5, 32)))
        assertEquals(3, OpenWakeWordDetector.melTimeSteps(longArrayOf(3, 32)))
    }

    @Test fun melTimeStepsZeroForDegenerateShape() {
        assertEquals(0, OpenWakeWordDetector.melTimeSteps(longArrayOf()))
        assertEquals(0, OpenWakeWordDetector.melTimeSteps(longArrayOf(32)))
    }

    // ---- pcm16ToShorts (little-endian decode) ------------------------------------------------------

    @Test fun decodesLittleEndianSignedPcm() {
        // 0x0001 = 1 ; 0xFFFF = -1 ; 0x0080 -> 0x8000 = -32768 ; 0x7FFF = 32767
        val buf = byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F)
        assertArrayEquals(shortArrayOf(1, -1, -32768, 32767), OpenWakeWordDetector.pcm16ToShorts(buf, buf.size))
    }

    @Test fun decodesOnlyTheFirstNBytes() {
        // n shorter than the buffer: trailing bytes are ignored (matches the engine's partial-frame contract).
        val buf = byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x7F, 0x7F)
        assertArrayEquals(shortArrayOf(1, 2), OpenWakeWordDetector.pcm16ToShorts(buf, 4))
    }
}
