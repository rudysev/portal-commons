package com.portal.commons.audio

import android.content.Context

/**
 * The **engine-agnostic wake-detection boundary**: everything downstream of "here is a 16 kHz mono 16-bit
 * PCM frame" that decides whether a wake phrase was spoken. [WakeMicEngine] owns the mic, the capture
 * thread, and the frame assembly; a [WakeDetector] owns *recognition* — model load, streaming inference,
 * and turning frames into wake events.
 *
 * [WakeMicEngine] fans each captured frame to one or more detectors so implementations can run in parallel
 * when needed (bit-identical audio, same room/mic/timing).
 *
 * **Threading contract.** [start], [accept], and [updateWakeWords] are all called on the engine's single
 * **capture thread**, at frame boundaries — so an implementation may keep capture-thread-only mutable state
 * without locks, and must never be closed mid-[accept] (the engine defers a wake-set swap to a frame
 * boundary for exactly this reason). [close] is called from the controlling thread after capture has stopped.
 * Readiness is asynchronous: a detector signals [Events.onReady] / [Events.onUnavailable] from whatever
 * thread loads its model.
 *
 * A detector reports results through [Events], tagged with its own [name] so parallel detectors are
 * distinguishable in the log.
 */
interface WakeDetector {

    /** Stable short id for logs, e.g. `"oww"`. Distinguishes parallel detectors. */
    val name: String

    /**
     * (Re)entering capture: reset internal recognition state so the first frame after a fresh start /
     * handoff-reclaim / device rebuild isn't garbled. Called on the capture thread from [WakeMicEngine]'s
     * `onStarted`.
     */
    fun start()

    /**
     * Feed one frame of 16 kHz mono 16-bit PCM ([n] bytes of [buf]). Report a match via the [Events]
     * handed to the factory. Called on the capture thread. An implementation that isn't ready yet should
     * buffer or drop internally — the engine always delivers every frame.
     */
    fun accept(buf: ByteArray, n: Int)

    /**
     * Hot-swap wake words for detectors that resolve phrase models from **bundled assets** at runtime
     * (portal-assistant). Detectors built with explicit [OpenWakeWordDetector.PhraseClassifierConfig]
     * (portal-wake plugin models) must ignore this — rebuild the engine or call
     * [OpenWakeWordDetector.updatePhraseModels] instead. Called on the capture thread at a frame boundary.
     */
    fun updateWakeWords(words: List<WakeWord>)

    /** Full teardown: release the model/native resources. Called from the controlling thread after stop. */
    fun close()

    /** How a detector reports back to [WakeMicEngine]. All methods are tagged with the detector's [name]. */
    interface Events {
        /** The detector's model finished loading and it can accept audio. */
        fun onReady(name: String)

        /** The detector's model is missing/unusable — it will never fire. */
        fun onUnavailable(name: String)

        /** A genuine wake fired: hand off to [id]. [detail] is a human-readable score for the log. */
        fun onWake(name: String, id: String, detail: String)

        /** A tuning-relevant non-fire. Logged verbatim by the engine. */
        fun onDiagnostic(name: String, message: String)

        /** Streaming classifier score in [0, 1] — used by the benchmark harness for peak-score threshold sweeps. */
        fun onScore(name: String, score: Float) {}
    }

    /** Builds a [WakeDetector] once the engine has wired up its [Events] sink. */
    fun interface Factory {
        fun create(context: Context, wakeWords: List<WakeWord>, events: Events): WakeDetector
    }
}
