package com.portal.commons.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeDebounceTest {

    @Test fun `fires only after framesToFire consecutive above-threshold frames`() {
        val d = WakeDebounce(framesToFire = 2)
        assertFalse("first above-threshold frame must not fire", d.onAboveThreshold("jarvis"))
        assertTrue("second consecutive frame fires", d.onAboveThreshold("jarvis"))
    }

    @Test fun `a below-threshold frame breaks the run`() {
        val d = WakeDebounce(framesToFire = 2)
        assertFalse(d.onAboveThreshold("jarvis"))
        d.onBelowThreshold("jarvis")            // run broken
        assertFalse("run restarted — one frame is not enough", d.onAboveThreshold("jarvis"))
        assertTrue(d.onAboveThreshold("jarvis"))
    }

    @Test fun `after firing, the next fire needs a fresh full run`() {
        val d = WakeDebounce(framesToFire = 2)
        d.onAboveThreshold("jarvis")
        assertTrue(d.onAboveThreshold("jarvis")) // fired
        assertFalse("counter reset on fire", d.onAboveThreshold("jarvis"))
        assertTrue(d.onAboveThreshold("jarvis"))
    }

    @Test fun `runs are tracked independently per wake id`() {
        val d = WakeDebounce(framesToFire = 2)
        assertFalse(d.onAboveThreshold("jarvis"))
        assertFalse("alexa has its own run", d.onAboveThreshold("alexa"))
        assertTrue("jarvis completes its run", d.onAboveThreshold("jarvis"))
        assertTrue("alexa completes its run", d.onAboveThreshold("alexa"))
    }

    @Test fun `framesToFire of 1 fires on the first frame`() {
        val d = WakeDebounce(framesToFire = 1)
        assertTrue(d.onAboveThreshold("jarvis"))
        assertTrue(d.onAboveThreshold("jarvis"))
    }

    @Test fun `reset clears an in-progress run`() {
        val d = WakeDebounce(framesToFire = 2)
        assertFalse(d.onAboveThreshold("jarvis"))
        d.reset()
        assertFalse("after reset, one frame is not enough to fire", d.onAboveThreshold("jarvis"))
        assertTrue(d.onAboveThreshold("jarvis"))
    }

    @Test fun `default framesToFire matches OwwTuning`() {
        // The production debounce must use the tuned value; guard against drift.
        val d = WakeDebounce()
        var fired = false
        repeat(OwwTuning.DEBOUNCE_FRAMES) { fired = d.onAboveThreshold("jarvis") }
        assertTrue("fires exactly at OwwTuning.DEBOUNCE_FRAMES consecutive frames", fired)
    }
}
