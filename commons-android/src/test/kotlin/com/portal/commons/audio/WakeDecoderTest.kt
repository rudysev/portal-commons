package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat
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

    /** Feed one non-finalizing frame at [ms]. */
    private fun WakeDecoder.frameAt(ms: Long, tag: Int = 2) {
        now = ms
        accept(frame(tag), FRAME)
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

    // ---- idle reset: the timer ----------------------------------------------------------------------

    @Test fun keepsBeforeIdleElapses() {
        val d = ready()
        d.frameAt(0)
        d.frameAt(IDLE - 1)
        assertEquals(emptyList<String>(), diagnostics)
    }

    @Test fun resetsAtIdleWhenDecoderQuiet_thenWarmsUpBeforeTheFrame() {
        val d = ready()
        d.frameAt(IDLE, tag = 7)
        assertEquals(listOf(IDLE_RESET), diagnostics)
        assertEquals(warmUp() + "reset" + warmUp() + "frame:7", model.live.log)
    }

    @Test fun captureRestartRestartsTheClock() {
        val d = ready()
        now = 10_000
        d.reset()
        d.frameAt(10_000 + IDLE - 1)
        assertEquals(emptyList<String>(), diagnostics)
        d.frameAt(10_000 + IDLE)
        assertEquals(listOf(IDLE_RESET), diagnostics)
    }

    @Test fun wakeWordSwapRestartsTheClock() {
        val d = ready()
        now = 10_000
        d.updateWakeWords(listOf(ALEXA))
        d.frameAt(10_000 + IDLE - 1)
        assertEquals(emptyList<String>(), diagnostics)
        d.frameAt(10_000 + IDLE)
        assertEquals(listOf(IDLE_RESET), diagnostics)
    }

    // ---- idle reset: natural endpoints keep pushing it back -----------------------------------------

    @Test fun naturalEndpointsKeepDeferringTheReset() {
        val d = ready()
        // A quiet room endpoints every ~5 s; each one restarts the clock, so the reset never becomes due no
        // matter how long capture runs (100 s here, well past the force cap).
        repeat(20) {
            now += 5_000
            d.accept(frame(3, endpoint = true), FRAME)
        }
        assertEquals(emptyList<String>(), diagnostics)
        // Endpoints stop (continuous non-endpointing audio) → due IDLE after the last one.
        val lastEndpoint = now
        d.frameAt(lastEndpoint + IDLE - 1)
        assertEquals(emptyList<String>(), diagnostics)
        d.frameAt(lastEndpoint + IDLE)
        assertEquals(listOf(IDLE_RESET), diagnostics)
    }

    // ---- idle reset: never mid-utterance (until the backstop) ---------------------------------------

    @Test fun defersWhileAnyPartialIsInFlight() {
        val d = ready()
        model.live.partial = """{"partial" : "hey jar"}"""
        d.frameAt(IDLE)
        // A bare "[unk]" partial still counts — a real "hey" may still be settling.
        model.live.partial = """{"partial" : "[unk]"}"""
        d.frameAt(IDLE + 1)
        assertEquals(emptyList<String>(), diagnostics)
    }

    @Test fun quietEmptyOrUnreadablePartialsDoNotDefer() {
        val d = ready()
        val quiet = listOf("""{"partial" : ""}""", """{"partial" : "   "}""", """{}""", "not json")
        quiet.forEach {
            model.live.partial = it
            d.frameAt(now + IDLE)
        }
        assertEquals(List(quiet.size) { IDLE_RESET }, diagnostics)
    }

    @Test fun deferredResetFiresAtTheNextQuietMoment() {
        val d = ready()
        model.live.partial = """{"partial" : "hey jar"}"""
        d.frameAt(IDLE) // "hey jar…" in flight — hold
        assertEquals(emptyList<String>(), diagnostics)
        model.live.partial = """{"partial" : ""}"""
        d.frameAt(IDLE + 900) // utterance over — reset now
        assertEquals(listOf(IDLE_RESET), diagnostics)
    }

    @Test fun forcesResetPastBackstopEvenMidUtterance() {
        val d = ready()
        model.live.partial = """{"partial" : "hey jar"}"""
        d.frameAt(FORCE - 1)
        assertEquals(emptyList<String>(), diagnostics)
        d.frameAt(FORCE)
        assertEquals(listOf(FORCED_RESET), diagnostics)
    }

    @Test fun quietResetPastBackstopIsAnOrdinaryReset() {
        // "forced mid-decode" means "clipped speech is possible" in the log — a quiet late reset isn't that.
        ready().frameAt(FORCE + 1)
        assertEquals(listOf(IDLE_RESET), diagnostics)
    }

    @Test fun clockRestartsAfterQuietReset() {
        val d = ready()
        d.frameAt(IDLE)
        d.frameAt(IDLE + IDLE - 1)
        assertEquals(listOf(IDLE_RESET), diagnostics)
        d.frameAt(IDLE + IDLE)
        assertEquals(listOf(IDLE_RESET, IDLE_RESET), diagnostics)
    }

    @Test fun clockRestartsAfterForcedReset() {
        val d = ready()
        model.live.partial = """{"partial" : "hey jar"}"""
        d.frameAt(FORCE)
        d.frameAt(FORCE + IDLE - 1)
        d.frameAt(FORCE + IDLE) // deferred again, not forced
        assertEquals(listOf(FORCED_RESET), diagnostics)
        d.frameAt(FORCE + FORCE)
        assertEquals(listOf(FORCED_RESET, FORCED_RESET), diagnostics)
    }

    @Test fun partialIsQueriedOnlyOnceTheResetIsDue() {
        // The partial is a native call; it must not be made on every frame.
        val d = ready()
        d.frameAt(0)
        d.frameAt(IDLE - 1)
        assertEquals(0, model.live.partialQueries)
        d.frameAt(IDLE)
        assertEquals(1, model.live.partialQueries)
    }

    // ---- grammar ------------------------------------------------------------------------------------

    @Test fun grammarListsEachPhraseLeadAndDecoysThenUnk_andABareKeywordOnlyForALeadlessWord() {
        ready(listOf(JARVIS, ALEXA, COMPUTER))
        val entries =
            listOf("hey jarvis", "hey") + WakeMatcher.decoysFor(JARVIS) + listOf("hey alexa", "computer", "[unk]")
        assertEquals(entries.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }, model.decoders.single().grammar)
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

    // ---- audit clips -------------------------------------------------------------------------------

    private val clips = CopyOnWriteArrayList<Pair<String, List<Int>>>()

    private fun audited(): WakeDecoder = checkNotNull(
        WakeDecoder.create(model, listOf(JARVIS), { diagnostics.add(it) }, clock = { now }) { clip, word, pcm ->
            clips.add("${clip.prefix}_$word" to tags(pcm))
        },
    )

    /** The tag of each frame in a clip's PCM. */
    private fun tags(pcm: ByteArray) = (pcm.indices step FRAME).map { pcm[it].toInt() }

    @Test fun aWakeSavesTheAudioEndingWithItsFinalizingFrame() {
        val d = audited()
        d.accept(frame(5), FRAME)
        d.decode(HEY_JARVIS_CONFIDENT)
        assertEquals(listOf("wake_jarvis" to listOf(5, 3)), clips)
    }

    @Test fun aNearMissSavesUnderItsKeyword() {
        audited().decode("""{"result":[{"conf":0.99,"word":"jarvis"}],"text":"jarvis"}""")
        assertEquals(listOf("near_jarvis" to listOf(3)), clips)
    }

    @Test fun aKeywordFreeDecodeSavesNothing() {
        audited().decode("""{"result":[{"conf":0.99,"word":"[unk]"}],"text":"[unk]"}""")
        assertEquals(emptyList<Pair<String, List<Int>>>(), clips)
    }

    @Test fun theClipHoldsOnlyTheLastClipMs() {
        val d = audited()
        val kept = WakeAudit.CLIP_MS / PcmCaptureFormat.FRAME_MS
        (1..kept).forEach { d.accept(frame(10 + it), FRAME) }
        d.decode(HEY_JARVIS_CONFIDENT)
        assertEquals((12..10 + kept).toList() + 3, clips.single().second)
    }

    @Test fun aMarkedMissSavesTheLastMinute() {
        val d = audited()
        val kept = WakeAudit.MISS_MS / PcmCaptureFormat.FRAME_MS
        d.accept(frame(5), FRAME)
        repeat(kept) { d.accept(frame(7), FRAME) }
        d.markMiss()
        assertEquals(listOf("miss_marked" to List(kept) { 7 }), clips)
        assertEquals(listOf("miss marked (60 s saved)"), diagnostics)
    }

    @Test fun aMissMarkedBeforeAnyAudioSavesNothing() {
        audited().markMiss()
        assertEquals(emptyList<Pair<String, List<Int>>>(), clips)
        assertEquals(listOf("miss mark ignored (no audio yet)"), diagnostics)
    }

    @Test fun aMissMarkedWithClipCaptureOffSavesNothing() {
        ready().markMiss()
        assertEquals(listOf("miss mark ignored (wake clips off)"), diagnostics)
    }

    @Test fun aCaptureRestartDropsAudioFromBeforeThePause() {
        val d = audited()
        d.accept(frame(5), FRAME)
        d.reset()
        d.decode(HEY_JARVIS_CONFIDENT)
        assertEquals(listOf(3), clips.single().second)
    }

    @Test fun aThrowingAuditNeverCostsTheWake() {
        val d = checkNotNull(
            WakeDecoder.create(model, listOf(JARVIS), { diagnostics.add(it) }, clock = { now }) { _, _, _ ->
                error("disk gone")
            },
        )
        assertEquals(wake("jarvis", "hey(97) jarvis(81)"), d.decode(HEY_JARVIS_CONFIDENT))
        assertEquals(listOf("wake clip dropped (disk gone)"), diagnostics)
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

    private fun wake(wakeId: String, transcript: String) = WakeEvent(wakeId, transcript)

    private fun warmUp() = List(WakeDecoder.WARMUP_SILENCE_FRAMES) { "silence" }

    private companion object {
        const val IDLE = 25_000L
        const val FORCE = 60_000L
        const val IDLE_RESET = "idle reset (bounding Vosk lattice)"
        const val FORCED_RESET = "idle reset forced mid-decode (bounding Vosk lattice)"

        val JARVIS_LENIENT = WakeWord.fromPhrase("hey jarvis", id = "jarvis", minConf = WakeMatcher.BASELINE_CONF)!!
        val COMPUTER = WakeWord.fromPhrase("computer", minConf = WakeMatcher.BASELINE_CONF)!!

        const val HEY_ALEXA = """{"result":[{"conf":0.99,"word":"hey"},{"conf":0.99,"word":"alexa"}]}"""
    }
}
