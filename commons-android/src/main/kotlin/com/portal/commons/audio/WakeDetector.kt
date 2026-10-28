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
 * boundary for exactly this reason). [close] is called at most once, never during another detector call: from the
 * thread calling [WakeMicEngine.close], or, when a detector call is still running (a stuck native call that outlasts
 * the bounded close, or a close from inside the wake callback), by the capture thread as it leaves that call.
 * The engine never runs two of these calls at once, even when it rebuilds a wedged capture session while the
 * old capture thread is still inside a detector call (two threads in one Vosk recognizer abort the process).
 * A wake exists only as the return value of [accept], so it arises on the capture thread inside a frame the engine
 * fed: never from [start], [updateWakeWords], [close] or a thread of its own.
 * Readiness is asynchronous and may be frame-driven: the Vosk detector signals [Events.onReady] (and
 * [Events.onUnavailable] for a failed first decoder build) on the capture thread at the first frame after its
 * model loads — so not while capture is paused — and [Events.onUnavailable] for a failed model load straight
 * from its model-load thread. [WakeMicEngine] forwards both to the consumer on the main thread (see
 * [WakeMicConfig]).
 */
interface WakeDetector {

    /** Stable short id for logs, e.g. `"vosk"`. */
    val id: String

    /**
     * (Re)entering capture: reset internal recognition state so the first frame after a fresh start /
     * handoff-reclaim / device rebuild isn't garbled. Called on the capture thread from [WakeMicEngine]'s
     * `onStarted`.
     */
    fun start()

    /**
     * Feed one frame of 16 kHz mono 16-bit PCM ([n] bytes of [buf]) and return the wake it completed, or null for
     * none. Called on the capture thread. At most one wake per call: a detector that decodes several buffered frames
     * in one call stops at its first wake and decodes nothing after it within that call. An implementation that isn't
     * ready yet should buffer or drop internally; the engine may skip frames, so a detector must not assume it hears
     * every one.
     */
    fun accept(buf: ByteArray, n: Int): WakeEvent?

    /**
     * Hot-swap the active wake set without tearing down capture. Called on the capture thread at a frame
     * boundary. Detectors that cannot rebuild grammar in place may no-op.
     */
    fun updateWakeWords(words: List<WakeWord>)

    /**
     * Full teardown: release the model/native resources. Called once, never during another detector call: from the
     * thread closing the engine, or by the capture thread as it leaves a call the engine's bounded close outlasted.
     */
    fun close()

    /** Dependencies supplied when the engine builds a detector. */
    interface Host {
        val context: Context
        val wakeWords: List<WakeWord>
        val events: Events
    }

    /**
     * How a detector reports back to [WakeMicEngine], besides the wakes [accept] returns. Callbacks carry the detector
     * id; the engine prefixes log lines with it. [onReady] and [onUnavailable] are invoked on the capture thread or a
     * detector's own model-load thread — [WakeMicEngine] re-delivers those two to the consumer on the main thread
     * (see [WakeMicConfig]).
     */
    interface Events {
        /**
         * The detector can accept audio. May be frame-driven: Vosk calls it on the **capture thread** at the
         * first frame after its model loads, once the first decoder is built and warmed.
         */
        fun onReady(detectorId: String)

        /**
         * The detector's model is missing/unusable — it will never fire. Vosk calls it on its **model-load
         * thread** when the load fails, or on the **capture thread** (frame-driven) when the first decoder
         * build fails.
         */
        fun onUnavailable(detectorId: String)

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
