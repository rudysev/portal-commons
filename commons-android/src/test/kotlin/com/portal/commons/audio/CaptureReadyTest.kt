package com.portal.commons.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM test for the capture-open success predicate (the one production behavior change in the shared
 * mic device). Uses literal AudioRecord state values so it needs no Android runtime:
 * STATE_UNINITIALIZED=0, STATE_INITIALIZED=1, RECORDSTATE_STOPPED=1, RECORDSTATE_RECORDING=3.
 */
class CaptureReadyTest {

    @Test fun readyOnlyWhenInitializedAndRecording() {
        assertTrue("initialized + recording", captureReady(recordState = 1, recordingState = 3))
    }

    @Test fun notReadyWhenUninitialized() {
        assertFalse("uninitialized, even if 'recording'", captureReady(recordState = 0, recordingState = 3))
    }

    @Test fun notReadyWhenRecordingDidNotStart() {
        // The key guard: AudioRecord initialized but startRecording() didn't take (slot busy / threw).
        assertFalse("initialized but stopped", captureReady(recordState = 1, recordingState = 1))
    }

    @Test fun notReadyWhenNeitherHolds() {
        assertFalse(captureReady(recordState = 0, recordingState = 1))
    }
}
