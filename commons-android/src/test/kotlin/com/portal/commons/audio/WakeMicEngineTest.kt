package com.portal.commons.audio

import android.content.ContextWrapper
import com.portal.commons.PcmDevice
import com.portal.commons.audio.ScriptedDecoder.Companion.frame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Drives a real [WakeMicEngine] capture thread one frame at a time with a [SteppedDevice] and a hand-set clock, so the
 * handoff cooldown and the consumer callback routing are observable from outside. The detector is the real Vosk wake
 * detector over the Vosk fakes ([FakeVoskLoader] / [FakeVoskModel] / [ScriptedDecoder]): a wake is a confident
 * "hey jarvis" final queued on the model and decoded at an endpoint frame, and readiness, unavailability and the
 * pre-ready replay are the detector's own. Only the tests of several detectors at once use a [FakeDetector].
 */
class WakeMicEngineTest {

    /**
     * A mic that serves one full frame per [push], which returns once the engine has finished handling it. Its next
     * [failOpens] opens fail, and while [failReads] is set every read is a device error. Records each capture thread
     * that opens it in [openThreads].
     */
    private class SteppedDevice : PcmDevice {
        private val frames = Semaphore(0)
        private val queued = ConcurrentLinkedQueue<ByteArray>()
        private val handled = Semaphore(0)

        @Volatile private var frameOut = false

        /** Serves [frame] (all-zero silence by default) and waits until the engine has finished handling it. */
        fun push(frame: ByteArray = ByteArray(FRAME)) {
            offer(frame)
            assertTrue("engine never finished the frame", handled.tryAcquire(2, TimeUnit.SECONDS))
        }

        /** Serves [frame] without waiting for the engine to finish it (capture may stop inside that frame). */
        fun offer(frame: ByteArray = ByteArray(FRAME)) {
            queued.add(frame)
            frames.release()
        }

        @Volatile var opens = 0

        @Volatile var failOpens = 0

        @Volatile var failReads = false

        val openThreads = CopyOnWriteArrayList<Thread>()

        override fun open(): Boolean {
            openThreads.addIfAbsent(Thread.currentThread())
            opens++
            if (failOpens == 0) return true
            failOpens--
            return false
        }

        override fun read(buf: ByteArray, offset: Int, length: Int): Int {
            // The capture loop reads again only once onFrame has returned: that is when the last frame is handled.
            if (frameOut) {
                frameOut = false
                handled.release()
            }
            return when {
                failReads -> -1

                !frames.tryAcquire(5, TimeUnit.MILLISECONDS) -> 0

                else -> {
                    queued.remove().copyInto(buf, offset, FRAME - length)
                    frameOut = true
                    length
                }
            }
        }

        override fun stop() = Unit

        override fun release() = Unit
    }

    /**
     * Counts frames it is fed, records each wake-word hot swap it receives and, when [wakeOnNextAccept] is set, returns
     * that wake id from [accept]. Only for the tests of several detectors at once.
     */
    private class FakeDetector : WakeDetector {
        override val id = "fake"
        val accepts = CopyOnWriteArrayList<Long>()
        val wakeWordUpdates = CopyOnWriteArrayList<List<WakeWord>>()

        @Volatile var wakeOnNextAccept: String? = null

        @Volatile var clock: () -> Long = { 0L }

        override fun start() = Unit

        override fun accept(buf: ByteArray, n: Int): WakeEvent? {
            accepts.add(clock())
            val wakeId = wakeOnNextAccept ?: return null
            wakeOnNextAccept = null
            return WakeEvent(id, wakeId, "transcript")
        }

        override fun updateWakeWords(words: List<WakeWord>) {
            wakeWordUpdates.add(words)
        }

        @Volatile var closes = 0

        override fun close() {
            closes++
        }
    }

    @Volatile private var nowMs = 0L
    private val device = SteppedDevice()
    private val loader = FakeVoskLoader()
    private val model = FakeVoskModel()
    private val wakes = CopyOnWriteArrayList<WakeEvent>()
    private val wakeThreads = CopyOnWriteArrayList<String>()
    private val logs = CopyOnWriteArrayList<String>()

