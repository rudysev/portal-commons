package com.portal.commons.audio

import android.content.ContextWrapper
import com.portal.commons.PcmDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Drives a real [WakeMicEngine] capture thread one frame at a time with a [SteppedDevice], a [FakeDetector] and a
 * hand-set clock, so the handoff cooldown and the consumer callback routing are observable from outside.
 */
class WakeMicEngineTest {

    /** A mic that serves one full frame per [push], which returns once the engine has finished handling it. */
    private class SteppedDevice : PcmDevice {
        private val frames = Semaphore(0)
        private val handled = Semaphore(0)

        @Volatile private var frameOut = false

        fun push() {
            frames.release()
            assertTrue("engine never finished the frame", handled.tryAcquire(2, TimeUnit.SECONDS))
        }

        @Volatile var opens = 0

        override fun open(): Boolean {
            opens++
            return true
        }

        override fun read(buf: ByteArray, offset: Int, length: Int): Int {
            // The capture loop reads again only once onFrame has returned: that is when the last frame is handled.
            if (frameOut) {
                frameOut = false
                handled.release()
            }
            if (!frames.tryAcquire(5, TimeUnit.MILLISECONDS)) return 0
            frameOut = true
            return length
        }

        override fun stop() = Unit

        override fun release() = Unit
    }

    /** Counts frames it is fed and, when [wakeOnNextAccept] is set, fires that wake id from inside [accept]. */
    private class FakeDetector(val host: WakeDetector.Host) : WakeDetector {
        override val id = "fake"
        val accepts = CopyOnWriteArrayList<Long>()

        @Volatile var wakeOnNextAccept: String? = null

        @Volatile var clock: () -> Long = { 0L }

        override fun start() = Unit

        override fun accept(buf: ByteArray, n: Int) {
            accepts.add(clock())
            val wakeId = wakeOnNextAccept ?: return
            wakeOnNextAccept = null
            host.events.onWake(WakeEvent(id, wakeId, "transcript"))
        }

        override fun updateWakeWords(words: List<WakeWord>) = Unit

        @Volatile var closes = 0

        override fun close() {
            closes++
        }
    }

    @Volatile private var nowMs = 0L
    private val device = SteppedDevice()
    private val wakes = CopyOnWriteArrayList<WakeEvent>()
    private val wakeThreads = CopyOnWriteArrayList<String>()
    private val logs = CopyOnWriteArrayList<String>()
    private val ready = CopyOnWriteArrayList<String>()
    private val unavailable = CopyOnWriteArrayList<String>()
    private val posted = CopyOnWriteArrayList<Runnable>()
    private val micAcquires = CopyOnWriteArrayList<Unit>()
    private val detectors = CopyOnWriteArrayList<FakeDetector>()
    private lateinit var engine: WakeMicEngine

    private fun newEngine(detectorCount: Int = 1, onCreate: (WakeDetector.Host) -> Unit = {}) = WakeMicEngine(
        ContextWrapper(null),
        WakeMicConfig(
            wakeWords = emptyList(),
            detectors = List(detectorCount) {
                WakeDetector.Factory { host ->
                    onCreate(host)
                    FakeDetector(host).also {
                        it.clock = { nowMs }
                        detectors.add(it)
                    }
                }
            },
            wakeHandoffCooldownMs = 1_500L,
            onWake = {
                wakes.add(it)
                wakeThreads.add(Thread.currentThread().name)
            },
            onDetectorReady = { ready.add(it) },
            onDetectorUnavailable = { unavailable.add(it) },
            beforeMicAcquire = { micAcquires.add(Unit) },
            log = { logs.add(it) },
        ),
        newDevice = { device },
        postToMain = { posted.add(it) },
        clock = { nowMs },
    ).also { engine = it }

    private val detector get() = detectors.single()
    private val events get() = detector.host.events

    private fun runPosted() {
        while (posted.isNotEmpty()) posted.removeAt(0).run()
    }

    @After fun tearDown() {
        if (::engine.isInitialized) engine.close()
    }

    @Test fun noFrameReachesTheDetectorUntilTheHandoffCooldownElapses() {
        newEngine().start()
        detector.wakeOnNextAccept = "jarvis"
        device.push()
        nowMs = 500L
        device.push()
        nowMs = 1_499L
        device.push()
        nowMs = 1_500L
        device.push()

        assertEquals(listOf(0L, 1_500L), detector.accepts)
    }

    @Test fun aWakeFromOneDetectorKeepsThatSameFrameFromEveryOtherDetector() {
        newEngine(detectorCount = 2).start()
        val (first, second) = detectors
        first.wakeOnNextAccept = "jarvis"
        device.push()
        nowMs = 500L
        device.push()

        assertEquals(listOf(0L), first.accepts)
        assertEquals(emptyList<Long>(), second.accepts)
    }

    @Test fun aRepeatOfTheSameWakeIdInsideTheCooldownNeverReachesTheConsumerAndIsLogged() {
        newEngine().start()
        detector.wakeOnNextAccept = "jarvis"
        device.push()
        nowMs = 500L
        events.onWake(WakeEvent("fake", "jarvis", "again"))

        assertEquals(listOf(WakeEvent("fake", "jarvis", "transcript")), wakes)
        assertTrue(logs.contains("wake dropped (handoff cooldown) → jarvis [again]"))
    }

