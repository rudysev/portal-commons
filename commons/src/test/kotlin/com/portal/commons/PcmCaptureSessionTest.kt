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
 *  - [FakeDevice] is **non-blocking** (returns `0` when idle, like the real `READ_NON_BLOCKING`).
 *  - [BlockingFake] deliberately parks in `read` on a latch to stand in for the residual *hung native call*
 *    case (a non-blocking read never parks in production), exercising the bounded join + force-release and the
 *    post-stop "no frame after stop" gate.
 * Each test has a hard timeout so a regression (e.g. `stop()` hanging) fails the suite instead of blocking it.
 * The frame rules themselves (assembly, rebuilds, the silence window) are [CaptureLoop]'s, tested in [CaptureLoopTest].
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

    /** Non-blocking fake: serves [framesToServe] full frames, then returns `0` (idle). Never parks. */
    private class FakeDevice(private val openResults: ArrayDeque<Boolean> = ArrayDeque()) : PcmDevice {
        val openCount = AtomicInteger()
        val stopCount = AtomicInteger()
        val releaseCount = AtomicInteger()
        val readCount = AtomicInteger()

        @Volatile var framesToServe = 0

        override fun open(): Boolean {
            openCount.incrementAndGet()
            return if (openResults.isEmpty()) true else openResults.removeFirst()
        }

        override fun read(buf: ByteArray, offset: Int, length: Int): Int {
            readCount.incrementAndGet()
            if (framesToServe <= 0) return 0 // idle: no data right now (NOT an error)
            framesToServe--
            return length
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
        )

        session.start()
        await(gotFrames)
        session.stop()
        await(log.stopped)

        assertEquals(3, frames.get())
        assertEquals("a requested stop is not a loss", emptyList<CaptureLoss>(), losses.all)
        assertEquals(1, fake.openCount.get())
        assertTrue(fake.releaseCount.get() >= 1)
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
        // releases the device as a last resort so the mic slot can't stay held.
        val fake = BlockingFake()
        val logs = CopyOnWriteArrayList<String>()
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ -> },
            log = { logs.add(it) },
            stopTimeoutMs = 50,
        )

        session.start()
        await(fake.parked)
        val releasesBefore = fake.releaseCount.get()
        session.stop() // join times out → force-release
        assertTrue("expected a stop-timeout log", logs.any { it.contains("pcm stop timeout") })
        assertTrue("expected a force-release during stop", fake.releaseCount.get() > releasesBefore)

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
        val session = PcmCaptureSession(device = fake, onFrame = { _, _ -> }, stopTimeoutMs = 200)

        session.start()
        await(capture.parked)
        val startNs = System.nanoTime()
        session.stop()
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
    fun stopDuringInFlightRebuildExitsCleanly() {
        // stop() landing while a rebuild's open() is in flight must exit cleanly: when open() returns, the
        // post-rebuild `continue` hits the top-of-loop running gate and breaks — no frame delivered, no
        // force-release timeout.
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
            policy = CapturePolicy(rebuildAfterReadFailures = 3),
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
    }

    @Test(timeout = 10_000)
    fun aSecondStartAndAStartAfterStopAreLoggedNoOps() {
        val fake = FakeDevice()
        val started = CountDownLatch(1)
        val logs = CopyOnWriteArrayList<String>()
        val log = StopLog {
            logs.add(it)
            if (it == "pcm capture started") started.countDown()
        }
        val session = PcmCaptureSession(device = fake, onFrame = { _, _ -> }, log = log)

        session.start()
        await(started)
        session.start() // already started → no second thread
        session.stop()
        await(log.stopped)
        session.start() // a session is single-use → no new capture
        Thread.sleep(50) // room for a wrong second capture to open the device

        assertEquals(1, fake.openCount.get())
        assertEquals(2, logs.count { it == "pcm start ignored — a capture session is single-use" })
    }

    @Test(timeout = 10_000)
    fun openFailureIsLostAsNotGrantedAndDoesNotRun() {
        val fake = FakeDevice(openResults = ArrayDeque(listOf(false)))
        val losses = Losses()
        val session = PcmCaptureSession(
            device = fake,
            onFrame = { _, _ -> },
            onLost = losses::add,
        )

        session.start() // thread spawns; open() fails inside the loop
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
        // join, and the fresh session then ran the same Decoder on a second thread — a native abort. Nor is it
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
        val session = PcmCaptureSession(device = device, onFrame = { _, _ -> }, onLost = losses::add)

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
    fun concurrentStartStopDoesNotDeadlockOrCrash() {
        val fake = FakeDevice() // idle: read returns 0
        val session = PcmCaptureSession(device = fake, onFrame = { _, _ -> })
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

    @Test(timeout = 10_000)
    fun aStopBetweenADeliveredFrameAndItsFollowUpCarriesOutNeither() {
        // The frame that fills the silence window is followed by a rebuild (rebuild on silence) or by the silenced
        // loss. A stop that lands while that frame is being delivered carries out neither: no reopen, no loss.
        for (rebuildFirst in listOf(true, false)) {
            val device = SilentDevice()
            val log = StopLog()
            val losses = Losses()
            lateinit var session: PcmCaptureSession
            session = PcmCaptureSession(
                device = device,
                onFrame = { _, _ -> session.stop() }, // a self-stop: it signals and returns
                onLost = losses::add,
                log = log,
                policy = CapturePolicy(
                    // The first frame fills the window.
                    silence = CapturePolicy.Silence(PcmCaptureFormat.FRAME_MS.toLong(), rebuildFirst),
                ),
            )

            session.start()
            await(log.stopped)

            assertEquals("no rebuild (rebuildFirst=$rebuildFirst)", 1, device.openCount.get())
            assertEquals("no loss (rebuildFirst=$rebuildFirst)", emptyList<CaptureLoss>(), losses.all)
        }
    }

    /** Serves full frames of digital silence for as long as it is read. */
    private class SilentDevice : PcmDevice {
        val openCount = AtomicInteger()

        override fun open(): Boolean {
            openCount.incrementAndGet()
            return true
        }

        override fun read(buf: ByteArray, offset: Int, length: Int): Int {
            buf.fill(0, offset, offset + length)
            return length
        }

        override fun stop() = Unit

        override fun release() = Unit
    }
}
