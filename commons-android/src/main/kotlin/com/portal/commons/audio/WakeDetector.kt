package com.portal.commons.audio

import android.content.Context

/**
 * The **engine-agnostic wake-detection boundary**: everything downstream of "here is a 16 kHz mono 16-bit
 * PCM frame" that decides whether a wake phrase was spoken. [WakeMicEngine] owns the mic, the capture
 * thread, and the frame assembly; a [WakeDetector] owns *recognition* — the recognizer/model, its own
 * readiness + warm-up, any pre-ready buffering, and turning frames into wake events.
 *
 * This is the seam that lets Vosk be **swapped** for another detector (e.g. openWakeWord) or **run in
 * parallel** with it: [WakeMicEngine] fans each captured frame to a list of detectors, so an A/B comparison
 * is *paired* (bit-identical audio to every detector, same room/mic/timing) — see [WakeMicEngine].
 *
 * **Threading contract (mirrors the old inline Vosk path).** [start], [accept], and [updateWakeWords] are
 * all called on the engine's single **capture thread**, at frame boundaries — so an implementation may keep
 * capture-thread-only mutable state without locks, and must never be closed mid-[accept] (the engine defers
 * a wake-set swap to a frame boundary for exactly this reason). [close] is called from the controlling
 * thread after capture has stopped. Readiness is asynchronous: a detector signals [Events.onReady] /
 * [Events.onUnavailable] from whatever thread loads its model.
 *
 * A detector reports results through [Events], tagged with its own [name] so parallel detectors are
 * distinguishable in the log when run in parallel.
 */
interface WakeDetector {

    /** Stable short id for logs, e.g. `"vosk"`, `"oww"`. Distinguishes parallel detectors. */
    val name: String

    /**
     * (Re)entering capture: reset internal recognition state and re-warm so the first frame after a fresh
     * start / handoff-reclaim / device rebuild isn't garbled. Called on the capture thread from
     * [WakeMicEngine]'s `onStarted`.
     */
    fun start()

    /**
     * Feed one frame of 16 kHz mono 16-bit PCM ([n] bytes of [buf]). Report a match/near-miss/diagnostic via
     * the [Events] handed to the factory. Called on the capture thread. An implementation that isn't ready
     * yet should buffer or drop internally — the engine always delivers every frame.
     */
    fun accept(buf: ByteArray, n: Int)

    /**
     * Swap the active wake set without tearing down capture. Called on the capture thread (the engine defers
     * the swap to a frame boundary), so it may close/rebuild native recognizer state safely. Detectors whose
     * model is fixed per wake word (e.g. a per-phrase neural model) may treat this as a no-op.
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

        /** A genuine wake fired: hand off to [id]. [detail] is a human-readable decode/score for the log. */
        fun onWake(name: String, id: String, detail: String)

        /** A tuning-relevant non-fire (near-miss, flushed decode, score trace). Logged verbatim by the engine. */
        fun onDiagnostic(name: String, message: String)
    }

    /**
     * Builds a [WakeDetector] once the engine has wired up its [Events] sink. Kept as a factory (not a ready
     * instance) so the engine controls construction order and the detector can start loading its model as
     * soon as it exists.
     */
    fun interface Factory {
        fun create(context: Context, wakeWords: List<WakeWord>, events: Events): WakeDetector
    }
}
