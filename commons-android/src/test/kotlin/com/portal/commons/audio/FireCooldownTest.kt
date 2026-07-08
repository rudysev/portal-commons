package com.portal.commons.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FireCooldownTest {

    @Test fun firstFireAlwaysAllowed() {
        val c = FireCooldown(1_500L)
        assertTrue(c.tryFire("oww", nowMs = 0L))
    }

    @Test fun secondFireWithinCooldownSuppressed() {
        val c = FireCooldown(1_500L)
        assertTrue(c.tryFire("oww", 0L))
        assertFalse("re-fire 200ms later is within cooldown", c.tryFire("oww", 200L))
        assertFalse("still within cooldown at 1499ms", c.tryFire("oww", 1_499L))
    }

    @Test fun fireAllowedAgainAfterCooldownElapses() {
        val c = FireCooldown(1_500L)
        assertTrue(c.tryFire("oww", 0L))
        assertTrue("cooldown elapsed at exactly 1500ms", c.tryFire("oww", 1_500L))
    }

    @Test fun suppressedFireDoesNotReArmCooldown() {
        val c = FireCooldown(1_500L)
        assertTrue(c.tryFire("oww", 0L)) // armed until 1500
        assertFalse(c.tryFire("oww", 1_000L)) // suppressed — must NOT push the window to 2500
        assertTrue("original window ends at 1500, not extended by the suppressed attempt", c.tryFire("oww", 1_500L))
    }

    @Test fun detectorsCoolDownIndependently() {
        val c = FireCooldown(1_500L)
        assertTrue(c.tryFire("oww", 0L))
        assertTrue("a different detector is not blocked by oww's cooldown", c.tryFire("alexa", 0L))
        assertFalse(c.tryFire("oww", 500L))
        assertFalse(c.tryFire("alexa", 500L))
    }

    @Test fun resetClearsAllCooldowns() {
        val c = FireCooldown(1_500L)
        assertTrue(c.tryFire("oww", 0L))
        assertFalse(c.tryFire("oww", 500L))
        c.reset()
        assertTrue("after reset, a fire is allowed immediately", c.tryFire("oww", 600L))
    }
}
