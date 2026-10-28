package com.portal.commons.audio

import com.portal.commons.PcmDevice
import com.portal.commons.audio.ScriptedDecoder.Companion.frame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives a real [WakeMicEngine] capture thread one frame at a time with a [SteppedDevice] and a hand-set clock, so the
 * handoff cooldown and the consumer callback routing are observable from outside. The detector is the real Vosk wake
 * detector over the Vosk fakes ([FakeVoskLoader] / [FakeVoskModel] / [ScriptedDecoder]): a wake is a confident
 * "hey jarvis" final queued on the model and decoded at an endpoint frame, and readiness, unavailability and the
 * pre-ready replay are the detector's own, and the engine's [WakeModel] reaches the detector through `newLoader`.
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

    @Volatile private var nowMs = 0L
    private val device = SteppedDevice()
    private val loader = FakeVoskLoader()
    private val model = FakeVoskModel()
    private val wakes = CopyOnWriteArrayList<WakeEvent>()
    private val wakeThreads = CopyOnWriteArrayList<String>()
    private val logs = CopyOnWriteArrayList<String>()

    // Runs on the logging thread before the line is recorded, so a test can hold the capture thread at a log line.
    @Volatile private var beforeLog: (String) -> Unit = {}
    private val ready = AtomicInteger()
    private val unavailable = AtomicInteger()
    private val loadedModels = CopyOnWriteArrayList<WakeModel>()
    private val losses = CopyOnWriteArrayList<CaptureLoss>()
    private val posted = CopyOnWriteArrayList<Runnable>()
    private val opensAtAcquire = CopyOnWriteArrayList<Int>()

    @Volatile private var beforeMicAcquire: () -> Unit = { opensAtAcquire.add(-1) }
    private lateinit var engine: WakeMicEngine

    /** An engine whose wake detector loads [model] through [loader]. */
    private fun newEngine(
        loader: VoskModelLoader = this.loader,
        model: WakeModel = WakeModel.Bundled,
        newDevice: () -> PcmDevice = { device },
    ) = WakeMicEngine(
        WakeMicConfig(
            wakeWords = listOf(JARVIS),
            model = model,
            wakeHandoffCooldownMs = 1_500L,
            onWake = {
                wakes.add(it)
                wakeThreads.add(Thread.currentThread().name)
            },
            onReady = { ready.incrementAndGet() },
            onUnavailable = { unavailable.incrementAndGet() },
            onCaptureLost = { losses.add(it) },
            beforeMicAcquire = { beforeMicAcquire() },
            log = {
                beforeLog(it)
                logs.add(it)
            },
        ),
        newDevice = newDevice,
        postToMain = { posted.add(it) },
        newLoader = {
            loadedModels.add(it)
            loader
        },
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

    @Test fun theWakeDetectorLoadsTheConfiguredWakeModel() {
        newEngine(model = WakeModel.Unpacked(File("downloaded-model")))

        assertEquals(listOf(WakeModel.Unpacked(File("downloaded-model"))), loadedModels)
    }

    @Test fun onWakeIsDeliveredOnTheCaptureThreadAndLogged() {
        readyEngine()
        pushWake(2)

        assertEquals(listOf(WakeEvent("jarvis", "hey(97) jarvis(81)")), wakes)
        assertEquals(listOf("wake-capture"), wakeThreads)
        assertTrue(logs.contains("wake detected → jarvis [hey(97) jarvis(81)]"))
    }

    @Test fun onReadyIsDeliveredThroughTheMainThreadPosterAtTheFirstFrameAfterTheModelLoads() {
        newEngine().start()
        loader.complete(model)
        device.push(frame(2))
        assertEquals(0, ready.get())
        runPosted()

        assertEquals(1, ready.get())
        assertTrue(logs.contains("wake detector ready"))
    }

    @Test fun onUnavailableIsDeliveredThroughTheMainThreadPoster() {
        newEngine()
        loader.fail()
        assertEquals(0, unavailable.get())
        runPosted()

        assertEquals(1, unavailable.get())
        assertEquals(listOf("wake detector unavailable"), logs)
    }

    @Test fun aFirstDecoderThatCannotBeBuiltIsUnavailable() {
        model.failBuilds = true
        newEngine().start()
        loader.complete(model)
        device.push(frame(2))
        runPosted()

        assertEquals(1, unavailable.get())
        assertEquals(0, ready.get())
    }

    @Test fun aModelThatFailsWhileTheEngineIsBeingConstructedIsPostedNotDeliveredInline() {
        newEngine(loader = FakeVoskLoader(throwOnLoad = true))
        assertEquals(0, unavailable.get())
        runPosted()

        assertEquals(1, unavailable.get())
    }

    @Test fun framesCapturedWhileTheModelLoadsAreReplayedOnceItIsReady() {
        newEngine().start()
        device.push(frame(2))
        device.push(frame(3))
        loader.complete(model)
        device.push(frame(4))

        assertEquals(listOf(2, 3, 4), heard())
        assertTrue(logs.contains("flushing 2 pre-ready frame(s)"))
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

    @Test fun aReadinessAlreadyQueuedIsNotDeliveredAfterClose() {
        newEngine().start()
        loader.complete(model)
        device.push(frame(2))
        engine.close()
        runPosted()

        assertEquals(0, ready.get())
    }

    @Test fun anUnavailabilityAlreadyQueuedIsNotDeliveredAfterClose() {
        newEngine()
        loader.fail()
        engine.close()
        runPosted()

        assertEquals(0, unavailable.get())
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
