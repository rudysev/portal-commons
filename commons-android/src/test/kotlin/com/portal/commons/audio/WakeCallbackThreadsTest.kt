package com.portal.commons.audio

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Runs the default main-thread poster where the unit-test android.jar makes the calling thread look like the main
 * one (both loopers are null), the case where an inline run would happen.
 */
class WakeCallbackThreadsTest {

    @Test fun theDefaultPosterNeverRunsTheCallbackInlineEvenOnTheMainThread() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        var ran = false

        WakeCallbackThreads.mainThreadPoster()(Runnable { ran = true })

        assertFalse(ran)
    }
}
