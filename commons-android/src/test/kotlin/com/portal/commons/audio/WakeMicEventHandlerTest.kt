package com.portal.commons.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeMicEventHandlerTest {

    private var nowMs = 0L
    private val log = mutableListOf<String>()
    private val wakes = mutableListOf<WakeEvent>()
    private val readyDetectorIds = mutableListOf<String>()

    private fun handler() = WakeMicEventHandler(
        handoffCooldownMs = 1_500L,
        wakeConsumer = { wakes.add(it) },
        onDetectorReady = { readyDetectorIds.add(it) },
        onDetectorUnavailable = {},
        clock = { nowMs },
        log = { log.add(it) },
        postToMain = { it.run() },
    )

    @Test fun wakeLogAlwaysIncludesDetectorId() {
        val h = handler()
        h.onWake(WakeEvent("oww", "jarvis", "score=0.95"))
        assertEquals(listOf("wake detected (oww) → jarvis [score=0.95]"), log)
        assertEquals(listOf(WakeEvent("oww", "jarvis", "score=0.95")), wakes)
    }

    @Test fun diagnosticLogAlwaysIncludesDetectorId() {
        val h = handler()
        h.onDiagnostic("oww", "near-miss jarvis score=0.45 threshold=0.50")
        assertEquals(listOf("(oww) near-miss jarvis score=0.45 threshold=0.50"), log)
    }

    @Test fun onReadyForwardsDetectorId() {
        val h = handler()
        h.onReady("oww")
        assertEquals(listOf("wake detector ready (oww)"), log)
        assertEquals(listOf("oww"), readyDetectorIds)
    }

    @Test fun onReadyConsumerRunsViaMainPoster() {
        var consumerRan = false
        val h = WakeMicEventHandler(
            handoffCooldownMs = 1_500L,
            wakeConsumer = {},
            onDetectorReady = { consumerRan = true },
            onDetectorUnavailable = {},
            log = {},
            postToMain = { it.run() },
        )
        h.onReady("oww")
        assertTrue(consumerRan)
    }

    @Test fun secondFireWithinHandoffCooldownIsSuppressed() {
        val h = handler()
        h.onWake(WakeEvent("oww", "jarvis", "a"))
        nowMs = 500L
        h.onWake(WakeEvent("oww", "jarvis", "b"))
        assertEquals(1, wakes.size)
    }

    @Test fun isCoolingDownTrueUntilWindowElapses() {
        val h = handler()
        h.onWake(WakeEvent("oww", "jarvis", "score"))
        nowMs = 500L
        assertTrue(h.isCoolingDown("jarvis"))
        assertFalse(h.isCoolingDown("alexa"))
        nowMs = 1_500L
        assertFalse(h.isCoolingDown("jarvis"))
    }

    @Test fun isAnyCoolingDownTrueAfterAnyFire() {
        val h = handler()
        assertFalse(h.isAnyCoolingDown())
        h.onWake(WakeEvent("oww", "jarvis", "a"))
        nowMs = 500L
        assertTrue(h.isAnyCoolingDown())
        h.reset()
        assertFalse(h.isAnyCoolingDown())
    }

    @Test fun resetClearsHandoffCooldown() {
        val h = handler()
        h.onWake(WakeEvent("oww", "jarvis", "a"))
        nowMs = 500L
        assertTrue(h.isAnyCoolingDown())
        h.reset()
        assertFalse(h.isAnyCoolingDown())
        h.onWake(WakeEvent("oww", "jarvis", "again"))
        assertEquals(2, wakes.size)
    }

    @Test fun wakeIdsCoolDownIndependently() {
        val h = handler()
        h.onWake(WakeEvent("oww", "jarvis", "a"))
        nowMs = 500L
        h.onWake(WakeEvent("oww", "alexa", "b"))
        assertEquals(2, wakes.size)
    }
}