    // Runs on the logging thread before the line is recorded, so a test can hold the capture thread at a log line.
    @Volatile private var beforeLog: (String) -> Unit = {}
    private val ready = CopyOnWriteArrayList<String>()
    private val unavailable = CopyOnWriteArrayList<String>()
    private val losses = CopyOnWriteArrayList<CaptureLoss>()
    private val posted = CopyOnWriteArrayList<Runnable>()
    private val opensAtAcquire = CopyOnWriteArrayList<Int>()

    @Volatile private var beforeMicAcquire: () -> Unit = { opensAtAcquire.add(-1) }
    private val fakeDetectors = CopyOnWriteArrayList<FakeDetector>()
    private lateinit var engine: WakeMicEngine

    /** An engine on the Vosk wake detector, loading through [loader]. */
    private fun newEngine(loader: VoskModelLoader = this.loader, newDevice: () -> PcmDevice = { device }) =
        newEngine(listOf(VoskWakeDetector.factory(newLoader = { loader })), newDevice)

    /** An engine on [count] [FakeDetector]s, for the tests of several detectors at once. */
    private fun newEngineWithFakeDetectors(count: Int) = newEngine(
        List(count) {
            WakeDetector.Factory {
                FakeDetector().also { detector ->
                    detector.clock = { nowMs }
                    fakeDetectors.add(detector)
                }
            }
        },
    )

    private fun newEngine(detectors: List<WakeDetector.Factory>, newDevice: () -> PcmDevice = { device }) =
        WakeMicEngine(
            ContextWrapper(null),
            WakeMicConfig(
                wakeWords = listOf(JARVIS),
                detectors = detectors,
                wakeHandoffCooldownMs = 1_500L,
                onWake = {
                    wakes.add(it)
                    wakeThreads.add(Thread.currentThread().name)
                },
                onDetectorReady = { ready.add(it) },
                onDetectorUnavailable = { unavailable.add(it) },
                onCaptureLost = { losses.add(it) },
                beforeMicAcquire = { beforeMicAcquire() },
                log = {
                    beforeLog(it)
                    logs.add(it)
                },
            ),
            newDevice = newDevice,
            postToMain = { posted.add(it) },
            clock = { nowMs },
        ).also { engine = it }

    /** A started engine whose detector has adopted the model and decoded frame [TAG_PRIME], with readiness run. */
    private fun readyEngine() {
        newEngine().start()
        loader.complete(model)
        device.push(frame(TAG_PRIME))
        runPosted()
    }

    /** Pushes an endpoint frame tagged [tag] that decodes a confident "hey jarvis", if the detector hears it. */
    private fun pushWake(tag: Int) {
        model.finals.add(HEY_JARVIS_CONFIDENT)
        device.push(frame(tag, endpoint = true))
    }

    /** The tags of the frames the live decoder has been fed, in order. */
    private fun heard() = model.live.fedTags()