    @Test fun aDifferentWakeIdInsideTheCooldownIsDroppedTooAndLogged() {
        newEngine().start()
        detector.wakeOnNextAccept = "jarvis"
        device.push()
        nowMs = 500L
        events.onWake(WakeEvent("fake", "alexa", "direct"))

        assertEquals(listOf("jarvis"), wakes.map { it.wakeId })
        assertTrue(logs.contains("wake dropped (handoff cooldown) → alexa [direct]"))
    }

    @Test fun aWakeExactlyWhenTheCooldownElapsesIsDeliveredAndOpensAFreshWindow() {
        newEngine().start()
        detector.wakeOnNextAccept = "jarvis"
        device.push()
        nowMs = 1_500L
        detector.wakeOnNextAccept = "alexa"
        device.push()
        nowMs = 2_999L
        device.push()
        events.onWake(WakeEvent("fake", "jarvis", "inside the fresh window"))
        nowMs = 3_000L
        device.push()

        assertEquals(listOf("jarvis", "alexa"), wakes.map { it.wakeId })
        assertEquals(listOf(0L, 1_500L, 3_000L), detector.accepts)
    }

    @Test fun restartingCaptureClosesTheCooldown() {
        newEngine().start()
        detector.wakeOnNextAccept = "jarvis"
        device.push()
        nowMs = 500L
        engine.pause()
        engine.start()
        device.push()
        events.onWake(WakeEvent("fake", "alexa", "after restart"))

        assertEquals(listOf(0L, 500L), detector.accepts)
        assertEquals(listOf("transcript", "after restart"), wakes.map { it.transcript })
    }

    @Test fun onWakeIsDeliveredOnTheCaptureThreadAndLogged() {
        newEngine().start()
        detector.wakeOnNextAccept = "jarvis"
        device.push()

        assertEquals(listOf("wake-capture"), wakeThreads)
        assertTrue(logs.contains("wake detected (fake) → jarvis [transcript]"))
    }

    @Test fun onReadyIsDeliveredThroughTheMainThreadPoster() {
        newEngine()
        events.onReady("fake")
        assertEquals(emptyList<String>(), ready)
        runPosted()

        assertEquals(listOf("fake"), ready)
        assertEquals(listOf("wake detector ready (fake)"), logs)
    }

    @Test fun onUnavailableIsDeliveredThroughTheMainThreadPoster() {
        newEngine()
        events.onUnavailable("fake")
        assertEquals(emptyList<String>(), unavailable)
        runPosted()

        assertEquals(listOf("fake"), unavailable)
        assertEquals(listOf("wake unavailable (fake) — detector idle"), logs)
    }

    @Test fun aModelThatFailsWhileTheEngineIsBeingConstructedIsPostedNotDeliveredInline() {
        newEngine(onCreate = { it.events.onUnavailable("fake") })
        assertEquals(emptyList<String>(), unavailable)
        runPosted()

        assertEquals(listOf("fake"), unavailable)
    }

    @Test fun aDetectorReadyWhileTheEngineIsBeingConstructedIsPostedNotDeliveredInline() {
        newEngine(onCreate = { it.events.onReady("fake") })
        assertEquals(emptyList<String>(), ready)
        runPosted()

        assertEquals(listOf("fake"), ready)
    }

    @Test fun diagnosticsAreLoggedWithTheDetectorId() {
        newEngine()
        events.onDiagnostic("fake", "near-miss [hey(72) jarvis(95)] rejected: floor")

        assertEquals(listOf("(fake) near-miss [hey(72) jarvis(95)] rejected: floor"), logs)
    }

    @Test fun callbacksAlreadyQueuedAreNotDeliveredAfterClose() {
        newEngine()
        events.onReady("fake")
        events.onUnavailable("fake")
        engine.close()
        runPosted()

        assertEquals(emptyList<String>(), ready)
        assertEquals(emptyList<String>(), unavailable)
    }

    @Test fun startAfterCloseIsALoggedNoOp() {
        newEngine().close()
        engine.start()

        assertEquals(emptyList<Unit>(), micAcquires)
        assertEquals(0, device.opens)
        assertTrue(logs.contains("start() after close() ignored"))
    }

    @Test fun pauseAfterCloseIsALoggedNoOp() {
        newEngine().close()
        engine.pause()

        assertTrue(logs.contains("pause() after close() ignored"))
        assertTrue(logs.none { it.startsWith("mic paused") })
    }

    @Test fun closingTwiceClosesTheDetectorsOnce() {
        newEngine().close()
        engine.close()

        assertEquals(1, detector.closes)
    }

    @Test fun aWakeRaisedAfterCloseNeverReachesTheConsumer() {
        newEngine().close()
        events.onWake(WakeEvent("fake", "jarvis", "after close"))

        assertEquals(emptyList<WakeEvent>(), wakes)
    }
}
