package com.portal.commons.audio

/**
 * Where [TwoStageWakeDetector] sends the **audio behind a decision**, so a score can be checked by ear
 * afterwards.
 *
 * This exists because a live run without it produces numbers nobody can interpret. Every finding in the
 * offline evaluation (`hey-jarvis/PHASE_B.md`) came from listening to a saved clip — including the one the
 * whole cascade design rests on, openWakeWord scoring **0.998** on ordinary conversation. Without the audio
 * that event is just an unexplained high score; with it, it is the measurement that killed the bypass. A
 * `debug.txt` line alone cannot distinguish a false accept from someone genuinely saying the phrase, which
 * is precisely the distinction the tuning turns on.
 *
 * The detector holds no policy about *where* clips go or how many are kept — that is the consumer's, via
 * [WakeClipRecorder] or its own implementation. Passing `null` (the default) disables capture entirely and
 * costs nothing on the capture thread.
 *
 * **Threading.** [save] is called on the **capture thread**, inside `accept()`, and must not block: stage 2
 * already spends ~900 ms there against a [TwoStageTuning.VERIFY_BUDGET_MS] budget, so an implementation
 * that writes synchronously would both risk dropping audio and corrupt the latency measurement that budget
 * exists to make. [WakeClipRecorder] hands the write to its own thread for exactly that reason.
 */
fun interface WakeAudit {

    /**
     * Persist [pcm] — the 2 s window the decision was made on, oldest sample first — as evidence of [clip]
     * for [wakeId] at stage-1 [score].
     *
     * The array is a snapshot the caller will not touch again, so an implementation may keep it.
     */
    fun save(clip: Clip, wakeId: String, score: Float, pcm: ShortArray)

    /** What a clip is evidence *of*. [prefix] leads the filename so a pull can be triaged by name alone. */
    enum class Clip(val prefix: String) {
        /** The cascade fired and the mic was handed off. Confirms the wake was real. */
        WAKE("wake"),

        /**
         * Stage 1 proposed and the candidate was not fired — stage 2 rejected the phrase, or (only while
         * stage 2 loads) the score fell under [TwoStageTuning.FALLBACK_SCORE]. **The most valuable clips:**
         * this is the cascade earning its keep, and the reason is on the matching `near-miss` line in
         * `debug.txt`.
         */
        REJECTED("rej"),

        /**
         * Stage 1 scored just below its own threshold, so no candidate was ever raised. Catches the misses
         * that never reach the policy at all — a wake nobody heard leaves no other trace.
         */
        NEAR("near"),
    }
}