    private fun runPosted() {
        while (posted.isNotEmpty()) posted.removeAt(0).run()
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertTrue("never happened: $what", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }

    /**
     * Waits for every capture thread that opened [device] to exit, then drains the main-thread queue. A loss is posted
     * from the capture thread, and after one the engine starts no other, so by then every report, a second, wrong,
     * one included, is queued. Joins the thread rather than waiting on a log line: the session logs before it reports.
     */
    private fun awaitCaptureEndThenRunPosted() {
        await("the mic opened") { device.openThreads.isNotEmpty() }
        for (thread in device.openThreads) {
            thread.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse("capture thread ${thread.name} never ended", thread.isAlive)
        }
        runPosted()
    }

    @After fun tearDown() {
        if (::engine.isInitialized) engine.close()
    }

    @Test fun noFrameReachesTheDetectorUntilTheHandoffCooldownElapses() {
        readyEngine()
        pushWake(2)
        nowMs = 500L
        device.push(frame(3))
        nowMs = 1_499L
        device.push(frame(4))
        nowMs = 1_500L
        device.push(frame(5))

        assertEquals(listOf(TAG_PRIME, 2, 5), heard())
    }

    @Test fun aWakeFromOneDetectorKeepsThatSameFrameFromEveryOtherDetector() {
        newEngineWithFakeDetectors(count = 2).start()
        val (first, second) = fakeDetectors
        first.wakeOnNextAccept = "jarvis"
        device.push()
        nowMs = 500L
        device.push()

        assertEquals(listOf(0L), first.accepts)
        assertEquals(emptyList<Long>(), second.accepts)
    }

    @Test fun aWakeExactlyWhenTheCooldownElapsesIsDeliveredAndOpensAFreshWindow() {
        readyEngine()
        pushWake(2)
        nowMs = 1_500L
        pushWake(3)
        nowMs = 2_999L
        device.push(frame(4))
        nowMs = 3_000L
        device.push(frame(5))

        assertEquals(listOf("jarvis", "jarvis"), wakes.map { it.wakeId })
        assertEquals(listOf(TAG_PRIME, 2, 3, 5), heard())
    }

    @Test fun restartingCaptureClosesTheCooldown() {
        readyEngine()
        pushWake(2)
        nowMs = 500L
        engine.pause()
        engine.start()
        pushWake(3)

        assertEquals(listOf(TAG_PRIME, 2, 3), heard())
        assertEquals(listOf("jarvis", "jarvis"), wakes.map { it.wakeId })
    }

    @Test fun onWakeIsDeliveredOnTheCaptureThreadAndLogged() {
        readyEngine()
        pushWake(2)

        assertEquals(listOf(WakeEvent("vosk", "jarvis", "hey(97) jarvis(81)")), wakes)
        assertEquals(listOf("wake-capture"), wakeThreads)
        assertTrue(logs.contains("wake detected (vosk) → jarvis [hey(97) jarvis(81)]"))
    }

    @Test fun onReadyIsDeliveredThroughTheMainThreadPosterAtTheFirstFrameAfterTheModelLoads() {
        newEngine().start()
        loader.complete(model)
        device.push(frame(2))
        assertEquals(emptyList<String>(), ready)
        runPosted()

        assertEquals(listOf("vosk"), ready)
        assertTrue(logs.contains("wake detector ready (vosk)"))
    }

    @Test fun onUnavailableIsDeliveredThroughTheMainThreadPoster() {
        newEngine()
        loader.fail()
        assertEquals(emptyList<String>(), unavailable)
        runPosted()

        assertEquals(listOf("vosk"), unavailable)
        assertEquals(listOf("wake unavailable (vosk) — detector idle"), logs)
    }

    @Test fun aFirstDecoderThatCannotBeBuiltIsUnavailable() {
        model.failBuilds = true
        newEngine().start()
        loader.complete(model)
        device.push(frame(2))
        runPosted()

        assertEquals(listOf("vosk"), unavailable)
        assertEquals(emptyList<String>(), ready)
    }

    @Test fun aModelThatFailsWhileTheEngineIsBeingConstructedIsPostedNotDeliveredInline() {
        newEngine(loader = FakeVoskLoader(throwOnLoad = true))
        assertEquals(emptyList<String>(), unavailable)
        runPosted()

        assertEquals(listOf("vosk"), unavailable)
    }

    @Test fun framesCapturedWhileTheModelLoadsAreReplayedOnceItIsReady() {
        newEngine().start()
        device.push(frame(2))
        device.push(frame(3))
        loader.complete(model)
        device.push(frame(4))

        assertEquals(listOf(2, 3, 4), heard())
        assertTrue(logs.contains("(vosk) flushing 2 pre-ready frame(s)"))
    }

    @Test fun aWakeInTheReplayedFramesIsDelivered() {
        newEngine().start()
        model.finals.add(HEY_JARVIS_CONFIDENT)
        device.push(frame(2, endpoint = true))
        loader.complete(model)
        device.push(frame(3))
        device.push(frame(4)) // inside the handoff cooldown the wake opened

        assertEquals(listOf("jarvis"), wakes.map { it.wakeId })
        assertEquals(listOf(2), heard())
    }

    @Test fun aWakeWordHotSwapReachesTheDetectorBeforeItsNextFrame() {
        readyEngine()
        engine.updateWakeWords(listOf(ALEXA))
        device.push(frame(2))

        assertEquals(ALEXA_GRAMMAR, model.live.grammar)
        assertEquals(listOf(2), heard())
        assertTrue(logs.contains("wake set changed → rebuilding grammar (1 word(s))"))
    }

    @Test fun aWakeWordHotSwapBeforeTheModelLoadsIsTheFirstGrammar() {
        newEngine().start()
        engine.updateWakeWords(listOf(ALEXA))
        device.push(frame(2))
        loader.complete(model)
        device.push(frame(3))

        assertEquals(ALEXA_GRAMMAR, model.decoders.single().grammar)
    }

    @Test fun aWakeWordHotSwapReachesEveryDetectorBeforeItsNextFrame() {
        newEngineWithFakeDetectors(count = 2).start()
        engine.updateWakeWords(listOf(ALEXA))
        device.push()

        fakeDetectors.forEach { assertEquals(listOf(listOf(ALEXA)), it.wakeWordUpdates) }
        assertTrue(logs.contains("wake set changed → rebuilding grammar (1 word(s))"))
    }

    @Test fun aReadinessAlreadyQueuedIsNotDeliveredAfterClose() {
        newEngine().start()
        loader.complete(model)
        device.push(frame(2))
        engine.close()
        runPosted()

        assertEquals(emptyList<String>(), ready)
    }

    @Test fun anUnavailabilityAlreadyQueuedIsNotDeliveredAfterClose() {
        newEngine()
        loader.fail()
        engine.close()
        runPosted()

        assertEquals(emptyList<String>(), unavailable)
    }

    @Test fun beforeMicAcquireRunsBeforeTheMicOpens() {
        newEngine()
        beforeMicAcquire = { opensAtAcquire.add(device.opens) }
        engine.start()
        device.push()

        assertEquals(listOf(0), opensAtAcquire)
        assertEquals(1, device.opens)
    }

    @Test fun aStartAfterCloseNeitherRunsBeforeMicAcquireNorOpensTheMic() {
        newEngine().close()
        engine.start()

        assertEquals(emptyList<Int>(), opensAtAcquire)
        assertEquals(0, device.opens)
    }

    @Test fun closingTwiceFreesTheDecoderAndTheModelOnce() {
        readyEngine()
        engine.close()
        engine.close()

        assertTrue(model.live.closed)
        assertEquals(1, model.live.log.count { it == "close" })
        assertEquals(1, model.closeCount)
    }

    @Test fun closingTwiceClosesEveryDetectorOnce() {
        newEngineWithFakeDetectors(count = 2).close()
        engine.close()

        assertEquals(listOf(1, 1), fakeDetectors.map { it.closes })
    }

    @Test fun aLossReachesOnCaptureLost() {
        newEngine()
        device.failOpens = 1
        engine.start()
        awaitCaptureEndThenRunPosted()

        assertEquals(listOf(CaptureLoss.NotGranted("microphone unavailable")), losses)
    }

    @Test fun theEngineRebuildsAfterRepeatedReadFailures() {
        newEngine().start()
        device.push(frame(2))
        device.failOpens = 1
        device.failReads = true // the read-failure run ends in a rebuild whose open fails
        awaitCaptureEndThenRunPosted()

        assertEquals(listOf(CaptureLoss.Dropped("microphone unavailable on rebuild")), losses)
    }

    @Test fun theEngineCapturesUnderATwoSecondSilenceWindow() {
        newEngine().start()
        repeat(19) { device.push() } // all-zero frames: 1.9 s of digital silence
        device.offer() // the 20th fills the 2 s window, and capture ends inside it
        awaitCaptureEndThenRunPosted()

        assertEquals(listOf(CaptureLoss.Silenced("digital silence for 2000ms")), losses)
    }

    @Test fun aWakeHeldUpPastACloseThatGaveUpWaitingNeverReachesTheConsumer() {
        val wakeLogged = CountDownLatch(1)
        val holdWake = CountDownLatch(1)
        beforeLog = {
            if (it.startsWith("wake detected")) {
                wakeLogged.countDown()
                holdWake.await()
            }
        }
        readyEngine()
        model.finals.add(HEY_JARVIS_CONFIDENT)
        device.offer(frame(2, endpoint = true))
        assertTrue(wakeLogged.await(2, TimeUnit.SECONDS))
        engine.close() // the capture thread is held inside the frame, so close() gives up waiting for it
        holdWake.countDown()
        await("the detector frees the model once the frame ends") { model.closeCount == 1 }

        assertEquals(emptyList<WakeEvent>(), wakes)
    }

    private companion object {
        const val TAG_PRIME = 99
    }
}
