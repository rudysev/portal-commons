package com.portal.commons.audio

/**
 * Pure **which-detector-fire-routes** decision for an app that runs an openWakeWord-based detector
 * alongside the streaming Vosk one.
 *
 * This is a **partition by phrase, not a race.** openWakeWord can only detect a phrase it has a classifier
 * for, so a phrase with no model has to stay Vosk-primary — exactly as it was before openWakeWord existed.
 * [owwOwnedIds] is the set that *does* have one: for those, the oWW-based detector routes and a Vosk fire
 * is a logged shadow (otherwise the same utterance would hand off twice); for everything else, Vosk routes.
 *
 * Applies to both oWW-based detectors. [TwoStageWakeDetector] — where Vosk *verifies* an openWakeWord
 * candidate rather than racing it — is primary for the same reason [OpenWakeWordDetector] is: it owns the
 * ids it has models for. Note the two are different compositions. Running oWW and Vosk as parallel
 * detectors over the *same* phrase is an **OR**, which unions their false accepts; the cascade is an AND.
 * Prefer `WakeDetectors.twoStage()` as the primary, and use this only to partition the phrases it can't
 * cover.
 */
object WakeRouting {

    /**
     * Should a fire from [detectorId] (for wake [firedId]) trigger the handoff?
     *  - **two-stage** / **oww** always route — they only ever fire for ids they hold a model for.
     *  - **vosk** routes iff [firedId] is not in [owwOwnedIds].
     *  - anything else never routes.
     */
    fun shouldRoute(
        detectorId: String,
        firedId: String,
        owwOwnedIds: Set<String>,
    ): Boolean = when (detectorId) {
        TwoStageWakeDetector.ID, OpenWakeWordDetector.ID -> true
        VoskWakeDetector.ID -> firedId !in owwOwnedIds
        else -> false
    }
}
