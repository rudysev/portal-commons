package com.portal.commons

import com.portal.commons.audio.CaptureLoss
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
 * `AudioRecord`. Two fake devices stand in for the Android mic:
 *  - [FakeDevice] is **non-blocking** (returns `0` when idle, like the real `READ_NON_BLOCKING`), with a
 *    configurable `bytesPerRead` so we can exercise partial-read frame assembly and the idle/error paths.
 *  - [BlockingFake] deliberately parks in `read` on a latch to stand in for the residual *hung native call*
 *    case (a non-blocking read never parks in production), exercising the bounded-join + alive-thread guard
 *    and the post-stop "no frame after stop" gate.
 * Each test has a hard timeout so a regression (e.g. `stop()` hanging) fails the suite instead of blocking it.
 * Tests pass `pollMs = 0` so the idle path doesn't add real sleeps.
 */
class PcmCaptureSessionTest {

    private fun await(latch: CountDownLatch) =
        assertTrue("timed out waiting for latch", latch.await(3, TimeUnit.SECONDS))

    /** Every [PcmCaptureSession.onLost] it was handed; [lost] counts down at the first. */
    private class Losses {
        val all = CopyOnWriteArrayList<CaptureLoss>()
        val lost = CountDownLatch(1)

        fun add(loss: CaptureLoss) {
            all.add(loss)
            lost.countDown()
        }
    }

    /** A log that counts [stopped] down once the capture thread has logged its end. */
    private class StopLog(private val forward: (String) -> Unit = {}) : (String) -> Unit {
        val stopped = CountDownLatch(1)

        override fun invoke(message: String) {
            forward(message)
            if (message == "pcm capture stopped") stopped.countDown()
        }
    }

    /**
     * Non-blocking fake: serves [framesToServe] frames in [bytesPerRead]-byte chunks (assembled by the
     * session into full [PcmCaptureFormat.FRAME_BYTES] frames), returns `0` (idle) once exhausted, and `-1`
     * (error) while [failReads]. Never parks.
     */
    private class FakeDevice(private val openResults: ArrayDeque<Boolean> = ArrayDeque()) : PcmDevice {
        val openCount = AtomicInteger()
        val stopCount = AtomicInteger()
        val releaseCount = AtomicInteger()
        val readCount = AtomicInteger()

        @Volatile var framesToServe = 0

        @Volatile var bytesPerRead = PcmCaptureFormat.FRAME_BYTES // smaller → exercise partial assembly

        @Volatile var failReads = false

        private var frameBytesServed = 0 // capture-thread only

        /** Counts down once the device has been opened ≥ 2 times — i.e. a rebuild re-opened it. */
        val reopened = CountDownLatch(1)

        override fun open(): Boolean {
            if (openCount.incrementAndGet() >= 2) reopened.countDown()
            frameBytesServed = 0
            return if (openResults.isEmpty()) true else openResults.removeFirst()
        }

        override fun read(buf: ByteArray, offset: Int, length: Int): Int {
            readCount.incrementAndGet()
            return when {
                failReads -> -1

                framesToServe <= 0 -> 0

                // idle: no data right now (NOT an error)
                else -> serve(length)
            }
        }

        private fun serve(length: Int): Int {
            val give = minOf(length, bytesPerRead, PcmCaptureFormat.FRAME_BYTES - frameBytesServed)
            frameBytesServed += give
            if (frameBytesServed >= PcmCaptureFormat.FRAME_BYTES) {
                framesToServe--
                frameBytesServed = 0
            }
            return give
        }

        override fun stop() {
            stopCount.incrementAndGet()
        }

        override fun release() {
            releaseCount.incrementAndGet()
        }
    }

    /**
     * Parks in [read] on a latch to simulate a *hung native call* (the only residual wedge once reads are
     * non-blocking). [parked] signals the thread reached the read; [unpark] lets it return a full frame.
     */
    private class BlockingFake : PcmDevice {
        val openCount = AtomicInteger()
        val releaseCount = AtomicInteger()
        val parked = CountDownLatch(1)
        private val proceed = CountDownLatch(1)

        override fun open(): Boolean {
            openCount.incrementAndGet()
            return true
        }

        override fun read(buf: ByteArray, offset: Int, length: Int): Int {
            parked.countDown()
            proceed.await() // stand in for a hung native read; in production READ_NON_BLOCKING never parks
            return length // a full frame, "arriving" only once unparked
        }

