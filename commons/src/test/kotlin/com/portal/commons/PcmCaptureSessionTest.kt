package com.portal.commons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for the shared capture lifecycle — the concurrency contract that's hard to verify on a real
 * `AudioRecord`. A [FakeDevice] stands in for the Android mic so we can deterministically exercise the
 * parked-read hang, the force-release-on-timeout path, the zombie-start guard, and rebuild. Each test has a
 * hard timeout so a regression (e.g. `stop()` hanging) fails the suite instead of blocking it.
 */
class PcmCaptureSessionTest {

    private fun await(latch: CountDownLatch) = assertTrue("timed out waiting for latch", latch.await(3, TimeUnit.SECONDS))

    /**
     * Fake [PcmDevice]: serves [framesToServe] frames, then parks in [read] until [stop] (when
     * [unblockOnStop]) or [forceUnblock]. [failReads] makes [read] return an error immediately (for rebuild).
     */
    private class FakeDevice(
        private val openResults: ArrayDeque<Boolean> = ArrayDeque(),
        private val unblockOnStop: Boolean = true,
    ) : PcmDevice {
        val openCount = AtomicInteger()
        val stopCount = AtomicInteger()
        val releaseCount = AtomicInteger()

        @Volatile var framesToServe = 0

        @Volatile var failReads = false

        @Volatile private var park = CountDownLatch(1)

        /** Counts down the first time [read] is about to block — the capture thread is *genuinely parked*,
         *  not merely past the "pcm capture started" log. Tests await this before a stop() that must time out. */
        val parked = CountDownLatch(1)

        /** Counts down once the device has been opened ≥ 2 times — i.e. a rebuild (or restart) re-opened it. */
        val reopened = CountDownLatch(1)

        override fun open(): Boolean {
            if (openCount.incrementAndGet() >= 2) reopened.countDown() // a rebuild/restart re-opened the device
            park = CountDownLatch(1) // fresh park per open (supports rebuild + restart)
            return if (openResults.isEmpty()) true else openResults.removeFirst()
        }

        override fun read(buf: ByteArray): Int {
            if (failReads) return -1
            if (framesToServe > 0) {
                framesToServe--
                return buf.size
            }
            parked.countDown() // signal the capture thread has reached the blocking read
            park.await() // simulate a live-but-idle mic: read blocks until stop()/forceUnblock()
            return -1
        }

        override fun stop() {
            stopCount.incrementAndGet()
            if (unblockOnStop) park.countDown()
        }

        override fun release() {
            releaseCount.incrementAndGet()
        }

        fun forceUnblock() = park.countDown()
    }

