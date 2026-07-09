package com.portal.commons.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FireCooldownTest {

    @Test fun firstFireAlwaysAllowed() {
        val c = FireCooldown(1_500L)
        assertTrue(c.tryFire("jarvis", nowMs = 0L))
    }

    @Test fun secondFireWithinCooldownSuppressed() {
        val c = FireCooldown(1_500L)
        assertTrue(c.tryFire("jarvis", 0L))
        assertFalse("re-fire 200ms later is within cooldown", c.tryFire("jarvis", 200L))
        assertFalse("still within cooldown at 1499ms", c.tryFire("jarvis", 1_499L))
    }

    @Test fun fireAllowedAgainAfterCooldownElapses() {
        val c = FireCooldown(1_500L)
        assertTrue(c.tryFire("jarvis", 0L))
        assertTrue("cooldown elapsed at exactly 1500ms", c.tryFire("jarvis", 1_500L))
    }

    @Test fun suppressedFireDoesNotReArmCooldown() {
        val c = FireCooldown(1_500L)
        assertTrue(c.tryFire("jarvis", 0L))
        assertFalse(c.tryFire("jarvis", 1_000L))
        assertTrue("original window ends at 1500, not extended by the suppressed attempt", c.tryFire("jarvis", 1_500L))
    }

    @Test fun wakeWordsCoolDownIndependently() {
        val c = FireCooldown(1_500L)
        assertTrue(c.tryFire("jarvis", 0L))
        assertTrue("alexa is not blocked by jarvis's cooldown", c.tryFire("alexa", 0L))
        assertFalse(c.tryFire("jarvis", 500L))
        assertFalse(c.tryFire("alexa", 500L))
    }

    @Test fun resetClearsAllCooldowns() {
        val c = FireCooldown(1_500L)
        assertTrue(c.tryFire("jarvis", 0L))
        assertFalse(c.tryFire("jarvis", 500L))
        c.reset()
        assertTrue("after reset, a fire is allowed immediately", c.tryFire("jarvis", 600L))
    }
}
