package com.portal.commons

import com.portal.commons.CaptureLoop.Action
import com.portal.commons.audio.CaptureLoss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The frame rules of one capture, driven synchronously: each test plays the device and the clock by hand, feeding
 * [CaptureLoop] read and rebuild results at chosen times and checking the action it returns. No thread, no sleep.
 */
class CaptureLoopTest {

    @Test
    fun partialReadsAssembleIntoFullFrame() {
        // Each frame is exactly FRAME_BYTES even when the device dribbles bytes in small chunks, including a chunk
        // size that does not divide FRAME_BYTES (3200) evenly: every read asks for at most what the frame still needs.
        for (chunk in listOf(640, 999)) {
            val loop = CaptureLoop(openedAtNs = 0)
            var action = loop.first()
            var reads = 0
            var served = 0
            while (action !is Action.Deliver) {
                assertTrue("a frame takes a bounded number of reads (chunk=$chunk)", reads < FRAME)
                val read = action as Action.Read
                assertTrue("a read never crosses the frame boundary (chunk=$chunk)", served + read.length <= FRAME)
                val n = minOf(chunk, read.length)
                action = loop.onRead(ByteArray(n) { (served + it).toByte() }, n, nowNs = 0)
                served += n
                reads++
            }
            assertEquals("the frame is a full FRAME_BYTES (chunk=$chunk)", FRAME, action.frame.size)
            assertEquals("bytes land in order (chunk=$chunk)", (FRAME - 1).toByte(), action.frame[FRAME - 1])
            assertTrue("assembly takes more reads than frames (chunk=$chunk)", reads > 1)
            assertEquals("the next frame starts empty", Action.Read(FRAME), action.then)
        }
    }

    @Test
    fun idleZeroReturnsDoNotTripRebuild() {
        // A non-blocking read returning 0 (no data now) is normal, not a failure: it does not count toward
        // rebuildAfterReadFailures, so even a threshold of one read error never rebuilds. The loop polls again later.
        val loop = CaptureLoop(rebuildAfterReadFailures = 1, openedAtNs = 0)
        loop.first()
        repeat(100) {
            assertEquals(Action.Read(FRAME, CaptureLoop.READ_POLL_MS), loop.onRead(ByteArray(0), 0, nowNs = 0))
        }

        // still live: data delivers a frame once it arrives
        assertTrue(loop.onRead(ByteArray(FRAME), FRAME, nowNs = 0) is Action.Deliver)
    }

    @Test
    fun rebuildsAfterConsecutiveReadFailures() {
        val loop = CaptureLoop(rebuildAfterReadFailures = 3, openedAtNs = 0)
        loop.first()
        loop.onRead(ByteArray(640), 640, nowNs = 0) // a partial frame, which the first error discards

        val backoff = Action.Read(FRAME, CaptureLoop.READ_ERROR_BACKOFF_MS)
        assertEquals("errors back off and drop the partial frame", backoff, loop.onRead(ByteArray(0), -1, nowNs = 0))
        assertEquals(backoff, loop.onRead(ByteArray(0), -1, nowNs = 0))
        assertEquals(Action.Rebuild("read failing"), loop.onRead(ByteArray(0), -1, nowNs = 0))

        // The rebuilt device starts a fresh frame, and recovers: data delivers a frame again.
        assertEquals(Action.Read(FRAME), loop.onRebuilt(reopened = true, nowNs = 0))
        assertTrue(loop.onRead(ByteArray(FRAME), FRAME, nowNs = 0) is Action.Deliver)
    }

    @Test
    fun aSuccessfulReadRestartsTheReadFailureCount() {
        val loop = CaptureLoop(rebuildAfterReadFailures = 2, openedAtNs = 0)
        loop.first()
        loop.onRead(ByteArray(0), -1, nowNs = 0)
        loop.onRead(ByteArray(640), 640, nowNs = 0)

        assertEquals(
            "not consecutive",
            Action.Read(FRAME, CaptureLoop.READ_ERROR_BACKOFF_MS),
            loop.onRead(ByteArray(0), -1, 0),
        )
    }

    @Test
    fun aFailedRebuildIsLostAsDropped() {
        val loop = CaptureLoop(rebuildAfterReadFailures = 1, openedAtNs = 0)
        loop.first()
        loop.onRead(ByteArray(0), -1, nowNs = 0)

        assertEquals(
            Action.End(CaptureLoss.Dropped("microphone unavailable on rebuild")),
            loop.onRebuilt(reopened = false, nowNs = 0),
        )
    }

