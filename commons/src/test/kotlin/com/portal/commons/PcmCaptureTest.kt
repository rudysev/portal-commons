package com.portal.commons

import com.portal.commons.audio.CaptureLoss
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives a real [PcmCapture] capture thread one frame at a time with a [SteppedDevice], a [RecordingListener] and a
 * main-thread poster that queues, so each capture request rule is observable from outside: listener calls, posted
 * callbacks, device opens.
 */
class PcmCaptureTest {

    /**
     * A mic that serves one all-zero frame per [push], which returns once the capture has finished handling it. Its
     * next [failOpens] opens fail, and while [failReads] is set every read is a device error.
     */
    private class SteppedDevice : PcmDevice {
        private val frames = Semaphore(0)
        private val handled = Semaphore(0)

        @Volatile private var frameOut = false

        fun push() {
            offer()
            assertTrue("capture never finished the frame", handled.tryAcquire(2, TimeUnit.SECONDS))
        }

        /** Serves one frame without waiting for the capture to finish it (capture may stop inside that frame). */
        fun offer() = frames.release()

        @Volatile var opens = 0

        @Volatile var failOpens = 0

        @Volatile var failReads = false

        override fun open(): Boolean {
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
                    frameOut = true
                    length
                }
            }
        }

        /** Runs on the releasing thread, with how many releases this device has had, this one included. */
        @Volatile var onRelease: (Int) -> Unit = {}

        @Volatile private var releases = 0

        override fun stop() = Unit

        override fun release() = onRelease(++releases)
    }

    /** Records every call it receives; [onFrameHook] runs inside each [onFrame]. */
    private class RecordingListener : PcmCapture.Listener {
        val starts = CopyOnWriteArrayList<Unit>()
        val frames = CopyOnWriteArrayList<Int>()
        val losses = CopyOnWriteArrayList<CaptureLoss>()

        @Volatile var onFrameHook: () -> Unit = {}

        override fun onStarted() {
            starts.add(Unit)
        }

        override fun onFrame(buf: ByteArray, n: Int) {
            frames.add(n)
            onFrameHook()
        }

        override fun onLost(loss: CaptureLoss) {
            losses.add(loss)
        }
    }

    private val device = SteppedDevice()
    private val listener = RecordingListener()
    private val posted = CopyOnWriteArrayList<Runnable>()
    private val logs = CopyOnWriteArrayList<String>()
    private val closes = CopyOnWriteArrayList<Unit>()
    private lateinit var capture: PcmCapture

    private fun newCapture(newDevice: () -> PcmDevice = { device }, policy: CapturePolicy = CapturePolicy()) =
        PcmCapture(
            newDevice = newDevice,
            postToMain = { posted.add(it) },
            log = { logs.add(it) },
            policy = policy,
            onClosed = { closes.add(Unit) },
        ).also { capture = it }

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

    /** Waits for the capture thread to post a callback, then drains the main-thread queue. */
    private fun awaitPostedThenRun() {
        await("a callback is posted") { posted.isNotEmpty() }
        runPosted()
    }

    @After fun tearDown() {
        if (::capture.isInitialized) capture.close()
    }

    @Test fun eachCaptureRequestCapturesOnAFreshDevice() {
        val devices = CopyOnWriteArrayList<SteppedDevice>()
        newCapture(newDevice = { SteppedDevice().also { devices.add(it) } })
        capture.start(listener)
        devices.last().push()
        capture.pause()
        capture.start(listener)
        devices.last().push()

        assertEquals(2, devices.size)
        assertEquals(listOf(1, 1), devices.map { it.opens })
        assertEquals(2, listener.frames.size)
    }

    @Test fun aStartWithTheSameListenerDuringAnOpenRequestKeepsTheCapture() {
        newCapture().start(listener)
        device.push()
        capture.start(listener)
        device.push()

        assertEquals(1, device.opens)
        assertEquals(1, listener.starts.size)
        assertEquals(2, listener.frames.size)
    }

    @Test fun aStartWithADifferentListenerEndsTheOpenRequestAndOnlyTheNewListenerReceivesFrames() {
        val devices = CopyOnWriteArrayList<SteppedDevice>()
        newCapture(newDevice = { SteppedDevice().also { devices.add(it) } })
        capture.start(listener)
        devices.last().push()
        val next = RecordingListener()
        capture.start(next)
        devices.last().push()
        devices.first().offer() // the first request's device, were it still capturing
        runPosted()

        assertEquals(2, devices.size)
        assertEquals(1, listener.frames.size)
        assertEquals(1, next.starts.size)
        assertEquals(1, next.frames.size)
        assertEquals(emptyList<CaptureLoss>(), listener.losses + next.losses)
    }

    @Test fun aStartAfterALossCapturesAgain() {
        newCapture()
        device.failOpens = 1
        capture.start(listener)
        awaitPostedThenRun()
        capture.start(listener)
        device.push()

        assertEquals(listOf(NOT_GRANTED), listener.losses)
        assertEquals(1, listener.frames.size)
    }

    @Test fun aRequestedPauseDeliversNoCaptureLoss() {
        newCapture().start(listener)
        device.push()
        capture.pause()
        runPosted()

        assertEquals(emptyList<CaptureLoss>(), listener.losses)
        assertEquals(emptyList<Runnable>(), posted)
    }

    @Test fun aLossIsPostedToTheMainThreadNotDeliveredInline() {
        newCapture()
        device.failOpens = 1
        capture.start(listener)
        await("the loss is posted") { posted.isNotEmpty() }

        assertEquals(emptyList<CaptureLoss>(), listener.losses)
        runPosted()
        assertEquals(listOf(NOT_GRANTED), listener.losses)
    }

    @Test fun aMicThatWontOpenDeliversExactlyOneNotGranted() {
        newCapture()
        device.failOpens = 1
        capture.start(listener)
        awaitPostedThenRun()
        Thread.sleep(50) // room for a second, wrong, report to arrive
        runPosted()

        assertEquals(listOf(NOT_GRANTED), listener.losses)
    }

    @Test fun aFailedRebuildDuringCaptureDeliversExactlyOneDropped() {
        newCapture(policy = CapturePolicy(rebuildAfterReadFailures = 3)).start(listener)
        device.push()
        device.failOpens = 1
        device.failReads = true // the read-failure run ends in a rebuild whose open fails
        awaitPostedThenRun()
        Thread.sleep(50) // room for a second, wrong, report to arrive
        runPosted()

        assertEquals(listOf(DROPPED), listener.losses)
    }

    @Test fun aFullSilenceWindowDeliversExactlyOneSilenced() {
        newCapture(policy = CapturePolicy(silence = CapturePolicy.Silence(2_000L))).start(listener)
        repeat(19) { device.push() } // the device serves all-zero frames: 1.9 s of digital silence
        device.offer() // the 20th fills the 2 s window, and capture ends inside it
        awaitPostedThenRun()
        Thread.sleep(50) // room for a second, wrong, report to arrive
        runPosted()

        assertEquals(listOf(CaptureLoss.Silenced("digital silence for 2000ms")), listener.losses)
    }

    @Test fun aPauseInsideTheSilenceDeliversNothingAndTheNextStartGetsAFullWindow() {
        newCapture(policy = CapturePolicy(silence = CapturePolicy.Silence(2_000L))).start(listener)
        repeat(10) { device.push() }
        capture.pause()
        capture.start(listener)
        repeat(19) { device.push() } // 2.9 s of silence across the pause, but only 1.9 s since the start
        runPosted()

        assertEquals(emptyList<CaptureLoss>(), listener.losses)
    }

    @Test fun aLossThatANewerStartOvertookIsDroppedWhenTheQueueDrains() {
        newCapture()
        device.failOpens = 1
        capture.start(listener)
        await("the loss is posted") { posted.isNotEmpty() }
        capture.start(listener)
        device.push() // the newer start is capturing
        runPosted()

        assertEquals(emptyList<CaptureLoss>(), listener.losses)
    }

    @Test fun aLateStopFromAnEarlierPauseLandingAfterARestartDeliversNothing() {
        val devices = CopyOnWriteArrayList<SteppedDevice>()
        val holdStop = CountDownLatch(1)
        newCapture(newDevice = { SteppedDevice().also { devices.add(it) } })
        capture.start(listener)
        val first = devices.single()
        first.onRelease = { if (Thread.currentThread().name == "pcm-capture") holdStop.await() }
        first.push()
        capture.pause() // the capture thread is held inside its release, so the stop join times out
        capture.start(listener)
        devices.last().push() // the restart is capturing
        holdStop.countDown() // the earlier run's stop now lands
        Thread.sleep(50) // room for the late stop to be posted
        runPosted()

        assertEquals(emptyList<CaptureLoss>(), listener.losses)
        assertEquals(2, listener.frames.size)
    }

    @Test fun aStartRacingTheStopOfAFailedRebuildLeavesTheNextLossReported() {
        val releasing = CountDownLatch(1)
        val holdStop = CountDownLatch(1)
        // The rebuild releases the device once; the capture's end releases it a second time, before reporting.
        device.onRelease = {
            if (it == 2) {
                releasing.countDown()
                holdStop.await()
            }
        }
        newCapture(policy = CapturePolicy(rebuildAfterReadFailures = 3)).start(listener)
        device.push()
        device.failOpens = 1
        device.failReads = true // the rebuild fails; the run is ending but has not yet reported its loss
        assertTrue(releasing.await(2, TimeUnit.SECONDS))
        device.failReads = false
        capture.start(listener) // the consumer starts again before that loss is reported
        holdStop.countDown()
        awaitPostedThenRun()
        if (listener.losses.isNotEmpty()) capture.start(listener) // a consumer retries on a loss

        device.push() // capture is running
        device.failOpens = 1
        device.failReads = true // and this run fails too
        awaitPostedThenRun()

        assertEquals(listOf(DROPPED, DROPPED), listener.losses)
    }

    @Test(timeout = 10_000)
    fun aCloseRacingAStartThatOvertookARequestReturnsOnlyOnceTheOvertakenMicIsFreed() {
        val overtakenReleased = raceAStartThatOvertakesARequest { capture.close() }

        assertTrue("close() returned while the overtaken capture still held the mic", overtakenReleased)
    }

    @Test(timeout = 10_000)
    fun aPauseRacingAStartThatOvertookARequestReturnsOnlyOnceTheOvertakenMicIsFreed() {
        val overtakenReleased = raceAStartThatOvertakesARequest { capture.pause() }

        assertTrue("pause() returned while the overtaken capture still held the mic", overtakenReleased)
    }

    @Test fun callbacksAlreadyQueuedAreNotDeliveredAfterClose() {
        newCapture()
        device.failOpens = 1
        capture.start(listener)
        await("the loss is posted") { posted.isNotEmpty() }
        capture.close()
        runPosted()

        assertEquals(emptyList<CaptureLoss>(), listener.losses)
    }

    @Test fun startAfterCloseIsALoggedNoOp() {
        newCapture().close()
        capture.start(listener)

        assertTrue(capture.isClosed)
        assertEquals(0, device.opens)
        assertTrue(logs.contains("start() after close() ignored"))
    }

    @Test fun pauseAfterCloseIsALoggedNoOp() {
        newCapture().close()
        capture.pause()

        assertTrue(logs.contains("pause() after close() ignored"))
        assertTrue(logs.none { it.startsWith("mic paused") })
    }

    @Test fun closingTwiceRunsOnClosedOnce() {
        newCapture().close()
        capture.close()

        assertEquals(1, closes.size)
    }

    @Test fun aCallbackHeldPastACloseThatGaveUpWaitingNeverReachesTheListenerAfterwardAndRunsOnClosedAsItReturns() {
        val inFrame = CountDownLatch(1)
        val holdFrame = CountDownLatch(1)
        listener.onFrameHook = {
            inFrame.countDown()
            holdFrame.await()
        }
        newCapture().start(listener)
        device.offer()
        assertTrue(inFrame.await(2, TimeUnit.SECONDS))
        capture.close() // the capture thread is held inside the frame, so close() gives up waiting for it
        assertEquals("onClosed ran during a callback", emptyList<Unit>(), closes)
        listener.onFrameHook = {}
        device.offer() // a frame the capture thread might read on its way out
        holdFrame.countDown()
        await("onClosed runs once the frame returns") { closes.size == 1 }
        Thread.sleep(50)

        assertEquals(1, listener.frames.size)
        assertEquals(1, closes.size)
    }

    @Test fun closeFromInsideACallbackRunsOnClosedOnlyOnceTheCallbackReturns() {
        val closesInsideTheCallback = CopyOnWriteArrayList<Int>()
        listener.onFrameHook = {
            capture.close()
            closesInsideTheCallback.add(closes.size)
        }
        newCapture().start(listener)
        device.offer()
        await("onClosed runs") { closes.size == 1 }

        assertEquals("onClosed ran in the middle of the callback", listOf(0), closesInsideTheCallback)
    }

    @Test(timeout = 10_000)
    fun closeReturnsWithinTheDeadlineWhileACallbackIsStuckAndRunsOnClosedOnceItReturns() {
        val inFrame = CountDownLatch(1)
        listener.onFrameHook = {
            inFrame.countDown()
            Thread.sleep(PcmCapture.STOP_DEADLINE_MS * 4) // a frame handler stuck past close()
        }
        newCapture().start(listener)
        device.offer()
        assertTrue(inFrame.await(2, TimeUnit.SECONDS))

        val startNs = System.nanoTime()
        capture.close()
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

        assertTrue("close() took $tookMs ms", tookMs < PcmCapture.STOP_DEADLINE_MS + 100)
        assertEquals("onClosed ran while still inside the callback", emptyList<Unit>(), closes)
        await("onClosed runs once the callback returns") { closes.size == 1 }
    }

    @Test(timeout = 10_000)
    fun wedgedInOnStartedAFreshRequestNeverRunsTheListenerAlongside() {
        replayWedge(OverlapListener(firstStartMs = PAST_THE_JOIN_MS)) // e.g. Vosk's reset + silence warm-up
    }

    @Test(timeout = 10_000)
    fun wedgedInOnFrameAFreshRequestNeverRunsTheListenerAlongside() {
        replayWedge(OverlapListener(firstFrameMs = PAST_THE_JOIN_MS)) // e.g. a slow decode
    }

    @Test(timeout = 10_000)
    fun aStartAfterAWedgedPauseCapturesAgainAndDeliversNoCaptureLoss() {
        val wedged = OverlapListener(firstFrameMs = PAST_THE_JOIN_MS)
        val opensWhileWedged = AtomicInteger()
        newCapture(newDevice = { FlowingDevice { if (wedged.inSlowCall) opensWhileWedged.incrementAndGet() } })
        capture.start(wedged)
        assertTrue(wedged.firstFrameEntered.await(2, TimeUnit.SECONDS))
        capture.pause() // the join times out: that thread is still in the listener
        capture.start(wedged)
        await("the fresh request captures") { wedged.frameCount >= 3 }
        runPosted()
        capture.close()
        runPosted()

        assertEquals("the fresh request opened while the old thread was wedged", 1, opensWhileWedged.get())
        assertEquals(emptyList<CaptureLoss>(), wedged.losses)
        assertEquals("two threads inside the listener at once", emptyList<String>(), wedged.overlaps)
    }

    @Test(timeout = 10_000)
    fun aCallbackQueuedBehindAWedgedOneNeverReachesItsListenerOnceANewerRequestBegan() {
        // Outlasts both stop joins below, so the second request's thread is still queued behind it when the third
        // request begins.
        val wedged = OverlapListener(firstFrameMs = PAST_THE_JOIN_MS * 2)
        val second = RecordingListener()
        val third = RecordingListener()
        newCapture(newDevice = { FlowingDevice {} })
        capture.start(wedged)
        assertTrue(wedged.firstFrameEntered.await(2, TimeUnit.SECONDS))
        capture.start(second) // its capture thread opens, then queues behind the wedged callback
        Thread.sleep(50)
        capture.start(third) // ends the second request while its thread is still queued
        await("the third request captures") { third.frames.isNotEmpty() }
        capture.close()

        assertEquals(emptyList<Unit>(), second.starts)
        assertEquals(emptyList<Int>(), second.frames)
        assertEquals(1, third.starts.size)
    }

    /**
     * Runs [call] on this thread while another thread's start() with a different listener is still stopping the
     * request it overtook: that request's capture thread is held inside its device release. Returns whether the
     * overtaken device had been released by the time [call] returned.
     */
    private fun raceAStartThatOvertakesARequest(call: () -> Unit): Boolean {
        val devices = CopyOnWriteArrayList<SteppedDevice>()
        val holdRelease = CountDownLatch(1)
        val released = AtomicInteger()
        newCapture(newDevice = { SteppedDevice().also { devices.add(it) } })
        capture.start(listener)
        val first = devices.single()
        first.onRelease = {
            if (Thread.currentThread().name == "pcm-capture") holdRelease.await() else released.incrementAndGet()
        }
        first.push()
        val starting = Thread { capture.start(RecordingListener()) }.apply { start() }
        await("the overtaking start built its session") { devices.size == 2 }
        call()
        val releasedOnReturn = released.get() > 0
        holdRelease.countDown()
        starting.join()
        return releasedOnReturn
    }

    private fun replayWedge(wedged: OverlapListener) {
        val opensWhileWedged = AtomicInteger()
        newCapture(newDevice = { FlowingDevice { if (wedged.inSlowCall) opensWhileWedged.incrementAndGet() } })
        capture.start(wedged)
        Thread.sleep(50) // the capture thread is inside its slow callback
        capture.pause() // the join times out: that thread is still in the listener
        capture.start(wedged) // a fresh request → a new capture thread
        Thread.sleep(PAST_THE_JOIN_MS + 100) // let both threads run past the slow call
        capture.close()

        assertEquals("the fresh request opened while the old thread was wedged", 1, opensWhileWedged.get())
        assertEquals("two threads inside the listener at once", emptyList<String>(), wedged.overlaps)
    }

    /**
     * Records any moment two threads are inside it. Its first [onStarted] / [onFrame] takes [firstStartMs] /
     * [firstFrameMs], during which [inSlowCall] is true.
     */
    private class OverlapListener(private val firstStartMs: Long = 0, private val firstFrameMs: Long = 0) :
        PcmCapture.Listener {
        private val inside = AtomicInteger()
        private val starts = AtomicInteger()
        private val frames = AtomicInteger()
        val overlaps = CopyOnWriteArrayList<String>()
        val losses = CopyOnWriteArrayList<CaptureLoss>()
        val firstFrameEntered = CountDownLatch(1)
        val frameCount get() = frames.get()

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

        override fun onStarted() = enter("onStarted") { if (starts.getAndIncrement() == 0) slow(firstStartMs) }

        override fun onFrame(buf: ByteArray, n: Int) = enter("onFrame") {
            if (frames.getAndIncrement() == 0) {
                firstFrameEntered.countDown()
                slow(firstFrameMs)
            }
        }

        override fun onLost(loss: CaptureLoss) {
            losses.add(loss)
        }
    }

    /** A mic that opens at once, running [onOpen] first, and serves a full non-silent frame every 10 ms. */
    private class FlowingDevice(private val onOpen: () -> Unit) : PcmDevice {
        override fun open(): Boolean {
            onOpen()
            return true
        }

        override fun read(buf: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(10)
            buf.fill(1, offset, offset + length)
            return length
        }

        override fun stop() = Unit

        override fun release() = Unit
    }

    private companion object {
        val NOT_GRANTED = CaptureLoss.NotGranted("microphone unavailable")
        val DROPPED = CaptureLoss.Dropped("microphone unavailable on rebuild")

        // Longer than the capture session's stop join, so the capture thread outlives it.
        const val PAST_THE_JOIN_MS = PcmCapture.STOP_DEADLINE_MS + 200
    }
}
