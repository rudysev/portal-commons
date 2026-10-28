package com.portal.commons.audio

import com.portal.commons.audio.ScriptedDecoder.Companion.frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The wake decoder, tested through [WakeDecoder.create] and its interface. Native libvosk is replaced at the seam by
 * [FakeVoskModel] / [ScriptedDecoder], so grammar building, warm-up, JSON parsing and the matcher run for real. Real
 * `org.json` on the JVM (see `testOptions` in build.gradle.kts).
 */
class WakeDecoderTest {

    private val model = FakeVoskModel()
    private val diagnostics = CopyOnWriteArrayList<String>()

    private var now = 0L

    private fun wakeDecoder(words: List<WakeWord> = listOf(JARVIS)): WakeDecoder? =
        WakeDecoder.create(model, words, { diagnostics.add(it) }, clock = { now })

    private fun ready(words: List<WakeWord> = listOf(JARVIS)): WakeDecoder = checkNotNull(wakeDecoder(words))

    /** Feed one finalizing frame that decodes [json]; returns the wake [WakeDecoder.accept] returned, if any. */
    private fun WakeDecoder.decode(json: String): WakeEvent? {
        model.finals.add(json)
        return accept(frame(3, endpoint = true), FRAME)
    }

    // ---- final-result decoding → wake / near-miss / flushed final -----------------------------------

    @Test fun confidentWakeIsReturnedWithItsDecode() {
        assertEquals(wake("jarvis", "hey(97) jarvis(81)"), ready().decode(HEY_JARVIS_CONFIDENT))
        assertEquals(emptyList<String>(), diagnostics)
    }

    @Test fun textOnlyDecodeHasNoConfidence_soOnlyALenientRouteFires() {
        assertNull(ready().decode("""{"text":"hey jarvis"}"""))
        assertEquals(listOf("near-miss [hey jarvis] rejected: 'hey' under 0.8 floor"), diagnostics)
    }

    @Test fun textOnlyDecodeFiresALenientRoute() {
        assertEquals(wake("jarvis", "hey jarvis"), ready(listOf(JARVIS_LENIENT)).decode("""{"text":"hey jarvis"}"""))
    }

    @Test fun lowConfidenceCleanPhraseFiresALenientRoute() {
        val returned = ready(listOf(JARVIS_LENIENT))
            .decode("""{"result":[{"conf":0.9,"word":"hey"},{"conf":0.2,"word":"jarvis"}],"text":"hey jarvis"}""")
        assertEquals(wake("jarvis", "hey(90) jarvis(20)"), returned)
    }

    @Test fun emptyOrMalformedDecodesReportNothing() {
        val d = ready()
        assertNull(d.decode("""{"text":""}"""))
        assertNull(d.decode("""{"text":"   "}"""))
        assertNull(d.decode("""{"result":[]}"""))
        assertNull(d.decode("""{}"""))
        assertNull(d.decode("not json"))
        assertEquals(emptyList<String>(), diagnostics)
    }

    @Test fun nearMissReportsTheDecodeAndTheGate() {
        val returned = ready().decode(
            """{"result":[{"conf":0.51,"word":"[unk]"},{"conf":0.99,"word":"hey"},{"conf":0.99,"word":"jarvis"}]}""",
        )
        assertNull(returned)
        assertEquals(
            listOf("near-miss [[unk](51) hey(99) jarvis(99)] rejected: contaminated ('[unk]' in decode)"),
            diagnostics,
        )
    }

    @Test fun keywordFreeDecodeIsReportedAsAFlushedFinal() {
        assertNull(ready().decode("""{"result":[{"conf":0.99,"word":"hey"}],"text":"hey"}"""))
        assertEquals(listOf("flushed final [hey(99)]"), diagnostics)
    }

    @Test fun aFrameThatDoesNotFinalizeReportsNothing() {
        assertNull(ready().accept(frame(2), FRAME))
        assertEquals(emptyList<String>(), diagnostics)
    }

    // ---- partial-result decoding: only a non-empty partial holds the idle reset off -----------------

    @Test fun anyNonEmptyPartialDefersTheIdleReset() {
        val d = ready()
        listOf("""{"partial" : "hey jar"}""", """{"partial" : "[unk]"}""").forEach {
            model.live.partial = it
            now += IDLE
            d.accept(frame(2), FRAME)
        }
        assertEquals(emptyList<String>(), diagnostics)
    }

