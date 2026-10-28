package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat
import com.portal.commons.audio.ScriptedDecoder.Companion.frame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * The [WakeDetector], tested through its own methods only. Native libvosk is replaced at the seam
 * ([VoskModelLoader] / [VoskModel] / [VoskDecoder]) by fakes, so model handoff, readiness, the pre-ready buffer +
 * replay and the [WakeDecoder] underneath all run for real. Decoding itself is covered by [WakeDecoderTest]. Real
 * `org.json` on the JVM (see `testOptions` in build.gradle.kts).
 *
 * Readiness is asserted only after a frame has been fed, so these tests hold whether the detector signals on
 * the model-load thread or at the next frame boundary.
 */
class WakeDetectorTest {

    private val loader = FakeVoskLoader()
    private val model = FakeVoskModel()
    private val callbacks = RecordingCallbacks()

    private fun detector(words: List<WakeWord> = listOf(JARVIS), loader: VoskModelLoader = this.loader) =
        callbacks.detector(loader, words)

    /** A detector whose model has loaded and which has been started, with the callback log cleared. */
    private fun readyDetector(words: List<WakeWord> = listOf(JARVIS)): WakeDetector = detector(words).also {
        loader.complete(model)
        it.start()
        it.accept(frame(TAG_PRIME), FRAME)
        callbacks.lines.clear()
    }

    /** Feed one finalizing frame that decodes [json]; returns the wake [WakeDetector.accept] returned, if any. */
    private fun WakeDetector.decode(json: String): WakeEvent? {
        model.finals.add(json)
        return accept(frame(3, endpoint = true), FRAME)
    }

    // ---- one wake end to end ------------------------------------------------------------------------

    @Test fun confidentWakeIsReturnedWithItsDecode() {
        assertEquals(wake("jarvis", "hey(97) jarvis(81)"), readyDetector().decode(HEY_JARVIS_CONFIDENT))
        assertEquals(emptyList<String>(), callbacks.lines)
    }

    // ---- model load + first decoder build → readiness -----------------------------------------------

    @Test fun readyOnceTheModelLoads() {
        val d = detector()
        loader.complete(model)
        d.accept(frame(2), FRAME)
        assertEquals(listOf("ready"), callbacks.lines)
        assertEquals(listOf(2), model.live.fedTags())
    }

    @Test fun modelLoadFailureIsUnavailable() {
        detector()
        loader.fail()
        assertEquals(listOf("unavailable"), callbacks.lines)
    }

    @Test fun loaderThatThrowsIsUnavailable() {
        detector(loader = FakeVoskLoader(throwOnLoad = true))
        assertEquals(listOf("unavailable"), callbacks.lines)
    }

    @Test fun firstDecoderBuildFailureIsUnavailable() {
        model.failBuilds = true
        val d = detector()
        loader.complete(model)
        d.accept(frame(2), FRAME)
        assertEquals(listOf("unavailable"), callbacks.lines)
        assertTrue(model.decoders.isEmpty())
        assertEquals(1, model.closeCount)
    }

    @Test fun wakeWordsSwappedBeforeTheModelLoadsAreTheFirstGrammar() {
        val d = detector()
        d.updateWakeWords(listOf(ALEXA))
        loader.complete(model)
        d.accept(frame(2), FRAME)
        assertEquals(ALEXA_GRAMMAR, model.decoders.single().grammar)
    }

    // ---- wake-word hot swap -------------------------------------------------------------------------

    @Test fun aWakeWordUpdateFromAnotherThreadIsAppliedAtTheNextFrame() {
        val d = readyDetector()
        val old = model.live
        thread(name = "controller") { d.updateWakeWords(listOf(ALEXA)) }.join()
        assertEquals("no Decoder built off the capture thread", listOf(old), model.decoders)

        d.accept(frame(2), FRAME)

        assertTrue(old.closed)
        assertEquals(ALEXA_GRAMMAR, model.live.grammar)
        assertEquals(listOf(2), model.live.fedTags())
        assertEquals(listOf("wake set changed → rebuilding grammar (1 word(s))"), callbacks.lines)
    }

    // ---- start ---------------------------------------------------------------------------------------

    @Test fun startResetsAndRewarmsTheDecoder() {
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
        assertEquals(listOf("ready", "flushing 3 pre-ready frame(s)"), callbacks.lines)
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
        assertEquals("flushing $capacity pre-ready frame(s)", callbacks.lines.last())
    }

    @Test fun preReadyFramesAreReplayedOnlyOnce() {
        val d = detector()
        d.accept(frame(2), FRAME)
        loader.complete(model)
        d.accept(frame(3), FRAME)
        d.start() // a resume re-enters the ready transition; the buffer is already drained
        d.accept(frame(4), FRAME)

        assertEquals(listOf(2, 3, 4), model.live.fedTags())
        assertEquals(listOf("ready", "flushing 1 pre-ready frame(s)"), callbacks.lines)
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
        assertEquals(listOf("ready", "flushing 3 pre-ready frame(s)"), callbacks.lines)
    }

    // ---- close --------------------------------------------------------------------------------------

    @Test fun closeFreesTheDecoderAndTheModel() {
        readyDetector().close()
        assertTrue(model.live.closed)
        assertEquals(1, model.closeCount)
    }

    private fun wake(wakeId: String, transcript: String) = WakeEvent(wakeId, transcript)

    private fun warmUp() = List(WakeDecoder.WARMUP_SILENCE_FRAMES) { "silence" }

    private companion object {
        const val TAG_PRIME = 99
    }
}
