package com.portal.commons.audio

/**
 * A wake phrase matched by the wake detector and delivered to the [WakeMicEngine] consumer: hand off to [wakeId].
 * [transcript] is a human-readable decode for the log.
 */
data class WakeEvent(val wakeId: String, val transcript: String)
