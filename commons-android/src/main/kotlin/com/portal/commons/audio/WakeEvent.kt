package com.portal.commons.audio

/** A wake phrase matched by a [WakeDetector] and reported to the [WakeMicEngine] consumer. */
data class WakeEvent(
    val detectorId: String,
    val wakeId: String,
    val transcript: String,
)
