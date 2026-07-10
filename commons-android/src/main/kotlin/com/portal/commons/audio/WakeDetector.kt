package com.portal.commons.audio

import android.content.Context

/**
 * The **engine-agnostic wake-detection boundary**: everything downstream of "here is a 16 kHz mono 16-bit
 * PCM frame" that decides whether a wake phrase was spoken. [WakeMicEngine] owns the mic, the capture
 * thread, and the frame assembly; a [WakeDetector] owns *recognition* — model load, streaming inference,
 * and turning frames into wake events.
 *
 * [WakeMicEngine] fans each captured frame to every configured detector so implementations can run in
 * parallel when benchmarking. Production apps typically configure one detector via [WakeDetectors].
 *
 * **Threading contract.** [start], [accept], and [updateWakeWords] are all called on the engine's single
 * **capture thread**, at frame boundaries — so an implementation may keep capture-thread-only mutable state
 * without locks, and must never be closed mid-[accept] (the engine defers a wake-set swap to a frame
 * boundary for exactly this reason). [close] is called from the controlling thread after capture has stopped.
 * Readiness is asynchronous: a detector signals [Events.onReady] / [Events.onUnavailable] from the
 * model-load thread; [WakeMicEngine] forwards those to the consumer on the main thread (see [WakeMicConfig]).
 */
interface WakeDetector {

    /** Stable short id for logs, e.g. `"oww"`. */
    val id: String

    /**
     * (Re)entering capture: reset internal recognition state so the first frame after a fresh start /
     * handoff-reclaim / device rebuild isn't garbled. Called on the capture thread from [WakeMicEngine]'s
     * `onStarted`.
     */
    fun start()

    /**
     * Feed one frame of 16 kHz mono 16-bit PCM ([n] bytes of [buf]). Report a match via [Host.events].
     * Called on the capture thread. An implementation that isn't ready yet should buffer or drop internally
     * — the engine always delivers every frame.
     */
    fun accept(buf: ByteArray, n: Int)

    /**
     * Hot-swap the active wake set without tearing down capture. Called on the capture thread at a frame
     * boundary. Detectors that cannot rebuild the active phrase set in place may no-op.
     */
    fun updateWakeWords(words: List<WakeWord>)

    /** Full teardown: release the model/native resources. Called from the controlling thread after stop. */
    fun close()

    /** Dependencies supplied when the engine builds a detector. */
    interface Host {
        val context: Context
        val wakeWords: List<WakeWord>
        val events: Events
        val handoffCooldown: WakeHandoffCooldown
    }

    /**
     * How a detector reports back to [WakeMicEngine]. Callbacks carry the detector [WakeEvent.detectorId]; the
     * engine prefixes log lines with it. [onWake] is invoked on the **capture thread**; [onReady] and
     * [onUnavailable] on the **model-load thread** — [WakeMicEngine] re-delivers those two to the consumer on
     * the main thread (see [WakeMicConfig]).
     */
    interface Events {
        /** The detector's model finished loading and it can accept audio. Called on the model-load thread. */
        fun onReady(detectorId: String)

        /** The detector's model is missing/unusable — it will never fire. Called on the model-load thread. */
        fun onUnavailable(detectorId: String)

        /**
         * A genuine wake fired: hand off to [WakeEvent.wakeId]. [WakeEvent.transcript] is a human-readable
         * decode for the log. Called on the **capture thread**.
         */
        fun onWake(event: WakeEvent)

        /**
         * A tuning-relevant non-fire. The engine logs it prefixed with the detector [detectorId] so parallel
         * detectors are distinguishable in `debug.txt`.
         */
        fun onDiagnostic(detectorId: String, message: String)
    }

    /** Builds a [WakeDetector] once the engine has wired up its [Host]. */
    fun interface Factory {
        fun create(host: Host): WakeDetector
    }
}
