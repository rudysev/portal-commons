package com.portal.commons.audio

/**
 * Per-detector post-fire de-dupe for [WakeMicEngine]. After a detector fires, further fires *from that same
 * detector* are suppressed for [cooldownMs] — long enough to cover the ~1 frame between a match and capture
 * actually pausing for the handoff, without one detector's fire silencing another (each name cools down
 * independently, which matters when detectors run in parallel).
 *
 * Pure and clock-free (the caller passes `nowMs`), single-threaded by contract (the engine's capture thread),
 * so it is unit-tested.
 */
class FireCooldown(private val cooldownMs: Long) {

    private val until = HashMap<String, Long>()

    /**
     * May [name] fire at [nowMs]? Returns true and arms the cooldown for a fresh fire; returns false (without
     * re-arming) while [name] is still cooling down from a recent fire.
     */
    fun tryFire(name: String, nowMs: Long): Boolean {
        if (nowMs < (until[name] ?: 0L)) return false
        until[name] = nowMs + cooldownMs
        return true
    }

    /** Clear all cooldowns (on capture (re)start). */
    fun reset() {
        until.clear()
    }
}
