package com.portal.commons.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeRoutingTest {

    private val owned = setOf("jarvis", "alexa")

    @Test fun owwAlwaysRoutes() {
        assertTrue(WakeRouting.shouldRoute(OpenWakeWordDetector.ID, "jarvis", owned))
        assertTrue(WakeRouting.shouldRoute(OpenWakeWordDetector.ID, "alexa", owned))
        assertTrue(WakeRouting.shouldRoute(OpenWakeWordDetector.ID, "custom", owned))
        assertTrue(WakeRouting.shouldRoute(OpenWakeWordDetector.ID, "jarvis", emptySet()))
    }

    @Test fun voskSuppressesOwnedIds() {
        assertFalse(WakeRouting.shouldRoute(VoskWakeDetector.ID, "jarvis", owned))
        assertFalse(WakeRouting.shouldRoute(VoskWakeDetector.ID, "alexa", owned))
    }

    @Test fun voskRoutesNonOwnedIds() {
        assertTrue(WakeRouting.shouldRoute(VoskWakeDetector.ID, "custom", owned))
        assertTrue(WakeRouting.shouldRoute(VoskWakeDetector.ID, "computer", owned))
    }

    @Test fun voskRoutesEverythingWhenOwwOwnsNothing() {
        assertTrue(WakeRouting.shouldRoute(VoskWakeDetector.ID, "jarvis", emptySet()))
        assertTrue(WakeRouting.shouldRoute(VoskWakeDetector.ID, "alexa", emptySet()))
    }

    @Test fun unknownDetectorNeverRoutes() {
        assertFalse(WakeRouting.shouldRoute("shadow", "jarvis", owned))
    }
}
