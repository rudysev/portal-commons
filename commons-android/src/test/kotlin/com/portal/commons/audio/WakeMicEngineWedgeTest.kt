package com.portal.commons.audio

import android.content.ContextWrapper
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
 * outlasts [WakeMicEngine.pause]'s bounded stop join, so the next [WakeMicEngine.start] finds that thread still
 * alive and rebuilds the session. The detector is shared across sessions, so the wedged thread and the rebuilt
 * one must never be inside it at the same time — a Vosk recognizer is not thread-safe, and two threads in it
 * abort the process on a Kaldi assertion.
 */
class WakeMicEngineWedgeTest {

    /**
     * Records any moment two threads are inside it. Its first [start] / [accept] takes [firstStartMs] /
     * [firstAcceptMs].
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

        private inline fun enter(what: String, work: () -> Unit) {
            if (inside.incrementAndGet() > 1) overlaps.add(what)
            try {
                work()
            } finally {
                inside.decrementAndGet()
            }
        }

        override fun start() = enter("start") { if (starts.getAndIncrement() == 0) Thread.sleep(firstStartMs) }

        override fun accept(buf: ByteArray, n: Int) = enter("accept") {
            if (accepts.getAndIncrement() == 0) {
                firstAcceptEntered.countDown()
                Thread.sleep(firstAcceptMs)
            }
        }

        override fun updateWakeWords(words: List<WakeWord>) = enter("updateWakeWords") {}

        override fun close() = enter("close") { closes.countDown() }
    }

    /** A mic that opens at once and serves a full frame on every read. */
    private class Device : PcmDevice {
        override fun open() = true

        override fun read(buf: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(10)
            return length
        }

        override fun stop() = Unit

        override fun release() = Unit
    }

    private val logs = CopyOnWriteArrayList<String>()

    private fun newEngine(detector: OverlapDetector) = WakeMicEngine(
        ContextWrapper(null),
        WakeMicConfig(
            wakeWords = emptyList(),
            detectors = listOf(WakeDetector.Factory { detector }),
            onWake = {},
            log = { logs.add(it) },
        ),
        newDevice = ::Device,
        postToMain = { it.run() },
    )

    private fun replayWedge(detector: OverlapDetector): List<String> {
        val engine = newEngine(detector)
        engine.start()
        Thread.sleep(50) // the capture thread is inside its slow detector call
        engine.pause() // the join times out: that thread is still in the detector
        engine.start() // refused → rebuilt session → a new capture thread
        Thread.sleep(700) // let both threads run past the slow call
        engine.close()
        return logs.filter { it.startsWith("pcm start refused") || it.startsWith("wake capture wedged") }
    }

    private val wedged = listOf(
        "pcm start refused — previous capture thread still alive",
        "wake capture wedged — rebuilding session and retrying",
    )

    @Test(timeout = 10_000)
    fun wedgedInStartRebuiltSessionNeverRunsTheDetectorAlongside() {
        // Vosk's reset + silence warm-up, longer than the 400 ms join
        val detector = OverlapDetector(firstStartMs = 600)
        assertEquals(wedged, replayWedge(detector))
        assertEquals("two threads inside the detector at once", emptyList<String>(), detector.overlaps)
    }

    @Test(timeout = 10_000)
    fun wedgedInAcceptRebuiltSessionNeverRunsTheDetectorAlongside() {
        val detector = OverlapDetector(firstAcceptMs = 600) // a slow decode, longer than the 400 ms join
        assertEquals(wedged, replayWedge(detector))
        assertEquals("two threads inside the detector at once", emptyList<String>(), detector.overlaps)
    }

    @Test(timeout = 10_000)
    fun closeReturnsWithinTheDeadlineWhileADetectorIsStuckAndClosesItOnceItReturns() {
        val detector = OverlapDetector(firstAcceptMs = 1_500) // a decode stuck far past close()'s deadline
        val engine = newEngine(detector)
        engine.start()
        assertTrue(detector.firstAcceptEntered.await(2, TimeUnit.SECONDS))

        val startNs = System.nanoTime()
        engine.close()
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

        assertTrue("close() took $tookMs ms", tookMs < 700)
        assertEquals("closed while still inside accept", 1L, detector.closes.count)
        assertTrue(logs.any { it.startsWith("🔴") && it.contains("close deferred") })
        assertTrue("never closed once accept returned", detector.closes.await(3, TimeUnit.SECONDS))
        assertEquals("two threads inside the detector at once", emptyList<String>(), detector.overlaps)
    }
}
