package com.portal.commons.audio

import com.portal.commons.DebugLog

/**
 * Configuration for [WakeMicEngine]: the [wakeWords] its one wake detector listens for, on the [model] it loads.
 *
 * **Threading contract** (consumer callbacks):
 * - [onWake] — **capture thread** (the engine's `wake-capture` thread). Invoked synchronously on a match so
 *   the consumer can pause capture with minimum latency (~one frame) before handoff. Post to your service's
 *   main/arbiter thread before touching UI or other capture state.
 * - [onReady], [onUnavailable], [onCaptureLost] — **main thread**. The engine always posts these to the main looper
 *   and never runs them inline, even when the event happens on the main thread, so none runs inside a call you are
 *   making to the engine (its constructor included). Consumers can update service/UI state directly. None is
 *   delivered after [WakeMicEngine.close], including ones already posted.
 * - [beforeMicAcquire] — **caller thread** of [WakeMicEngine.start], synchronously before the mic is opened.
 */
data class WakeMicConfig(
    val wakeWords: List<WakeWord>,
    val model: WakeModel,
    val wakeHandoffCooldownMs: Long = DEFAULT_WAKE_HANDOFF_COOLDOWN_MS,
    val onWake: (WakeEvent) -> Unit,
    /** The wake model has loaded and the first Decoder is built: wakes can arise from now on. */
    val onReady: () -> Unit = {},
    /**
     * The wake model failed to load, or its first Decoder couldn't be built. The engine never wakes after it.
     */
    val onUnavailable: () -> Unit = {},
    /**
     * A [CaptureLoss] (see **Capture loss** in `GLOSSARY.md`). The engine is then idle, as after a pause, and never
     * retries on its own: retry by calling [WakeMicEngine.start].
     */
    val onCaptureLost: (CaptureLoss) -> Unit = {},
    val beforeMicAcquire: () -> Unit = {},
    val log: (String) -> Unit = DebugLog::log,
) {
    companion object {
        /**
         * The handoff cooldown: for this long after a wake is delivered the wake detector is fed no frames, so no
         * wake of any wake id arises, covering the gap until capture pauses for the handoff.
         */
        const val DEFAULT_WAKE_HANDOFF_COOLDOWN_MS = 1_500L
    }
}
