package com.portal.commons.audio

/**
 * Per wake-word post-fire de-dupe for [WakeMicEngine]. After a phrase fires, further fires *for that same
 * wake id* are suppressed for [cooldownMs] — long enough to cover the ~1 frame between a match and capture
 * actually pausing for the handoff. Each wake id cools down independently, so jarvis and alexa can both fire
 * in the same scoring step when both classifiers clear their thresholds.
 *
 * Pure and clock-free (the caller passes `nowMs`), single-threaded by contract (the engine's capture thread),
 * so it is unit-tested.
 */
class FireCooldown(private val cooldownMs: Long) {

    private val until = HashMap<String, Long>()

    /**
     * May [wakeId] fire at [nowMs]? Returns true and arms the cooldown for a fresh fire; returns false
     * (without re-arming) while [wakeId] is still cooling down from a recent fire.
     */
    fun tryFire(wakeId: String, nowMs: Long): Boolean {
        if (nowMs < (until[wakeId] ?: 0L)) return false
        until[wakeId] = nowMs + cooldownMs
        return true
    }

    /** Clear all cooldowns (on capture (re)start). */
    fun reset() {
        until.clear()
    }
}
