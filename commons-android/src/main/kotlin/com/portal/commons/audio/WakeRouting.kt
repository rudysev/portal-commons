package com.portal.commons.audio

/**
 * Pure **which-detector-fire-routes** decision for apps that run openWakeWord and Vosk in parallel.
 *
 * openWakeWord is the primary detector for every id in [owwOwnedIds]; Vosk covers everything else and is
 * the fallback for those ids only when they are *not* in the owned set (e.g. oWW inactive / no heads).
 * When oWW owns an id, a Vosk fire of it is a logged shadow — otherwise the same utterance would hand off
 * twice.
 */
object WakeRouting {

    /**
     * Should a fire from [detectorId] (for wake [firedId]) trigger the handoff?
     *  - **oww** always routes.
     *  - **vosk** routes iff [firedId] is not in [owwOwnedIds].
     *  - anything else never routes.
     */
    fun shouldRoute(
        detectorId: String,
        firedId: String,
        owwOwnedIds: Set<String>,
    ): Boolean = when (detectorId) {
        OpenWakeWordDetector.ID -> true
        VoskWakeDetector.ID -> firedId !in owwOwnedIds
        else -> false
    }
}