        override fun stop() = Unit

        override fun release() {
            releaseCount.incrementAndGet()
        }

        fun unpark() = proceed.countDown()
    }

    @Test(timeout = 10_000)
    fun deliversFramesThenStopsCleanly() {
        val fake = FakeDevice().apply { framesToServe = 3 }
        val frames = AtomicInteger()
        val gotFrames = CountDownLatch(3)
        val log = StopLog()
        val losses = Losses()
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, n ->
                assertEquals(PcmCaptureFormat.FRAME_BYTES, n)
                frames.incrementAndGet()
                gotFrames.countDown()
            },
            onLost = losses::add,
            log = log,
            pollMs = 0,
        )

        assertTrue(session.start())
        await(gotFrames)
        session.stop()
        await(log.stopped)

        assertEquals(3, frames.get())
        assertEquals("a requested stop is not a loss", emptyList<CaptureLoss>(), losses.all)
        assertEquals(1, fake.openCount.get())
        assertTrue(fake.releaseCount.get() >= 1)
    }

    @Test(timeout = 10_000)
    fun partialReadsAssembleIntoFullFrame() {
        // Each onFrame must be exactly FRAME_BYTES even when the device dribbles bytes in small chunks —
        // including a chunk size that does NOT divide FRAME_BYTES (3200) evenly.
        for (chunk in listOf(640, 999)) {
            val fake = FakeDevice().apply {
                framesToServe = 2
                bytesPerRead = chunk
            }
            val frames = AtomicInteger()
            val gotFrames = CountDownLatch(2)
            val log = StopLog()
            val session = PcmCaptureSession(
                device = fake,
                onFrame = { _, n ->
                    assertEquals("frame must be a full FRAME_BYTES (chunk=$chunk)", PcmCaptureFormat.FRAME_BYTES, n)
                    frames.incrementAndGet()
                    gotFrames.countDown()
                },
                log = log,
                pollMs = 0,
            )
            session.start()
            await(gotFrames)
            session.stop()
            await(log.stopped)
            assertEquals(2, frames.get())
            assertTrue("assembly should take more reads than frames (chunk=$chunk)", fake.readCount.get() > 2)
        }
    }

    @Test(timeout = 10_000)
    fun idleZeroReturnsDoNotTripRebuild() {
        // A non-blocking read returning 0 (no data now) is NORMAL, not a failure: it must not call onReadError
        // nor count toward rebuildAfterReadFailures.
        val fake = FakeDevice() // framesToServe = 0 → read returns 0 (idle)
        val readErrors = AtomicInteger()
        val gotFrame = CountDownLatch(1)
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ -> gotFrame.countDown() },
            onReadError = { readErrors.incrementAndGet() },
            rebuildAfterReadFailures = 3,
            readErrorBackoffMs = 0,
            pollMs = 0,
        )

        session.start()
        Thread.sleep(100) // let the loop poll the idle device many times
        assertEquals("idle 0-returns must not be read errors", 0, readErrors.get())
        assertEquals("idle must not rebuild", 1, fake.openCount.get())

        fake.framesToServe = 1 // prove the loop is still live and delivers once data arrives
        await(gotFrame)
        session.stop()
    }

    @Test(timeout = 10_000)
    fun stopReturnsPromptlyWhenIdle() {
        val fake = FakeDevice() // idle: read returns 0
        val started = CountDownLatch(1)
        val log = StopLog { if (it == "pcm capture started") started.countDown() }
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ -> },
            log = log,
            pollMs = 0,
        )

        session.start()
        await(started)
        session.stop() // loop sees running=false within a poll and exits; its finally releases
        await(log.stopped)

        assertTrue(fake.stopCount.get() >= 1)
        assertTrue(fake.releaseCount.get() >= 1)
    }

    @Test(timeout = 10_000)
    fun residualPartialFrameDiscardedOnStop() {
        // A partial frame in flight when stop() lands is discarded — onFrame only ever fires on a full frame.
        val parked = CountDownLatch(1)
        val log = StopLog()
        val frames = AtomicInteger()
        val device = object : PcmDevice {
            private var served = false
            override fun open() = true
            override fun read(buf: ByteArray, offset: Int, length: Int): Int {
                if (!served) {
                    served = true
                    return minOf(length, 640)
                } // one partial chunk, then idle
                parked.countDown()
                return 0
            }
            override fun stop() = Unit
            override fun release() = Unit
        }
        val session = PcmCaptureSession(
            device = device,
            onFrame = { _, _ -> frames.incrementAndGet() },
            log = log,
            pollMs = 0,
        )

        session.start()
        await(parked) // a partial (640 B) frame is buffered; device now idle
        session.stop()
        await(log.stopped)
        assertEquals("a partial frame must not be delivered on stop", 0, frames.get())
    }

    @Test(timeout = 10_000)
    fun deliversNoFrameAfterStop() {
        // A frame that "arrives" exactly as stop() lands must NOT be delivered: stop() sets running=false, and
        // the loop's post-read gate drops the in-flight frame. This lets a caller safely discard a stopped
        // session and start a fresh one without the old thread's onFrame racing the new one (e.g. Vosk).
        val frames = AtomicInteger()
        val log = StopLog()
        val losses = Losses()
        val fake = BlockingFake()
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ -> frames.incrementAndGet() },
            onLost = losses::add,
            log = log,
            stopTimeoutMs = 50,
            pollMs = 0,
        )

        session.start()
        await(fake.parked) // thread is parked in read() (standing in for a hung native call)
        session.stop() // running=false; join times out (thread parked); returns
        fake.unpark() // read now returns a full frame — but running=false, so the gate must drop it
        await(log.stopped)
        assertEquals("a frame arriving as stop() lands must not be delivered", 0, frames.get())
        assertEquals("a stop that timed out is still not a loss", emptyList<CaptureLoss>(), losses.all)
    }

    @Test(timeout = 10_000)
    fun forceReleasesDeviceWhenJoinTimesOut() {
        // If the capture thread overruns the join (a stuck callback or a hung native call), stop() force-
        // releases the device as a last resort so the mic slot can't stay held, and the alive-thread guard
        // makes a concurrent start() refuse.
        val fake = BlockingFake()
        val logs = CopyOnWriteArrayList<String>()
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ -> },
            log = { logs.add(it) },
            stopTimeoutMs = 50,
            pollMs = 0,
        )

        session.start()
        await(fake.parked)
        val releasesBefore = fake.releaseCount.get()
        session.stop() // join times out → force-release
        assertTrue("expected a stop-timeout log", logs.any { it.contains("pcm stop timeout") })
        assertTrue("expected a force-release during stop", fake.releaseCount.get() > releasesBefore)
        assertFalse("start() must refuse while the thread is still alive", session.start())

        fake.unpark() // let the thread exit so it doesn't linger
    }

    @Test(timeout = 10_000)
    fun stopStaysWithinItsTimeoutWhenTheForceReleaseHangsToo() {
        val releaseEntered = CountDownLatch(1)
        val releaseHung = CountDownLatch(1)
        val capture = BlockingFake()
        val fake = object : PcmDevice by capture {
            override fun release() {
                releaseEntered.countDown()
                releaseHung.await() // a native release that hangs as well
            }
        }
        val session = PcmCaptureSession(device = fake, onFrame = { _, _ -> }, pollMs = 0)

        session.start()
        await(capture.parked)
        val startNs = System.nanoTime()
        session.stop(timeoutMs = 200)
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

        assertTrue("stop() took $tookMs ms", tookMs < 300)
        await(releaseEntered) // the force-release was still attempted
        releaseHung.countDown()
        capture.unpark()
    }

    @Test(timeout = 10_000)
    fun stopStaysWithinItsTimeoutWhileOnLostIsRunning() {
        // A loss reported before the stop is still reported, but a slow onLost must not hold stop() past its deadline.
        val inOnLost = CountDownLatch(1)
        val onLostHung = CountDownLatch(1)
        val session = PcmCaptureSession(
            device = FakeDevice(openResults = ArrayDeque(listOf(false))),
            onFrame = { _, _ -> },
            onLost = {
                inOnLost.countDown()
                onLostHung.await()
            },
            pollMs = 0,
            stopTimeoutMs = 200,
        )

        session.start()
        await(inOnLost)
        val startNs = System.nanoTime()
        session.stop()
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
        onLostHung.countDown()

        assertTrue("stop() took $tookMs ms", tookMs < 300)
    }

    @Test(timeout = 10_000)
    fun idleWatchdogRebuildsWhenLiveMicReturnsNoData() {
        // A live mic that returns 0 (no data) for idleRebuildMs — a stolen/half-dead slot that never errors —
        // must be recovered by a device rebuild, WITHOUT being counted as a read error.
        val fake = FakeDevice() // framesToServe = 0 → read returns 0 (idle)
        val readErrors = AtomicInteger()
        val gotFrame = CountDownLatch(1)
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ -> gotFrame.countDown() },
            onReadError = { readErrors.incrementAndGet() },
            idleRebuildMs = 50,
            pollMs = 0,
        )

        session.start()
        await(fake.reopened) // sustained idle triggered a device rebuild (open #2)
        assertTrue("idle must rebuild the device", fake.openCount.get() >= 2)
        assertEquals("idle 0-returns must NOT be counted as read errors", 0, readErrors.get())

        fake.framesToServe = 1 // recovery: data flows again → a frame is delivered
        await(gotFrame)
        session.stop()
    }

    @Test(timeout = 10_000)
    fun idleWatchdogRebuildsWhenMicTricklesSubFrameBytes() {
        // The watchdog measures DELIVERED FRAMES, not raw reads. A half-dead mic that dribbles sub-frame bytes
        // forever (n>0 every read but never enough to complete a 3200B frame) must still trip it — the old
        // "reset the clock on any n>0" logic would never fire here, leaving the consumer silently deaf.
        val opens = AtomicInteger()
        val reopened = CountDownLatch(1)
        val readErrors = AtomicInteger()
        val device = object : PcmDevice {
            override fun open(): Boolean {
                if (opens.incrementAndGet() >= 2) reopened.countDown()
                return true
            }
            override fun read(buf: ByteArray, offset: Int, length: Int): Int {
                Thread.sleep(5) // pace the trickle so a full frame can't assemble within idleRebuildMs
                return 1 // one byte: n>0 (never an error, never a full frame)
            }
            override fun stop() = Unit
            override fun release() = Unit
        }
        val session = PcmCaptureSession(
            device = device,
            onFrame = { _, _ -> },
            onReadError = { readErrors.incrementAndGet() },
            idleRebuildMs = 150,
            pollMs = 0,
        )

        session.start()
        await(reopened) // a trickle that never completes a frame tripped the frame-based idle watchdog → rebuild
        assertEquals("trickle bytes (n>0) are not read errors", 0, readErrors.get())
        session.stop()
    }

    @Test(timeout = 10_000)
    fun stopDuringInFlightRebuildExitsCleanly() {
        // stop() landing while a rebuild's open() is in flight must exit cleanly: when open() returns, the
        // post-rebuild `continue` hits the top-of-loop running gate and breaks — no frame delivered, no
        // force-release timeout, and no zombie left blocking a later start() (the mic slot stays reclaimable).
        val inRebuildOpen = CountDownLatch(1)
        val proceedOpen = CountDownLatch(1)
        val frames = AtomicInteger()
        val opens = AtomicInteger()
        val logs = CopyOnWriteArrayList<String>()
        val log = StopLog { logs.add(it) }
        val losses = Losses()
        val device = object : PcmDevice {
            override fun open(): Boolean {
                if (opens.incrementAndGet() == 2) { // the rebuild's reopen — park it so stop() lands mid-rebuild
                    inRebuildOpen.countDown()
                    proceedOpen.await()
                }
                return true
            }
            override fun read(buf: ByteArray, offset: Int, length: Int) = -1 // always error → triggers the rebuild
            override fun stop() = Unit
            override fun release() = Unit
        }
        val session = PcmCaptureSession(
            device = device,
            onFrame = { _, _ -> frames.incrementAndGet() },
            onLost = losses::add,
            log = log,
            rebuildAfterReadFailures = 3,
            readErrorBackoffMs = 0,
            pollMs = 0,
        )

        session.start()
        await(inRebuildOpen) // the loop is parked inside the rebuild's open()
        val stopper = Thread { session.stop() }.apply { start() }
        Thread.sleep(50) // stop() has set running=false and is in its bounded join
        proceedOpen.countDown() // open() returns; loop continues → top gate sees running=false → exits
        stopper.join(5_000)
        await(log.stopped)

        assertEquals("no frame delivered when stop lands during a rebuild", 0, frames.get())
        assertEquals("a stop during a rebuild is not a loss", emptyList<CaptureLoss>(), losses.all)
        assertFalse("clean exit — no force-release timeout", logs.any { it.contains("pcm stop timeout") })
        assertTrue("no zombie — start() succeeds after the rebuild-stop", session.start())
        session.stop()
    }

    @Test(timeout = 10_000)
    fun refusesStartWhilePreviousCaptureThreadAlive() {
        val fake = BlockingFake()
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ -> },
            stopTimeoutMs = 50,
            pollMs = 0,
        )

        session.start()
        await(fake.parked) // parked (hung-native stand-in) so stop() times out and leaves the thread alive
        session.stop() // times out → thread still alive

        assertFalse("start() must refuse while the previous thread is alive", session.start())

        fake.unpark() // thread can now exit
        val restarted = (0 until 100).any {
            session.start().also { started -> if (!started) Thread.sleep(20) }
        }
        assertTrue("start() should succeed once the previous thread exits", restarted)
        session.stop()
    }

    @Test(timeout = 10_000)
    fun startIsIdempotent() {
        val fake = FakeDevice()
        val started = CountDownLatch(1)
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ -> },
            log = { if (it == "pcm capture started") started.countDown() },
            pollMs = 0,
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
            pollMs = 0,
        )

        session.start()
        await(fake.reopened) // the rebuild has actually re-opened the device
        assertTrue("expected a rebuild (re-open) after the failure run", fake.openCount.get() >= 2)

        // Recover: set frames BEFORE clearing failReads so the loop has data the moment errors stop.
        fake.framesToServe = 5
        fake.failReads = false
        await(gotFrame)
        session.stop()
    }

    @Test(timeout = 10_000)
    fun openFailureIsLostAsNotGrantedAndDoesNotRun() {
        val fake = FakeDevice(openResults = ArrayDeque(listOf(false)))
        val losses = Losses()
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ -> },
            onLost = losses::add,
            pollMs = 0,
        )

        assertTrue(session.start()) // thread spawns; open() fails inside the loop
        await(losses.lost)
        Thread.sleep(50) // room for a second, wrong, report to arrive

        assertEquals(listOf(CaptureLoss.NotGranted("microphone unavailable")), losses.all)
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
            pollMs = 0,
        )
        okSession.start()
        await(startedLatch)
        okSession.stop()
        assertEquals(1, started.get())

        // Open failure: onStarted must NOT fire (it isn't "capturing").
        val badFake = FakeDevice(openResults = ArrayDeque(listOf(false)))
        val badStarted = AtomicInteger()
        val badLosses = Losses()
        val badSession = PcmCaptureSession(
            device = badFake,
            onFrame = { _, _ -> },
            onStarted = { badStarted.incrementAndGet() },
            onLost = badLosses::add,
            pollMs = 0,
        )
        badSession.start()
        await(badLosses.lost)
        Thread.sleep(50)
        assertEquals("onStarted must not fire when open failed", 0, badStarted.get())
    }

    @Test(timeout = 10_000)
    fun stopDuringOpenFiresNeitherOnStartedNorOnLost() {
        // A stop() that lands while open() is in flight must not start the consumer: onStarted is a callback like
        // any other. On gen2 a pause mid-open let the stopping thread run Vosk's reset + warm-up past the stop
        // join, and the rebuilt session then ran the same recognizer on a second thread — a native abort. Nor is it
        // a loss, whether the open then succeeds or fails: the stop was asked for.
        for (opened in listOf(true, false)) {
            val inOpen = CountDownLatch(1)
            val proceedOpen = CountDownLatch(1)
            val log = StopLog()
            val started = AtomicInteger()
            val losses = Losses()
            val releases = AtomicInteger()
            val device = object : PcmDevice {
                override fun open(): Boolean {
                    inOpen.countDown()
                    proceedOpen.await()
                    return opened
                }
                override fun read(buf: ByteArray, offset: Int, length: Int) = 0
                override fun stop() = Unit
                override fun release() {
                    releases.incrementAndGet()
                }
            }
            val session = PcmCaptureSession(
                device = device,
                onFrame = { _, _ -> },
                onStarted = { started.incrementAndGet() },
                onLost = losses::add,
                log = log,
                pollMs = 0,
            )

            session.start()
            await(inOpen)
            val stopper = Thread { session.stop() }.apply { start() }
            Thread.sleep(50) // stop() has cleared `running` and is in its bounded join
            proceedOpen.countDown() // open() returns after the stop
            stopper.join(5_000)
            Thread.sleep(50) // room for a wrong callback to arrive

            assertEquals("onStarted must not fire for a session stopped mid-open", 0, started.get())
            assertEquals("a stop mid-open is not a loss (opened=$opened)", emptyList<CaptureLoss>(), losses.all)
            if (opened) {
                await(log.stopped)
                assertTrue("the opened device is still released", releases.get() >= 1)
            }
        }
    }

    @Test(timeout = 10_000)
    fun aFailedRebuildIsLostAsDropped() {
        val fake = FakeDevice(openResults = ArrayDeque(listOf(true, false))).apply { failReads = true }
        val losses = Losses()
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ -> },
            onLost = losses::add,
            rebuildAfterReadFailures = 3,
            readErrorBackoffMs = 0,
            pollMs = 0,
        )

        session.start()
        await(losses.lost)
        Thread.sleep(50) // room for a second, wrong, report to arrive

        assertEquals(listOf(CaptureLoss.Dropped("microphone unavailable on rebuild")), losses.all)
        session.stop()
    }

    @Test(timeout = 10_000)
    fun anExceptionEscapingTheReadLoopIsLostAsDropped() {
        val losses = Losses()
        val released = AtomicInteger()
        val device = object : PcmDevice {
            override fun open() = true
            override fun read(buf: ByteArray, offset: Int, length: Int): Int = error("boom")
            override fun stop() = Unit
            override fun release() {
                released.incrementAndGet()
            }
        }
        val session = PcmCaptureSession(device = device, onFrame = { _, _ -> }, onLost = losses::add, pollMs = 0)

        session.start()
        await(losses.lost)
        Thread.sleep(50) // room for a second, wrong, report to arrive

        assertEquals(
            listOf(CaptureLoss.Dropped("capture ended unexpectedly: IllegalStateException: boom")),
            losses.all,
        )
        assertTrue("the mic is still released", released.get() >= 1)
        session.stop()
    }

    @Test(timeout = 10_000)
    fun selfStopKeepsZombieGuardUntilLoopExits() {
        // onFrame (on the capture thread) self-stops, then blocks so the loop is still "alive" mid-unwind.
        val fake = FakeDevice().apply { framesToServe = 1 }
        val inFrame = CountDownLatch(1)
        val releaseFrame = CountDownLatch(1)
        val log = StopLog()
        lateinit var session: PcmCaptureSession
        session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ ->
                session.stop() // self-stop from the capture thread
                inFrame.countDown()
                releaseFrame.await() // hold the capture thread inside the callback (loop hasn't exited yet)
            },
            log = log,
            pollMs = 0,
        )

        assertTrue(session.start())
        await(inFrame)
        // The capture thread is alive (parked in onFrame after self-stop) → a fresh start() must be refused.
        assertFalse("start() must be refused while the self-stopped loop is still alive", session.start())

        releaseFrame.countDown() // let the callback return so the loop reaches its finally
        await(log.stopped)

        // Once the loop has exited, start() succeeds again.
        val restarted = (0 until 100).any {
            session.start().also { started -> if (!started) Thread.sleep(20) }
        }
        assertTrue("start() should succeed after the loop fully exits", restarted)
        session.stop()
    }

    @Test(timeout = 10_000)
    fun concurrentStartStopDoesNotDeadlockOrCrash() {
        val fake = FakeDevice() // idle: read returns 0
        val session = PcmCaptureSession(device = fake, onFrame = { _, _ -> }, pollMs = 0)
        val errors = CopyOnWriteArrayList<Throwable>()

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

    /**
     * Serves full frames of digital silence whose first sample is the next value from [frameSamples] (repeating the
     * last one forever once the list runs out), so a test can script exactly one non-zero sample breaking a silence.
     * [onOpen] runs on every open, so a test can see a rebuild.
     */
    private class SampleDevice(private val frameSamples: List<Short>, private val onOpen: () -> Unit = {}) :
        PcmDevice {
        private var next = 0 // capture-thread only

        override fun open(): Boolean {
            onOpen()
            return true
        }

        override fun read(buf: ByteArray, offset: Int, length: Int): Int {
            val sample = frameSamples[minOf(next, frameSamples.lastIndex)].toInt()
            next++
            buf.fill(0, offset, offset + length)
            buf[offset] = sample.toByte()
            buf[offset + 1] = (sample shr 8).toByte()
            return length
        }

        override fun stop() = Unit

        override fun release() = Unit
    }

    private class SilenceRecorder {
        val frames = AtomicInteger()
        val framesAtSilenced = AtomicInteger(-1)
        val losses = Losses()
        val log = StopLog()

        // Counts the frames delivered before the loss: none arrives after it, as capture ends there.
        private fun lost(loss: CaptureLoss) {
            if (loss is CaptureLoss.Silenced) framesAtSilenced.compareAndSet(-1, frames.get())
            losses.add(loss)
        }

        fun session(device: PcmDevice, silenceWindowMs: Long? = SILENCE_WINDOW_MS, idleRebuildMs: Long? = null) =
            PcmCaptureSession(
                device = device,
                onFrame = { _, _ -> frames.incrementAndGet() },
                onLost = ::lost,
                log = log,
                silenceWindowMs = silenceWindowMs,
                idleRebuildMs = idleRebuildMs,
                pollMs = 0,
            )
    }

    @Test(timeout = 10_000)
    fun aWindowOfDigitalSilenceEndsCaptureAsSilenced() {
        val recorder = SilenceRecorder()
        val session = recorder.session(SampleDevice(listOf(0)))

        session.start()
        await(recorder.losses.lost)

        // 2 s of 16 kHz audio in 100 ms frames is 20 frames: silenced on the 20th, and capture ends there.
        assertEquals(FRAMES_IN_WINDOW, recorder.framesAtSilenced.get())
        assertEquals(FRAMES_IN_WINDOW, recorder.frames.get())
        assertEquals(listOf(CaptureLoss.Silenced("digital silence for 2000ms")), recorder.losses.all)
        session.stop()
    }

    @Test(timeout = 10_000)
    fun oneNonZeroSampleRestartsTheSilenceWindow() {
        val recorder = SilenceRecorder()
        // Frame 20 opens with one non-zero sample, so it leaves 1,599 trailing zeros: the window (32,000 samples) is
        // full only after 20 more all-zero frames (1,599 + 20 × 1,600), on frame 40.
        val session = recorder.session(SampleDevice(List<Short>(FRAMES_IN_WINDOW - 1) { 0 } + 1.toShort() + 0))

        session.start()
        await(recorder.losses.lost)

        assertEquals(40, recorder.framesAtSilenced.get())
        session.stop()
    }

    @Test(timeout = 10_000)
    fun aRebuildRestartsTheSilenceWindow() {
        val recorder = SilenceRecorder()
        val opens = AtomicInteger()
        // The first open serves 10 silent frames and then stalls, so the idle watchdog rebuilds; the rebuilt device
        // serves silence forever. Silenced only after a full window on the rebuilt device: 10 + 20 frames.
        val device = object : PcmDevice {
            private var served = 0 // capture-thread only

            override fun open(): Boolean {
                opens.incrementAndGet()
                served = 0
                return true
            }

            override fun read(buf: ByteArray, offset: Int, length: Int): Int {
                if (opens.get() == 1 && served == FRAMES_IN_WINDOW / 2) return 0
                served++
                buf.fill(0, offset, offset + length)
                return length
            }

            override fun stop() = Unit

            override fun release() = Unit
        }
        val session = recorder.session(device, idleRebuildMs = 50)

        session.start()
        await(recorder.losses.lost)

        assertEquals("the stall rebuilt the device", 2, opens.get())
        assertEquals(30, recorder.framesAtSilenced.get())
        session.stop()
    }

    @Test(timeout = 10_000)
    fun withoutASilenceWindowDigitalSilenceKeepsCapturing() {
        val recorder = SilenceRecorder()
        val session = recorder.session(SampleDevice(listOf(0)), silenceWindowMs = null)

        session.start()
        while (recorder.frames.get() < 2 * FRAMES_IN_WINDOW) Thread.sleep(1)
        session.stop()
        await(recorder.log.stopped)

        assertEquals("never silenced", -1, recorder.framesAtSilenced.get())
        assertEquals("a requested stop is not a loss", emptyList<CaptureLoss>(), recorder.losses.all)
    }

    private companion object {
        const val SILENCE_WINDOW_MS = 2_000L
        const val FRAMES_IN_WINDOW = 20
    }
}
