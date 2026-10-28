package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat
import com.portal.commons.audio.ScriptedDecoder.Companion.frame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Vosk detector, tested through the [WakeDetector] interface only. Native libvosk is replaced at the seam
 * ([VoskModelLoader] / [VoskModel] / [VoskDecoder]) by fakes, so grammar building, warm-up, JSON parsing, the
 * matcher, idle reset, the pre-ready buffer + replay and the grammar hot-swap all run for real. Real `org.json`
 * on the JVM (see `testOptions` in build.gradle.kts).
 *
 * Readiness is asserted only after a frame has been fed, so these tests hold whether the detector signals on
 * the model-load thread or at the next frame boundary.
 */
class VoskWakeDetectorTest {

    private val loader = FakeVoskLoader()
    private val model = FakeVoskModel()
    private val events = RecordingEvents()

    @Volatile private var now = 0L

    private fun detector(words: List<WakeWord> = listOf(JARVIS), loader: VoskModelLoader = this.loader) =
        VoskWakeDetector(loader, words, events, clock = { now })

    /** A detector whose model has loaded and which has been started at t=0, with the event log cleared. */
    private fun readyDetector(words: List<WakeWord> = listOf(JARVIS)): VoskWakeDetector = detector(words).also {
        loader.complete(model)
        it.start()
        it.accept(frame(TAG_PRIME), FRAME)
        events.lines.clear()
    }

    /** Feed one non-finalizing frame at [ms]. */
    private fun WakeDetector.frameAt(ms: Long, tag: Int = 2) {
        now = ms
        accept(frame(tag), FRAME)
    }

    /** Feed one finalizing frame that decodes [json]; returns the wake [WakeDetector.accept] returned, if any. */
    private fun WakeDetector.decode(json: String): WakeEvent? {
        model.finals.add(json)
        return accept(frame(3, endpoint = true), FRAME)
    }

    // ---- final-result decoding → wake / near-miss / flushed -----------------------------------------

    @Test fun confidentWakeIsReturnedWithItsDecode() {
        assertEquals(wake("jarvis", "hey(97) jarvis(81)"), readyDetector().decode(HEY_JARVIS_CONFIDENT))
        assertEquals(emptyList<String>(), events.lines)
    }

    @Test fun textOnlyDecodeHasNoConfidence_soOnlyALenientRouteFires() {
        assertNull(readyDetector().decode("""{"text":"hey jarvis"}"""))
        assertEquals(listOf("near-miss [hey jarvis] rejected: 'hey' under 0.8 floor"), events.lines)
    }

    @Test fun textOnlyDecodeFiresALenientRoute() {
        val returned = readyDetector(listOf(JARVIS_LENIENT)).decode("""{"text":"hey jarvis"}""")
        assertEquals(wake("jarvis", "hey jarvis"), returned)
    }

    @Test fun lowConfidenceCleanPhraseFiresALenientRoute() {
        val returned = readyDetector(listOf(JARVIS_LENIENT))
            .decode("""{"result":[{"conf":0.9,"word":"hey"},{"conf":0.2,"word":"jarvis"}],"text":"hey jarvis"}""")
        assertEquals(wake("jarvis", "hey(90) jarvis(20)"), returned)
    }

    @Test fun emptyOrMalformedDecodesReportNothing() {
        val d = readyDetector()
        assertNull(d.decode("""{"text":""}"""))
        assertNull(d.decode("""{}"""))
        assertNull(d.decode("not json"))
        assertEquals(emptyList<String>(), events.lines)
    }

    @Test fun nearMissReportsTheDecodeAndTheGate() {
        val returned = readyDetector().decode(
            """{"result":[{"conf":0.51,"word":"[unk]"},{"conf":0.99,"word":"hey"},{"conf":0.99,"word":"jarvis"}]}""",
        )
        assertNull(returned)
        assertEquals(
            listOf("near-miss [[unk](51) hey(99) jarvis(99)] rejected: contaminated ('[unk]' in decode)"),
            events.lines,
        )
    }

