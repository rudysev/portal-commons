package com.portal.commons.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Unit tests for [OwwCaptureGuard] in-flight tracking. Retire/close integration is in [OpenWakeWordDetectorLifecycleTest]. */
class OwwCaptureGuardTest {

    @Test fun inFlightCounterTracksNestedAccept() {
        val modelThread = OwwModelThread()
        val guard = OwwCaptureGuard(modelThread)
        assertEquals(0, guard.inFlightCount())
        guard.enterCapture()
        guard.enterCapture()
        assertEquals(2, guard.inFlightCount())
        guard.exitCapture()
        assertEquals(1, guard.inFlightCount())
        guard.exitCapture()
        modelThread.shutdown(1_000L)
    }

    @Test fun awaitIdleReturnsImmediatelyWhenAlreadyIdle() {
        val modelThread = OwwModelThread()
        val guard = OwwCaptureGuard(modelThread)
        assertTrue(guard.awaitIdle(100))
        modelThread.shutdown(1_000L)
    }

    @Test fun awaitIdleBlocksUntilExitCapture() {
        val modelThread = OwwModelThread()
        val guard = OwwCaptureGuard(modelThread)
        guard.enterCapture()

        val released = AtomicBoolean(false)
        val waiterDone = CountDownLatch(1)
        Thread {
            assertTrue(guard.awaitIdle(2_000))
            released.set(true)
            waiterDone.countDown()
        }.start()

        Thread.sleep(50)
        assertFalse("awaitIdle must wait while in flight", released.get())
        guard.exitCapture()
        assertTrue(waiterDone.await(2, TimeUnit.SECONDS))
        assertTrue(released.get())
        modelThread.shutdown(1_000L)
    }

    @Test fun awaitIdleTimesOutWhileStillInFlight() {
        val modelThread = OwwModelThread()
        val guard = OwwCaptureGuard(modelThread)
        guard.enterCapture()
        assertFalse(guard.awaitIdle(50))
        guard.exitCapture()
        modelThread.shutdown(1_000L)
    }
}