    @Test
    fun idleWatchdogRebuildsWhenLiveMicReturnsNoData() {
        // A live mic that returns 0 (no data) for idleRebuildMs, a stolen or half-dead slot that never errors, is
        // rebuilt by the idle watchdog, not counted as a read error.
        val loop = CaptureLoop(rebuildAfterReadFailures = 1, idleRebuildMs = 50, openedAtNs = ms(10))
        loop.first()
        assertEquals(Action.Read(FRAME, CaptureLoop.READ_POLL_MS), loop.onRead(ByteArray(0), 0, nowNs = ms(59)))
        assertEquals(Action.Rebuild("no frame for 50ms"), loop.onRead(ByteArray(0), 0, nowNs = ms(60)))

        // The rebuild restarts the idle clock; data on the rebuilt device delivers a frame.
        loop.onRebuilt(reopened = true, nowNs = ms(100))
        assertTrue(loop.onRead(ByteArray(FRAME), FRAME, nowNs = ms(149)) is Action.Deliver)
    }

    @Test
    fun idleWatchdogRebuildsWhenMicTricklesSubFrameBytes() {
        // The watchdog measures delivered frames, not reads: a half-dead mic that returns bytes on every read but never
        // enough to complete a frame still trips it.
        val loop = CaptureLoop(rebuildAfterReadFailures = 1, idleRebuildMs = 150, openedAtNs = 0)
        loop.first()
        for (t in 0L until 150L step 5) {
            assertEquals(Action.Read(FRAME - 1 - (t / 5).toInt()), loop.onRead(ByteArray(1), 1, nowNs = ms(t)))
        }
        assertEquals(Action.Rebuild("no frame for 150ms"), loop.onRead(ByteArray(1), 1, nowNs = ms(150)))
    }

    @Test
    fun aSlowFrameCallbackDoesNotTripTheIdleWatchdog() {
        // The idle clock restarts once the consumer has the frame: the session reads again as soon as the frame
        // callback returns, so a callback slower than idleRebuildMs is not a stalled mic.
        val loop = CaptureLoop(idleRebuildMs = 50, openedAtNs = 0)
        loop.first()
        loop.onRead(ByteArray(FRAME), FRAME, nowNs = ms(10))

        assertEquals(Action.Read(FRAME, CaptureLoop.READ_POLL_MS), loop.onRead(ByteArray(0), 0, nowNs = ms(500)))
        assertEquals(Action.Rebuild("no frame for 50ms"), loop.onRead(ByteArray(0), 0, nowNs = ms(550)))
    }

    @Test
    fun aWindowOfDigitalSilenceEndsCaptureAsSilenced() {
        val loop = CaptureLoop(silenceWindowMs = SILENCE_WINDOW_MS, openedAtNs = 0)
        loop.first()

        // 2 s of 16 kHz audio in 100 ms frames is 20 frames: the 20th is delivered, and then capture ends.
        repeat(FRAMES_IN_WINDOW - 1) { assertEquals(Action.Read(FRAME), loop.deliver(sample = 0)) }
        assertEquals(SILENCED, loop.deliver(sample = 0))
    }

    @Test
    fun oneNonZeroSampleRestartsTheSilenceWindow() {
        // Frame 20 opens with one non-zero sample, so it leaves 1,599 trailing zeros: the window (32,000 samples) is
        // full only after 20 more all-zero frames (1,599 + 20 × 1,600), on frame 40.
        val loop = CaptureLoop(silenceWindowMs = SILENCE_WINDOW_MS, openedAtNs = 0)
        loop.first()
        repeat(FRAMES_IN_WINDOW - 1) { loop.deliver(sample = 0) }
        assertEquals(Action.Read(FRAME), loop.deliver(sample = 1))

        repeat(FRAMES_IN_WINDOW - 1) { assertEquals(Action.Read(FRAME), loop.deliver(sample = 0)) }
        assertEquals("silenced on frame 40", SILENCED, loop.deliver(sample = 0))
    }

    @Test
    fun aRebuildRestartsTheSilenceWindow() {
        // 10 silent frames, a stall the idle watchdog rebuilds, then silence on the rebuilt device: silenced only after
        // a full window there, 10 + 20 frames.
        val loop = CaptureLoop(idleRebuildMs = 50, silenceWindowMs = SILENCE_WINDOW_MS, openedAtNs = 0)
        loop.first()
        repeat(FRAMES_IN_WINDOW / 2) { loop.deliver(sample = 0) }
        loop.onRead(ByteArray(0), 0, nowNs = 0)
        assertEquals(Action.Rebuild("no frame for 50ms"), loop.onRead(ByteArray(0), 0, nowNs = ms(50)))
        loop.onRebuilt(reopened = true, nowNs = ms(50))

        repeat(FRAMES_IN_WINDOW - 1) { assertEquals(Action.Read(FRAME), loop.deliver(sample = 0, nowNs = ms(50))) }
        assertEquals(SILENCED, loop.deliver(sample = 0, nowNs = ms(50)))
    }

