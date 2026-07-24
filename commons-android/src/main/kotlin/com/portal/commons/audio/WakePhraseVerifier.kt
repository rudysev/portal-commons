package com.portal.commons.audio

/**
 * **Stage 2 of the cascade**: given the audio window stage 1 flagged, did the wake phrase actually get
 * *spoken*, as opposed to something that merely scored like it?
 *
 * Deliberately narrower than [WakeDetector]. A detector owns a stream, decides on its own schedule, and
 * fires events; a verifier answers one question about one window, synchronously, and reports nothing. That
 * asymmetry is the cascade: composing two *detectors* gives you an OR (either can fire, so their false
 * accepts add up), whereas a detector plus a verifier gives you an AND.
 *
 * **Threading.** [verify] is called on the engine's capture thread and must return inside
 * [TwoStageTuning.VERIFY_BUDGET_MS]. [state] is read from the capture thread but written from a model-load
 * thread, so implementations must make it safely visible (`@Volatile`).
 */
internal interface WakePhraseVerifier {

    /** Whether [verify] can be consulted right now — drives [TwoStagePolicy]'s fallback path. */
    val state: TwoStagePolicy.VerifierState

    /**
     * Was the phrase belonging to [wakeId] spoken in [window] (16 kHz mono s16, oldest sample first)?
     *
     * Verifies against **that wake word's own phrase**, not "any registered phrase": stage 1 fires per
     * classifier, so a candidate for `jarvis` confirmed by a decode of "hey alexa" would be a cross-phrase
     * false accept. Returns false for an unknown [wakeId] — nothing to verify against is not a confirmation.
     */
    fun verify(window: ShortArray, wakeId: String): Boolean

    /**
     * Track the live wake set. The grammar is built from the registered phrases, so a plugin install or
     * removal must reach stage 2 as well — otherwise a newly registered phrase can never be confirmed.
     * Called on the capture thread at a frame boundary, like [WakeDetector.updateWakeWords].
     */
    fun updateWakeWords(words: List<WakeWord>)

    /** Release the model/native resources. Called from the controlling thread after capture has stopped. */
    fun close()
}