    @Test fun quietEmptyOrUnreadablePartialsDoNotDefer() {
        val d = ready()
        val quiet = listOf("""{"partial" : ""}""", """{"partial" : "   "}""", """{}""", "not json")
        quiet.forEach {
            model.live.partial = it
            now += IDLE
            d.accept(frame(2), FRAME)
        }
        assertEquals(List(quiet.size) { IDLE_RESET }, diagnostics)
    }

    // ---- grammar ------------------------------------------------------------------------------------

    @Test fun grammarListsEachPhraseKeywordAndLeadThenUnk() {
        ready(listOf(JARVIS, ALEXA, COMPUTER))
        assertEquals(
            """["hey jarvis", "jarvis", "hey", "hey alexa", "alexa", "computer", "[unk]"]""",
            model.decoders.single().grammar,
        )
    }

    @Test fun rejectedGrammarFallsBackToOpenVocabulary() {
        model.rejectGrammar = true
        val d = ready()
        assertNull(model.decoders.single().grammar)
        // the matcher still runs over the open-vocabulary decode
        assertEquals(wake("jarvis", "hey(97) jarvis(81)"), d.decode(HEY_JARVIS_CONFIDENT))
    }

    // ---- warm-up ------------------------------------------------------------------------------------

    @Test fun theFirstDecoderIsWarmedWithTenSilentFrames() {
        ready().accept(frame(2), FRAME)
        assertEquals(warmUp() + "frame:2", model.live.log)
    }

    @Test fun resetRewarmsBeforeTheNextFrame() {
        val d = ready()
        d.accept(frame(2), FRAME)
        d.reset()
        d.accept(frame(3), FRAME)
        assertEquals(warmUp() + "frame:2" + "reset" + warmUp() + "frame:3", model.live.log)
    }

    // ---- wake-word swap -----------------------------------------------------------------------------

    @Test fun swapBuildsAWarmedDecoderAndClosesTheOld() {
        val d = ready()
        val old = model.live
        d.updateWakeWords(listOf(ALEXA))

        assertTrue(old.closed)
        assertEquals(ALEXA_GRAMMAR, model.live.grammar)
        assertEquals(warmUp(), model.live.log)
        assertEquals(wake("alexa", "hey(99) alexa(99)"), d.decode(HEY_ALEXA))
    }

    @Test fun failedSwapKeepsTheOldDecoderAndTheOldWakeWords() {
        val d = ready()
        val old = model.live
        model.failBuilds = true
        d.updateWakeWords(listOf(ALEXA))

        assertFalse(old.closed)
        assertEquals(listOf(old), model.decoders.toList())
        assertNull(d.decode(HEY_ALEXA)) // the matcher still runs the old set, in step with the live grammar
        assertEquals(wake("jarvis", "hey(97) jarvis(81)"), d.decode(HEY_JARVIS_CONFIDENT))
        assertEquals(listOf("flushed final [hey(99) alexa(99)]"), diagnostics)
        assertEquals(listOf(3, 3), old.fedTags())
    }

    @Test fun swapRejectedByTheModelFallsBackToOpenVocabulary() {
        val d = ready()
        model.rejectGrammar = true
        d.updateWakeWords(listOf(ALEXA))

        assertNull(model.live.grammar)
        assertEquals(wake("alexa", "hey(99) alexa(99)"), d.decode(HEY_ALEXA))
    }

    // ---- create / close -----------------------------------------------------------------------------

    @Test fun failedFirstBuildIsNullAndClosesTheModel() {
        model.failBuilds = true
        assertNull(wakeDecoder())
        assertTrue(model.decoders.isEmpty())
        assertEquals(1, model.closeCount)
    }

    @Test fun closeFreesTheDecoderAndTheModel() {
        ready().close()
        assertTrue(model.live.closed)
        assertEquals(1, model.closeCount)
    }

    private fun wake(wakeId: String, transcript: String) = WakeEvent(VoskWakeDetector.ID, wakeId, transcript)

    private fun warmUp() = List(WakeDecoder.WARMUP_SILENCE_FRAMES) { "silence" }

    private companion object {
        const val IDLE = 25_000L
        const val IDLE_RESET = "idle reset (bounding Vosk lattice)"

        val JARVIS_LENIENT = WakeWord.fromPhrase("hey jarvis", id = "jarvis", minConf = WakeMatcher.BASELINE_CONF)!!
        val COMPUTER = WakeWord.fromPhrase("computer", minConf = WakeMatcher.BASELINE_CONF)!!

        const val HEY_ALEXA = """{"result":[{"conf":0.99,"word":"hey"},{"conf":0.99,"word":"alexa"}]}"""
    }
}