    @Test fun keywordFreeDecodeIsReportedAsAFlushedFinal() {
        readyDetector().decode("""{"result":[{"conf":0.99,"word":"hey"}],"text":"hey"}""")
        assertEquals(listOf("flushed final [hey(99)]"), events.lines)
    }

    // ---- model load + first decoder build → readiness -----------------------------------------------

    @Test fun readyOnceTheModelLoads() {
        val d = detector()
        loader.complete(model)
        d.accept(frame(2), FRAME)
        assertEquals(listOf("ready"), events.lines)
        assertEquals(listOf(2), model.live.fedTags())
    }

    @Test fun modelLoadFailureIsUnavailable() {
        detector()
        loader.fail()
        assertEquals(listOf("unavailable"), events.lines)
    }

    @Test fun loaderThatThrowsIsUnavailable() {
        detector(loader = FakeVoskLoader(throwOnLoad = true))
        assertEquals(listOf("unavailable"), events.lines)
    }

    @Test fun firstDecoderBuildFailureIsUnavailable() {
        model.failBuilds = true
        val d = detector()
        loader.complete(model)
        d.accept(frame(2), FRAME)
        assertEquals(listOf("unavailable"), events.lines)
        assertTrue(model.decoders.isEmpty())
    }

    @Test fun grammarListsEachPhraseKeywordAndLeadThenUnk() {
        val d = detector(listOf(JARVIS, ALEXA, COMPUTER))
        loader.complete(model)
        d.accept(frame(2), FRAME)
        assertEquals(
            """["hey jarvis", "jarvis", "hey", "hey alexa", "alexa", "computer", "[unk]"]""",
            model.live.grammar,
        )
    }

    @Test fun rejectedGrammarFallsBackToOpenVocabulary() {
        model.rejectGrammar = true
        val d = detector()
        loader.complete(model)
        d.accept(frame(2), FRAME)
        assertEquals(listOf("ready"), events.lines)
        assertNull(model.decoders.single().grammar)

        // the matcher still runs over the open-vocabulary decode
        assertEquals(wake("jarvis", "hey(97) jarvis(81)"), d.decode(HEY_JARVIS_CONFIDENT))
    }

    @Test fun wakeWordsSwappedBeforeTheModelLoadsAreTheFirstGrammar() {
        val d = detector()
        d.updateWakeWords(listOf(ALEXA))
        loader.complete(model)
        d.accept(frame(2), FRAME)
        assertEquals(ALEXA_GRAMMAR, model.decoders.single().grammar)
    }

    // ---- warm-up ------------------------------------------------------------------------------------

    @Test fun everyBuildAndEveryResetIsWarmedWithTenSilentFrames() {
        val d = detector()
        loader.complete(model)
        d.accept(frame(2), FRAME)
        d.start()
        d.accept(frame(3), FRAME)

        assertEquals(warmUp() + "frame:2" + "reset" + warmUp() + "frame:3", model.live.log)
    }

    // ---- pre-ready buffer + replay ------------------------------------------------------------------

    @Test fun framesDuringLoadAreReplayedInOrderOnReady() {
        val d = detector()
        d.accept(frame(2), FRAME)
        d.accept(frame(3), FRAME)
        d.accept(frame(4), FRAME)
        loader.complete(model)
        d.accept(frame(5), FRAME)
        d.accept(frame(6), FRAME)

        assertEquals(listOf(2, 3, 4, 5, 6), model.live.fedTags())
        assertEquals(listOf("ready", "flushing 3 pre-ready frame(s)"), events.lines)
    }

