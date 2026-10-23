package com.portal.commons.audio

import com.portal.commons.DebugLog

/**
 * Configuration for [WakeMicEngine].
 *
 * **Threading contract** (consumer callbacks):
 * - [onWake] — **capture thread** (the engine's `wake-capture` thread). Invoked synchronously on a match so
 *   the consumer can pause capture with minimum latency (~one frame) before handoff. Post to your service's
 *   main/arbiter thread before touching UI or other capture state.
 * - [onDetectorReady], [onDetectorUnavailable], [onCaptureLost] — **main thread**. The engine always posts
 *   these to the main looper and never runs them inline, even when the event happens on the main thread, so none
 *   runs inside a call you are making to the engine (its constructor included). Consumers can update service/UI state
 *   directly. None is delivered after [WakeMicEngine.close], including ones already posted.
 * - [beforeMicAcquire] — **caller thread** of [WakeMicEngine.start], synchronously before the mic is opened.
 */
data class WakeMicConfig(
    val wakeWords: List<WakeWord>,
    val detectors: List<WakeDetector.Factory>,
    val wakeHandoffCooldownMs: Long = DEFAULT_WAKE_HANDOFF_COOLDOWN_MS,
    val onWake: (WakeEvent) -> Unit,
    val onDetectorReady: (detectorId: String) -> Unit = {},
    val onDetectorUnavailable: (detectorId: String) -> Unit = {},
    /**
     * A [CaptureLoss]: capture stopped running while it was wanted (from [WakeMicEngine.start] until the next
     * [WakeMicEngine.pause] or [WakeMicEngine.close]), for a reason the consumer did not ask for. At most once per
     * start; the engine is then idle, as after a pause, and never retries on its own: retry by calling start again.
     */
    val onCaptureLost: (CaptureLoss) -> Unit = {},
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