    @Test(timeout = 10_000)
    fun deliversFramesThenStopsCleanly() {
        val fake = FakeDevice().apply { framesToServe = 3 }
        val frames = AtomicInteger()
        val gotFrames = CountDownLatch(3)
        val stopped = CountDownLatch(1)
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ ->
                frames.incrementAndGet()
                gotFrames.countDown()
            },
            onStopped = { stopped.countDown() },
        )

        assertTrue(session.start())
        await(gotFrames)
        session.stop()
        await(stopped)

        assertEquals(3, frames.get())
        assertEquals(1, fake.openCount.get())
        assertTrue(fake.releaseCount.get() >= 1)
    }

    @Test(timeout = 10_000)
    fun stopReturnsPromptlyWhenReadIsParked() {
        val fake = FakeDevice() // framesToServe = 0 → parks immediately
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ -> },
            onStopped = { stopped.countDown() },
            log = { if (it == "pcm capture started") started.countDown() },
        )

        session.start()
        await(started) // the capture thread is now parked in read()
        session.stop() // must not hang behind the parked read (the original MicCapture bug)
        await(stopped)

        assertTrue(fake.stopCount.get() >= 1)
        assertTrue(fake.releaseCount.get() >= 1)
    }

    @Test(timeout = 10_000)
    fun deliversNoFrameAfterStop() {
        // A frame that arrives exactly as stop() lands must NOT be delivered: stop() sets running=false
        // before it unblocks the read, so the loop's post-read guard drops the in-flight frame. This is what
        // lets a caller safely discard a stopped session and start a fresh one without the old thread's
        // onFrame racing the new one (e.g. a non-thread-safe Vosk consumer).
        val frames = AtomicInteger()
        val parked = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val device = object : PcmDevice {
            override fun open() = true
            override fun read(buf: ByteArray): Int {
                parked.countDown()
                unblock.await() // released by stop() — AFTER stop() has set running=false
                return buf.size // a real frame, arriving just as the session stops
            }
            override fun stop() = unblock.countDown() // unblock the parked read so it returns the frame
            override fun release() {}
        }
        val session = PcmCaptureSession(
            device = device,
            onFrame = { _, _ -> frames.incrementAndGet() },
            onStopped = { stopped.countDown() },
        )

        session.start()
        await(parked)
        session.stop() // running=false, then device.stop() unblocks read → it returns a frame post-stop
        await(stopped) // loop fully exited
        assertEquals("a frame arriving as stop() lands must not be delivered", 0, frames.get())
    }

    @Test(timeout = 10_000)
    fun forceReleasesDeviceWhenJoinTimesOut() {
        val fake = FakeDevice(unblockOnStop = false) // stop() does NOT unblock the parked read
        val logs = CopyOnWriteArrayList<String>()
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ -> },
            log = { logs.add(it) },
            joinTimeoutMs = 50,
        )

        session.start()
        await(fake.parked) // wait until genuinely parked in read(), so stop()'s join deterministically times out
        val releasesBefore = fake.releaseCount.get()
        session.stop() // join times out → last-resort release

        assertTrue("expected a stop-timeout log", logs.any { it.contains("pcm stop timeout") })
        assertTrue("expected a force-release during stop", fake.releaseCount.get() > releasesBefore)
        fake.forceUnblock() // let the zombie thread exit so it doesn't linger
    }

    @Test(timeout = 10_000)
    fun refusesStartWhilePreviousCaptureThreadAlive() {
        val fake = FakeDevice(unblockOnStop = false)
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ -> },
            joinTimeoutMs = 50,
        )

        session.start()
        await(fake.parked) // ensure parked in read() so stop() genuinely times out and leaves a zombie
        session.stop() // times out → zombie still alive

        assertFalse("start() must refuse while the zombie is alive", session.start())

        fake.forceUnblock() // zombie can now exit
        var restarted = false
        for (i in 0 until 100) {
            if (session.start()) {
                restarted = true
                break
            }
            Thread.sleep(20)
        }
        assertTrue("start() should succeed once the zombie exits", restarted)
        session.stop()
        fake.forceUnblock()
    }

    @Test(timeout = 10_000)
    fun startIsIdempotent() {
        val fake = FakeDevice()
        val started = CountDownLatch(1)
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ -> },
            log = { if (it == "pcm capture started") started.countDown() },
        )

        assertTrue(session.start())
        await(started)
        assertTrue(session.start()) // already running → no second thread
        Thread.sleep(50)

        assertEquals(1, fake.openCount.get())
        session.stop()
    }

    @Test(timeout = 10_000)
    fun rebuildsAfterConsecutiveReadFailures() {
        val fake = FakeDevice().apply { failReads = true }
        val gotFrame = CountDownLatch(1)
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ -> gotFrame.countDown() },
            rebuildAfterReadFailures = 3,
            readErrorBackoffMs = 0,
        )

        session.start()
        await(fake.reopened) // the rebuild has actually re-opened the device — assert after the event, not before
        assertTrue("expected a rebuild (re-open) after the failure run", fake.openCount.get() >= 2)

        // Recover: set frames BEFORE clearing failReads so the loop never parks on an empty queue.
        fake.framesToServe = 5
        fake.failReads = false
        await(gotFrame)
        session.stop()
    }

    @Test(timeout = 10_000)
    fun openFailureReportsErrorAndDoesNotRun() {
        val fake = FakeDevice(openResults = ArrayDeque(listOf(false)))
        val error = CountDownLatch(1)
        val stoppedCalls = AtomicInteger()
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ -> },
            onError = { error.countDown() },
            onStopped = { stoppedCalls.incrementAndGet() },
        )

        assertTrue(session.start()) // thread spawns; open() fails inside the loop
        await(error)
        Thread.sleep(50)

        assertEquals("onStopped must not fire when open never succeeded", 0, stoppedCalls.get())
        assertEquals(0, fake.releaseCount.get())
    }

    @Test(timeout = 10_000)
    fun onStartedFiresAfterOpenAndNotOnOpenFailure() {
        // Success: onStarted fires exactly once, after a successful open.
        val okFake = FakeDevice().apply { framesToServe = 1 }
        val started = AtomicInteger()
        val startedLatch = CountDownLatch(1)
        val okSession = PcmCaptureSession(
            device = okFake,
            onFrame = { _, _ -> },
            onStarted = {
                started.incrementAndGet()
                startedLatch.countDown()
            },
        )
        okSession.start()
        await(startedLatch)
        okSession.stop()
        assertEquals(1, started.get())

        // Open failure: onStarted must NOT fire (it isn't "capturing").
        val badFake = FakeDevice(openResults = ArrayDeque(listOf(false)))
        val badStarted = AtomicInteger()
        val errored = CountDownLatch(1)
        val badSession = PcmCaptureSession(
            device = badFake,
            onFrame = { _, _ -> },
            onStarted = { badStarted.incrementAndGet() },
            onError = { errored.countDown() },
        )
        badSession.start()
        await(errored)
        Thread.sleep(50)
        assertEquals("onStarted must not fire when open failed", 0, badStarted.get())
    }

    @Test(timeout = 10_000)
    fun selfStopKeepsZombieGuardUntilLoopExits() {
        // onFrame (on the capture thread) self-stops, then blocks so the loop is still "alive" mid-unwind.
        val fake = FakeDevice().apply { framesToServe = 1 }
        val inFrame = CountDownLatch(1)
        val releaseFrame = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        lateinit var session: PcmCaptureSession
        session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ ->
                session.stop() // self-stop from the capture thread
                inFrame.countDown()
                releaseFrame.await() // hold the capture thread inside the callback (loop hasn't exited yet)
            },
            onStopped = { stopped.countDown() },
        )

        assertTrue(session.start())
        await(inFrame)
        // The capture thread is alive (parked in onFrame after self-stop) → a fresh start() must be refused.
        assertFalse("start() must be refused while the self-stopped loop is still alive", session.start())

        releaseFrame.countDown() // let the callback return so the loop reaches its finally
        await(stopped)

        // Once the loop has exited, start() succeeds again.
        var restarted = false
        for (i in 0 until 100) {
            if (session.start()) {
                restarted = true
                break
            }
            Thread.sleep(20)
        }
        assertTrue("start() should succeed after the loop fully exits", restarted)
        session.stop()
    }

    @Test(timeout = 10_000)
    fun concurrentStartStopDoesNotDeadlockOrCrash() {
        val fake = FakeDevice() // parks in read; stop() unblocks
        val session = PcmCaptureSession(device = fake, onFrame = { _, _ -> })
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

        val workers = (0 until 4).map {
            Thread {
                runCatching {
                    repeat(200) {
                        session.start()
                        session.stop()
                    }
                }.onFailure { errors.add(it) }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join(8_000) }

        assertTrue("workers should finish (no deadlock)", workers.none { it.isAlive })
        assertTrue("no exceptions from concurrent start/stop: $errors", errors.isEmpty())
        session.stop() // ensure final state is stopped
    }
}
