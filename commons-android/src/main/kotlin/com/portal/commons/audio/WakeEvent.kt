package com.portal.commons.audio

/**
 * A wake phrase matched by a [WakeDetector] and reported to the [WakeMicEngine] consumer.
 *
 * @param detectorId which detector fired (e.g. `"vosk"`, `"oww"`, `"two-stage"`).
 * @param wakeId the wake word that matched (e.g. `"jarvis"`).
 * @param transcript human-readable evidence for the log — a Vosk decode, or the rule that fired.
 * @param score the detector's raw confidence in [0, 1], when it has one. Carried **structurally** rather
 *   than only rendered into [transcript] because [TwoStageWakeDetector] gates on it: its bypass and
 *   single-stage-fallback rules are numeric comparisons against stage 1's score, and parsing them back out
 *   of a formatted string would couple the cascade to another detector's log format. Null for a detector
 *   with no meaningful scalar score (Vosk reports per-word confidences, not one number).
 */
data class WakeEvent(
    val detectorId: String,
    val wakeId: String,
    val transcript: String,
    val score: Float? = null,
)
