package com.portal.commons.audio

/**
 * Per wake-word post-fire de-dupe for [WakeMicEngine]. After a phrase fires, further fires *for that same
 * wake id* are suppressed for [handoffCooldownMs] — long enough to cover the ~1 frame between a match and
 * capture actually pausing for the handoff. Each wake id cools down independently.
 *
 * Pure and clock-free (the caller passes `nowMs`), single-threaded by contract (the engine's capture thread),
 * so it is unit-tested. [WakeMicEventHandler] exposes this as [WakeHandoffCooldown] for detectors.
 */
class FireCooldown(private val handoffCooldownMs: Long) {

    private val until = HashMap<String, Long>()

    /**
     * May [wakeId] fire at [nowMs]? Returns true and arms the cooldown for a fresh fire; returns false
     * (without re-arming) while [wakeId] is still cooling down from a recent fire.
     */
    fun tryFire(wakeId: String, nowMs: Long): Boolean {
        if (isCoolingDown(wakeId, nowMs)) return false
        until[wakeId] = nowMs + handoffCooldownMs
        return true
    }

    /** True while [wakeId] is still inside a post-fire cooldown window armed by [tryFire]. */
    fun isCoolingDown(wakeId: String, nowMs: Long): Boolean = nowMs < (until[wakeId] ?: 0L)

    /** True while any wake id is still inside a post-fire cooldown (used by [VoskWakeDetector] to gate all inference). */
    fun isAnyCoolingDown(nowMs: Long): Boolean = until.values.any { nowMs < it }

    /** Clear all cooldowns (on capture (re)start). */
    fun reset() {
        until.clear()
    }
}