    @Test
    fun withoutASilenceWindowDigitalSilenceKeepsCapturing() {
        val loop = CaptureLoop(openedAtNs = 0)
        loop.first()

        repeat(2 * FRAMES_IN_WINDOW) { assertEquals("never silenced", Action.Read(FRAME), loop.deliver(sample = 0)) }
    }

    @Test
    fun withRebuildOnSilenceAWindowOfSilenceRebuildsAndCaptureGoesOn() {
        // A client the system silences mid-capture (Portal+ gen1 backgrounding the app), which a reopen brings back.
        val loop = CaptureLoop(silenceWindowMs = SILENCE_WINDOW_MS, rebuildOnSilence = true, openedAtNs = 0)
        loop.first()
        repeat(FRAMES_IN_WINDOW - 1) { loop.deliver(sample = 0) }
        assertEquals(SILENCE_REBUILD, loop.deliver(sample = 0))
        loop.onRebuilt(reopened = true, nowNs = 0)

        repeat(3 * FRAMES_IN_WINDOW) { assertEquals("a live stream is no loss", Action.Read(FRAME), loop.deliver(1)) }
    }

    @Test
    fun withRebuildOnSilenceASilenceThatSurvivesTheRebuildEndsCaptureAsSilenced() {
        val loop = CaptureLoop(silenceWindowMs = SILENCE_WINDOW_MS, rebuildOnSilence = true, openedAtNs = 0)
        loop.first()

        // One window on the first open, one more on the rebuilt device, then the loss.
        repeat(FRAMES_IN_WINDOW - 1) { loop.deliver(sample = 0) }
        assertEquals(SILENCE_REBUILD, loop.deliver(sample = 0))
        loop.onRebuilt(reopened = true, nowNs = 0)
        repeat(FRAMES_IN_WINDOW - 1) { assertEquals(Action.Read(FRAME), loop.deliver(sample = 0)) }
        assertEquals(SILENCED, loop.deliver(sample = 0))
    }

    @Test
    fun withRebuildOnSilenceASoundAfterTheRebuildEarnsAnotherRebuild() {
        // A window of silence (rebuild 1), one sound on the rebuilt device, then silence: the sound means the reopen
        // worked, so the next window rebuilds again before a last window ends capture.
        val loop = CaptureLoop(silenceWindowMs = SILENCE_WINDOW_MS, rebuildOnSilence = true, openedAtNs = 0)
        loop.first()
        repeat(FRAMES_IN_WINDOW - 1) { loop.deliver(sample = 0) }
        assertEquals(SILENCE_REBUILD, loop.deliver(sample = 0))
        loop.onRebuilt(reopened = true, nowNs = 0)

        loop.deliver(sample = 1)
        repeat(FRAMES_IN_WINDOW - 1) { loop.deliver(sample = 0) }
        assertEquals("rebuild 2", SILENCE_REBUILD, loop.deliver(sample = 0))
        loop.onRebuilt(reopened = true, nowNs = 0)
        repeat(FRAMES_IN_WINDOW - 1) { loop.deliver(sample = 0) }
        assertEquals(SILENCED, loop.deliver(sample = 0))
    }

    /**
     * Feeds one full frame whose first sample is [sample] and the rest digital silence, and returns what the loop does
     * once that frame is delivered.
     */
    private fun CaptureLoop.deliver(sample: Short, nowNs: Long = 0): Action {
        val bytes = ByteArray(FRAME)
        bytes[0] = sample.toByte()
        bytes[1] = (sample.toInt() shr 8).toByte()
        val action = onRead(bytes, FRAME, nowNs)
        assertTrue("a full frame is delivered: $action", action is Action.Deliver)
        return (action as Action.Deliver).then
    }

    private fun ms(ms: Long) = TimeUnit.MILLISECONDS.toNanos(ms)

    private companion object {
        const val FRAME = PcmCaptureFormat.FRAME_BYTES
        const val SILENCE_WINDOW_MS = 2_000L
        const val FRAMES_IN_WINDOW = 20
        val SILENCED = Action.End(CaptureLoss.Silenced("digital silence for 2000ms"))
        val SILENCE_REBUILD = Action.Rebuild("digital silence for 2000ms")
    }
}
