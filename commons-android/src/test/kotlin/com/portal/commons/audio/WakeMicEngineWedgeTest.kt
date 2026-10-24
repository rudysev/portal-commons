package com.portal.commons.audio

import android.content.ContextWrapper
import com.portal.commons.PcmCaptureSession
import com.portal.commons.PcmDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Replays the gen2 wedge that aborted the process inside Vosk (2026-10-22): a detector call on the capture thread
 * outlasts [WakeMicEngine.pause]'s bounded stop join, so that thread is still alive when the next
 * [WakeMicEngine.start] begins a fresh session. The detector is shared across sessions, so the wedged thread and the
 * fresh one must never be inside it at the same time — a Vosk recognizer is not thread-safe, and two threads in it
 * abort the process on a Kaldi assertion.
 */
class WakeMicEngineWedgeTest {

    /**
     * Records any moment two threads are inside it. Its first [start] / [accept] takes [firstStartMs] /
     * [firstAcceptMs], during which [inSlowCall] is true.
     */
    private class OverlapDetector(private val firstStartMs: Long = 0, private val firstAcceptMs: Long = 0) :
        WakeDetector {
        override val id = "fake"
        private val inside = AtomicInteger()
        private val starts = AtomicInteger()
        private val accepts = AtomicInteger()
        val overlaps = CopyOnWriteArrayList<String>()
        val closes = CountDownLatch(1)
        val firstAcceptEntered = CountDownLatch(1)
        val acceptCount get() = accepts.get()

        @Volatile var inSlowCall = false
            private set

        private fun slow(ms: Long) {
            inSlowCall = true
            try {
                Thread.sleep(ms)
            } finally {
                inSlowCall = false
            }
        }

        private inline fun enter(what: String, work: () -> Unit) {
            if (inside.incrementAndGet() > 1) overlaps.add(what)
            try {
                work()
            } finally {
                inside.decrementAndGet()
            }
        }

        override fun start() = enter("start") { if (starts.getAndIncrement() == 0) slow(firstStartMs) }

        override fun accept(buf: ByteArray, n: Int) = enter("accept") {
            if (accepts.getAndIncrement() == 0) {
                firstAcceptEntered.countDown()
                slow(firstAcceptMs)
            }
        }

        override fun updateWakeWords(words: List<WakeWord>) = enter("updateWakeWords") {}

        override fun close() = enter("close") { closes.countDown() }
    }

    /** A mic that opens at once, running [onOpen] first, and serves a full frame on every read. */
    private class Device(private val onOpen: () -> Unit) : PcmDevice {
        override fun open(): Boolean {
            onOpen()
            return true
        }

        override fun read(buf: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(10)
            return length
        }

        override fun stop() = Unit

        override fun release() = Unit
    }

    private val logs = CopyOnWriteArrayList<String>()
    private val losses = CopyOnWriteArrayList<CaptureLoss>()
    private val posted = CopyOnWriteArrayList<Runnable>()

    // Opens of a device while the previous capture thread was still inside its slow detector call: the wedge.
    private val opensWhileWedged = AtomicInteger()

    private fun runPosted() {
        while (posted.isNotEmpty()) posted.removeAt(0).run()
    }

    private fun newEngine(detector: OverlapDetector) = WakeMicEngine(
        ContextWrapper(null),
        WakeMicConfig(
            wakeWords = emptyList(),
            detectors = listOf(WakeDetector.Factory { detector }),
            onWake = {},
            onCaptureLost = { losses.add(it) },
            log = { logs.add(it) },
        ),
        newDevice = { Device { if (detector.inSlowCall) opensWhileWedged.incrementAndGet() } },
        postToMain = { posted.add(it) },
    )

    private fun replayWedge(detector: OverlapDetector) {
        val engine = newEngine(detector)
        engine.start()
        Thread.sleep(50) // the capture thread is inside its slow detector call
        engine.pause() // the join times out: that thread is still in the detector
        engine.start() // a fresh session → a new capture thread
        Thread.sleep(PAST_THE_JOIN_MS + 100) // let both threads run past the slow call
        engine.close()
        assertEquals("the fresh session opened while the old thread was wedged", 1, opensWhileWedged.get())
    }

    @Test(timeout = 10_000)
    fun wedgedInStartFreshSessionNeverRunsTheDetectorAlongside() {
        val detector = OverlapDetector(firstStartMs = PAST_THE_JOIN_MS) // Vosk's reset + silence warm-up
        replayWedge(detector)
        assertEquals("two threads inside the detector at once", emptyList<String>(), detector.overlaps)
    }

    @Test(timeout = 10_000)
    fun wedgedInAcceptFreshSessionNeverRunsTheDetectorAlongside() {
        val detector = OverlapDetector(firstAcceptMs = PAST_THE_JOIN_MS) // a slow decode
        replayWedge(detector)
        assertEquals("two threads inside the detector at once", emptyList<String>(), detector.overlaps)
    }

    @Test(timeout = 10_000)
    fun aStartAfterAWedgedPauseCapturesOnAFreshSessionAndDeliversNoCaptureLoss() {
        val detector = OverlapDetector(firstAcceptMs = PAST_THE_JOIN_MS) // a slow decode
        val engine = newEngine(detector)
        engine.start()
        assertTrue(detector.firstAcceptEntered.await(2, TimeUnit.SECONDS))
        engine.pause() // the join times out: that thread is still in the detector
        engine.start()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (detector.acceptCount < 3 && System.nanoTime() < deadline) Thread.sleep(10)
        runPosted()
        engine.close()
        runPosted()

        assertTrue("the fresh session never captured", detector.acceptCount >= 3)
        assertEquals("the fresh session opened while the old thread was wedged", 1, opensWhileWedged.get())
        assertEquals(emptyList<CaptureLoss>(), losses)
    }

    @Test(timeout = 10_000)
    fun closeReturnsWithinTheDeadlineWhileADetectorIsStuckAndClosesItOnceItReturns() {
        val detector = OverlapDetector(firstAcceptMs = FAR_PAST_THE_DEADLINE_MS) // a decode stuck past close()
        val engine = newEngine(detector)
        engine.start()
        assertTrue(detector.firstAcceptEntered.await(2, TimeUnit.SECONDS))

        val startNs = System.nanoTime()
        engine.close()
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

        assertTrue("close() took $tookMs ms", tookMs < PAST_THE_JOIN_MS + 100)
        assertEquals("closed while still inside accept", 1L, detector.closes.count)
        assertTrue(logs.any { it.startsWith("🔴") && it.contains("close deferred") })
        assertTrue("never closed once accept returned", detector.closes.await(3, TimeUnit.SECONDS))
        assertEquals("two threads inside the detector at once", emptyList<String>(), detector.overlaps)
    }

    private companion object {
        // Longer than the capture session's stop join, so the capture thread outlives it.
        const val PAST_THE_JOIN_MS = PcmCaptureSession.STOP_DEADLINE_MS + 200
        const val FAR_PAST_THE_DEADLINE_MS = PcmCaptureSession.STOP_DEADLINE_MS * 4
    }
}
