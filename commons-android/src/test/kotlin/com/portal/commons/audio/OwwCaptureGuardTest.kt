package com.portal.commons.audio

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
