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

    // The cascade is primary for the same reason oWW is: it only fires for ids it holds a model for.
    @Test fun twoStageAlwaysRoutes() {
        assertTrue(WakeRouting.shouldRoute(TwoStageWakeDetector.ID, "jarvis", owned))
        assertTrue(WakeRouting.shouldRoute(TwoStageWakeDetector.ID, "custom", owned))
        assertTrue(WakeRouting.shouldRoute(TwoStageWakeDetector.ID, "jarvis", emptySet()))
    }

    @Test fun voskStillShadowsIdsOwnedByTheCascade() {
        // The partition is by phrase, so a phrase the cascade covers must not also hand off from Vosk.
        assertFalse(WakeRouting.shouldRoute(VoskWakeDetector.ID, "jarvis", owned))
        assertTrue(WakeRouting.shouldRoute(VoskWakeDetector.ID, "custom", owned))
    }
}
