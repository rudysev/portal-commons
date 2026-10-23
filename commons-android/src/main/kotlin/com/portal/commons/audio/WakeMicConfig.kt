package com.portal.commons.audio

import com.portal.commons.DebugLog

/**
 * Configuration for [WakeMicEngine].
 *
 * **Threading contract** (consumer callbacks):
 * - [onWake] — **capture thread** (the engine's `wake-capture` thread). Invoked synchronously on a match so
 *   the consumer can pause capture with minimum latency (~one frame) before handoff. Post to your service's
 *   main/arbiter thread before touching UI or other capture state.
 * - [onDetectorReady], [onDetectorUnavailable], [onError], [onStopped] — **main thread**. The engine
 *   marshals these from the capture thread (or a detector's model-load thread) so consumers can update
 *   service/UI state directly.
 * - [beforeMicAcquire] — **caller thread** of [WakeMicEngine.start], synchronously before the mic is opened.
 */
data class WakeMicConfig(
    val wakeWords: List<WakeWord>,
    val detectors: List<WakeDetector.Factory>,
    val wakeHandoffCooldownMs: Long = DEFAULT_WAKE_HANDOFF_COOLDOWN_MS,
    val onWake: (WakeEvent) -> Unit,
    val onDetectorReady: (detectorId: String) -> Unit = {},
    val onDetectorUnavailable: (detectorId: String) -> Unit = {},
    val onError: (String) -> Unit = {},
    val onStopped: () -> Unit = {},
    val beforeMicAcquire: () -> Unit = {},
    val log: (String) -> Unit = DebugLog::log,
) {
    companion object {
        /**
         * The handoff cooldown: for this long after a wake is delivered no detector is fed frames and every
         * further wake, whatever its wake id, is dropped, covering the gap until capture pauses for the handoff.
         */
        const val DEFAULT_WAKE_HANDOFF_COOLDOWN_MS = 1_500L
    }
}