    @Test fun eachFrameDecodesAtMostThreeReplayedFramesWithLiveFramesQueuedBehind() {
        val d = detector()
        (1..7).forEach { d.accept(frame(it), FRAME) }
        loader.complete(model)

        d.accept(frame(8), FRAME)
        assertEquals(listOf(1, 2, 3), model.live.fedTags())
        d.accept(frame(9), FRAME)
        assertEquals((1..6).toList(), model.live.fedTags())
        d.accept(frame(10), FRAME)
        assertEquals((1..9).toList(), model.live.fedTags())
        d.accept(frame(11), FRAME)
        assertEquals((1..11).toList(), model.live.fedTags())
        d.accept(frame(12), FRAME) // caught up: live frames decode as they arrive
        assertEquals((1..12).toList(), model.live.fedTags())
    }

    @Test fun startDropsAReplayThePauseInterrupted() {
        val d = detector()
        (1..7).forEach { d.accept(frame(it), FRAME) }
        loader.complete(model)
        d.accept(frame(8), FRAME)
        d.start() // the capture resumed after a pause: the rest of the backlog is stale
        d.accept(frame(9), FRAME)

        assertEquals(listOf(1, 2, 3, 9), model.live.fedTags())
    }

    @Test fun replayedFramesKeepTheirLengthAndAreCopied() {
        val d = detector()
        val src = byteArrayOf(9, 8, 7)
        d.accept(src, 2)
        src[0] = 0
        loader.complete(model)
        d.accept(frame(5), FRAME)

        assertArrayEquals(byteArrayOf(9, 8), model.live.fed.first())
    }

    @Test fun preReadyBufferKeepsOnlyTheLatestEightSeconds() {
        val capacity = 8_000 / PcmCaptureFormat.FRAME_MS
        val d = detector()
        (1..capacity + 2).forEach { d.accept(frame(it), FRAME) }
        loader.complete(model)
        val caughtUp = capacity + 2 + capacity / 2 // each live frame shrinks the backlog by two
        (capacity + 3..caughtUp).forEach { d.accept(frame(it), FRAME) }

        assertEquals((3..caughtUp).toList(), model.live.fedTags())
        assertEquals("flushing $capacity pre-ready frame(s)", events.lines.last())
    }

    @Test fun preReadyFramesAreReplayedOnlyOnce() {
        val d = detector()
        d.accept(frame(2), FRAME)
        loader.complete(model)
        d.accept(frame(3), FRAME)
        d.start() // a resume re-enters the ready transition; the buffer is already drained
        d.accept(frame(4), FRAME)

        assertEquals(listOf(2, 3, 4), model.live.fedTags())
        assertEquals(listOf("ready", "flushing 1 pre-ready frame(s)"), events.lines)
    }

    @Test fun replayStopsAtTheFirstWakeAndDropsTheRestOfTheBacklog() {
        // Audio buffered before a wake, including the frame that started the replay, is never decoded after it.
        val d = detector()
        d.accept(frame(2, endpoint = true), FRAME)
        d.accept(frame(3, endpoint = true), FRAME)
        d.accept(frame(4), FRAME)
        model.finals.add(HEY_JARVIS_CONFIDENT)
        model.finals.add(HEY_JARVIS_CONFIDENT)
        loader.complete(model)
        val replayed = d.accept(frame(5), FRAME)
        val live = d.accept(frame(6), FRAME) // past the replay, live frames decode again

        assertEquals(wake("jarvis", "hey(97) jarvis(81)"), replayed)
        assertNull(live)
        assertEquals(listOf(2, 6), model.live.fedTags())
        assertEquals(listOf("ready", "flushing 3 pre-ready frame(s)"), events.lines)
    }

    // ---- idle reset: the timer ----------------------------------------------------------------------

    @Test fun keepsBeforeIdleElapses() {
        val d = readyDetector()
        d.frameAt(0)
        d.frameAt(IDLE - 1)
        assertEquals(emptyList<String>(), events.lines)
    }

    @Test fun resetsAtIdleWhenDecoderQuiet_thenWarmsUpBeforeTheFrame() {
        val d = readyDetector()
        d.frameAt(IDLE, tag = 7)
        assertEquals(listOf(IDLE_RESET), events.lines)
        assertEquals(listOf("reset") + warmUp() + "frame:7", model.live.log.takeLast(12))
    }

