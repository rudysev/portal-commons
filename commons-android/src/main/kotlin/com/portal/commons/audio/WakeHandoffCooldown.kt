package com.portal.commons.audio

/**
 * Engine-owned post-fire cooldown state, supplied to [WakeDetector] implementations via [WakeDetector.Host].
 * App consumers configure the handoff window on [WakeMicConfig.wakeHandoffCooldownMs] instead.
 */
interface WakeHandoffCooldown {
    /** True while [wakeId] is inside the engine's post-fire handoff cooldown for that phrase. */
    fun isCoolingDown(wakeId: String): Boolean

    /**
     * True while any wake id is inside the engine's post-fire handoff cooldown. Detectors use this
     * to gate all inference during handoff (matching the pre-seam global cooldown behavior).
     */
    fun isAnyCoolingDown(): Boolean
}
