package com.portal.commons.audio

import com.portal.commons.audio.IdleResetPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the idle-reset deferral rules: reset only in decoder silence, natural endpoints restart the
 * clock, and the force backstop keeps the memory bound unconditional. Times are absolute ms from an
 * epoch of 0 (the policy is clock-free; `flushedAtMs` starts at 0).
 */
class IdleResetPolicyTest {

    private val quiet = { false }
    private val midUtterance = { true }

    private fun policy() = IdleResetPolicy(idleAfterMs = IDLE, forceAfterMs = FORCE)

    // ---- the idle timer ----------------------------------------------------------------------------

    @Test fun keepsBeforeIdleElapses() {
        val p = policy()
        assertEquals(Decision.KEEP, p.decide(0, quiet))
        assertEquals(Decision.KEEP, p.decide(IDLE - 1, quiet))
    }

    @Test fun resetsAtIdleWhenDecoderQuiet() {
        assertEquals(Decision.RESET, policy().decide(IDLE, quiet))
    }

    @Test fun noteFlushedRestartsTheClock() {
        val p = policy()
        p.noteFlushed(10_000) // e.g. a manual reset or a grammar rebuild
        assertEquals(Decision.KEEP, p.decide(10_000 + IDLE - 1, quiet))
        assertEquals(Decision.RESET, p.decide(10_000 + IDLE, quiet))
    }

    // ---- rule #2: natural endpoints keep pushing the reset back --------------------------------------

    @Test fun naturalEndpointsKeepDeferringTheReset() {
        val p = policy()
        var now = 0L
        // A quiet room endpoints every ~5 s; each flush restarts the clock, so the manual reset
        // never becomes due no matter how long capture runs.
        repeat(20) {
            now += 5_000
            p.noteFlushed(now)
            assertEquals(Decision.KEEP, p.decide(now + 5_000, quiet))
        }
        // Endpoints stop (continuous non-endpointing audio) → due IDLE after the last flush.
        assertEquals(Decision.RESET, p.decide(now + IDLE, quiet))
    }

    // ---- rule #1: never reset mid-utterance (until the backstop) -------------------------------------

    @Test fun defersWhileMidUtterance() {
        assertEquals(Decision.KEEP, policy().decide(IDLE, midUtterance))
    }

    @Test fun deferredResetFiresAtTheNextQuietMoment() {
        val p = policy()
        assertEquals(Decision.KEEP, p.decide(IDLE, midUtterance)) // "hey jar…" in flight — hold
        assertEquals(Decision.RESET, p.decide(IDLE + 900, quiet)) // utterance over — reset now
    }

    @Test fun forcesResetPastBackstopEvenMidUtterance() {
        val p = policy()
        assertEquals(Decision.KEEP, p.decide(FORCE - 1, midUtterance))
        assertEquals(Decision.FORCE_RESET, p.decide(FORCE, midUtterance))
    }

    @Test fun quietResetPastBackstopIsAnOrdinaryReset() {
        // FORCE_RESET means "clipped speech is possible" in the log — a quiet late reset isn't that.
        assertEquals(Decision.RESET, policy().decide(FORCE + 1, quiet))
    }

    @Test fun clockRestartsAfterForcedReset() {
        val p = policy()
        assertEquals(Decision.FORCE_RESET, p.decide(FORCE, midUtterance))
        p.noteFlushed(FORCE) // the engine notes the flush after acting on the decision
        assertEquals(Decision.KEEP, p.decide(FORCE + IDLE - 1, midUtterance))
        assertEquals(Decision.KEEP, p.decide(FORCE + IDLE, midUtterance)) // deferred again, not forced
        assertEquals(Decision.FORCE_RESET, p.decide(FORCE + FORCE, midUtterance))
    }

    // ---- the partial query is lazy --------------------------------------------------------------------

    @Test fun doesNotQueryTheDecoderBeforeDue() {
        // isMidUtterance is a native call; the policy must not make it on every frame.
        val p = policy()
        var queries = 0
        val counting = {
            queries++
            false
        }
        p.decide(0, counting)
        p.decide(IDLE - 1, counting)
        assertEquals(0, queries)
        p.decide(IDLE, counting)
        assertEquals(1, queries)
    }

    private companion object {
        const val IDLE = 25_000L
        const val FORCE = 60_000L
    }
}
