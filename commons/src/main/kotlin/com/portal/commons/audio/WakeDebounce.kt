package com.portal.commons.audio

/**
 * Per-wake consecutive-frame debounce: a wake only fires once its score has cleared the threshold on
 * [framesToFire] **consecutive** frames, which kills single-frame score spikes (see [OwwTuning.DEBOUNCE_FRAMES]).
 *
 * Pure bookkeeping, deliberately split out of the ONNX scorer so the fire policy is unit-testable without a
 * model. The per-wake threshold comparison stays with the caller (thresholds are per wake word); this tracks
 * only the consecutive-run counters. Single-threaded — drive it from the one capture thread, like the scorer.
 */
class WakeDebounce(private val framesToFire: Int = OwwTuning.DEBOUNCE_FRAMES) {

    private val consecutive = HashMap<String, Int>()

    /**
     * Record that [id] cleared its threshold on this frame. Returns true when that completes a run of
     * [framesToFire] consecutive above-threshold frames (and resets the run so the next fire needs a fresh run).
     */
    fun onAboveThreshold(id: String): Boolean {
        val c = (consecutive[id] ?: 0) + 1
        if (c >= framesToFire) {
            consecutive[id] = 0
            return true
        }
        consecutive[id] = c
        return false
    }

    /** Record that [id] was below its threshold on this frame — breaks any run in progress. */
    fun onBelowThreshold(id: String) {
        consecutive[id] = 0
    }

    /** Clear every wake's run (on each (re)start, so a pre-pause partial run can't carry over). */
    fun reset() = consecutive.clear()
}