    @Test fun startRestartsTheClock() {
        val d = readyDetector()
        now = 10_000
        d.start()
        d.frameAt(10_000 + IDLE - 1)
        assertEquals(emptyList<String>(), events.lines)
        d.frameAt(10_000 + IDLE)
        assertEquals(listOf(IDLE_RESET), events.lines)
    }

    @Test fun grammarSwapRestartsTheClock() {
        val d = readyDetector()
        now = 10_000
        d.updateWakeWords(listOf(ALEXA))
        d.frameAt(10_000 + IDLE - 1)
        assertEquals(emptyList<String>(), events.lines)
        d.frameAt(10_000 + IDLE)
        assertEquals(listOf(IDLE_RESET), events.lines)
    }

    // ---- idle reset: natural endpoints keep pushing it back -----------------------------------------

    @Test fun naturalEndpointsKeepDeferringTheReset() {
        val d = readyDetector()
        // A quiet room endpoints every ~5 s; each one restarts the clock, so the reset never becomes due no
        // matter how long capture runs (100 s here, well past the force cap).
        repeat(20) {
            now += 5_000
            d.accept(frame(3, endpoint = true), FRAME)
        }
        assertEquals(emptyList<String>(), events.lines)
        // Endpoints stop (continuous non-endpointing audio) → due IDLE after the last one.
        val lastEndpoint = now
        d.frameAt(lastEndpoint + IDLE - 1)
        assertEquals(emptyList<String>(), events.lines)
        d.frameAt(lastEndpoint + IDLE)
        assertEquals(listOf(IDLE_RESET), events.lines)
    }

    // ---- idle reset: never mid-utterance (until the backstop) ---------------------------------------

    @Test fun defersWhileAnyPartialIsInFlight() {
        val d = readyDetector()
        model.live.partial = """{"partial" : "hey jar"}"""
        d.frameAt(IDLE)
        // A bare "[unk]" partial still counts — a real "hey" may still be settling.
        model.live.partial = """{"partial" : "[unk]"}"""
        d.frameAt(IDLE + 1)
        assertEquals(emptyList<String>(), events.lines)
    }

    @Test fun quietEmptyOrUnreadablePartialsDoNotDefer() {
        val d = readyDetector()
        val quiet = listOf("""{"partial" : ""}""", """{"partial" : "   "}""", """{}""", "not json")
        quiet.forEach {
            model.live.partial = it
            d.frameAt(now + IDLE)
        }
        assertEquals(List(quiet.size) { IDLE_RESET }, events.lines)
    }

    @Test fun deferredResetFiresAtTheNextQuietMoment() {
        val d = readyDetector()
        model.live.partial = """{"partial" : "hey jar"}"""
        d.frameAt(IDLE) // "hey jar…" in flight — hold
        assertEquals(emptyList<String>(), events.lines)
        model.live.partial = """{"partial" : ""}"""
        d.frameAt(IDLE + 900) // utterance over — reset now
        assertEquals(listOf(IDLE_RESET), events.lines)
    }

    @Test fun forcesResetPastBackstopEvenMidUtterance() {
        val d = readyDetector()
        model.live.partial = """{"partial" : "hey jar"}"""
        d.frameAt(FORCE - 1)
        assertEquals(emptyList<String>(), events.lines)
        d.frameAt(FORCE)
        assertEquals(listOf(FORCED_RESET), events.lines)
    }

    @Test fun quietResetPastBackstopIsAnOrdinaryReset() {
        // "forced mid-decode" means "clipped speech is possible" in the log — a quiet late reset isn't that.
        readyDetector().frameAt(FORCE + 1)
        assertEquals(listOf(IDLE_RESET), events.lines)
    }

    @Test fun clockRestartsAfterForcedReset() {
        val d = readyDetector()
        model.live.partial = """{"partial" : "hey jar"}"""
        d.frameAt(FORCE)
        d.frameAt(FORCE + IDLE - 1)
        d.frameAt(FORCE + IDLE) // deferred again, not forced
        assertEquals(listOf(FORCED_RESET), events.lines)
        d.frameAt(FORCE + FORCE)
        assertEquals(listOf(FORCED_RESET, FORCED_RESET), events.lines)
    }

    @Test fun partialIsQueriedOnlyOnceTheResetIsDue() {
        // The partial is a native call; it must not be made on every frame.
        val d = readyDetector()
        d.frameAt(0)
        d.frameAt(IDLE - 1)
        assertEquals(0, model.live.partialQueries)
        d.frameAt(IDLE)
        assertEquals(1, model.live.partialQueries)
    }

    // ---- grammar hot-swap ---------------------------------------------------------------------------

    @Test fun grammarSwapBuildsAWarmedDecoderAndClosesTheOld() {
        val d = readyDetector()
        val old = model.live
        d.updateWakeWords(listOf(ALEXA))

        assertTrue(old.closed)
        assertEquals(ALEXA_GRAMMAR, model.live.grammar)
        assertEquals(warmUp(), model.live.log)
        assertEquals(wake("alexa", "hey(99) alexa(99)"), d.decode(HEY_ALEXA))
    }

    @Test fun failedGrammarSwapKeepsTheOldDecoderAndTheOldWakeWords() {
        val d = readyDetector()
        val old = model.live
        model.failBuilds = true
        d.updateWakeWords(listOf(ALEXA))

        assertFalse(old.closed)
        assertEquals(listOf(old), model.decoders.toList())
        assertNull(d.decode(HEY_ALEXA)) // the matcher still runs the old set, in step with the live grammar
        assertEquals(wake("jarvis", "hey(97) jarvis(81)"), d.decode(HEY_JARVIS_CONFIDENT))
        assertEquals(listOf("flushed final [hey(99) alexa(99)]"), events.lines)
        assertEquals(listOf(TAG_PRIME, 3, 3), old.fedTags())
    }

    @Test fun grammarSwapRejectedByTheModelFallsBackToOpenVocabulary() {
        val d = readyDetector()
        model.rejectGrammar = true
        d.updateWakeWords(listOf(ALEXA))

        assertNull(model.live.grammar)
        assertEquals(wake("alexa", "hey(99) alexa(99)"), d.decode(HEY_ALEXA))
    }

    // ---- close --------------------------------------------------------------------------------------

    @Test fun closeFreesTheDecoderAndTheModel() {
        readyDetector().close()
        assertTrue(model.live.closed)
        assertEquals(1, model.closeCount)
    }

    private fun wake(wakeId: String, transcript: String) = WakeEvent(VoskWakeDetector.ID, wakeId, transcript)

    private fun warmUp() = List(VoskWakeDetector.WARMUP_SILENCE_FRAMES) { "silence" }

    private companion object {
        const val TAG_PRIME = 99
        const val IDLE = 25_000L
        const val FORCE = 60_000L
        const val IDLE_RESET = "idle reset (bounding Vosk lattice)"
        const val FORCED_RESET = "idle reset forced mid-decode (bounding Vosk lattice)"

        val JARVIS_LENIENT = WakeWord.fromPhrase("hey jarvis", id = "jarvis", minConf = WakeMatcher.BASELINE_CONF)!!
        val COMPUTER = WakeWord.fromPhrase("computer", minConf = WakeMatcher.BASELINE_CONF)!!

        const val ALEXA_GRAMMAR = """["hey alexa", "alexa", "hey", "[unk]"]"""

        val HEY_JARVIS_CONFIDENT = """
            {"result":[
              {"conf":0.97,"word":"hey"},
              {"conf":0.81,"word":"jarvis"}
            ],"text":"hey jarvis"}
        """.trimIndent()

        const val HEY_ALEXA = """{"result":[{"conf":0.99,"word":"hey"},{"conf":0.99,"word":"alexa"}]}"""
    }
}
