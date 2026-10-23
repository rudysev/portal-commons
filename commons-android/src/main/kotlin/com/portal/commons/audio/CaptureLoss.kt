package com.portal.commons.audio

/**
 * Why capture is not running while the consumer wants it running (see **Capture loss** in `GLOSSARY.md`). Delivered
 * through [WakeMicConfig.onCaptureLost] at most once per [WakeMicEngine.start]; [reason] is for the log.
 */
sealed interface CaptureLoss {
    val reason: String

    /** The mic could not be opened, including a start refused because a previous capture is wedged. */
    data class NotGranted(override val reason: String) : CaptureLoss

    /** Capture was running and died, including a failed rebuild. */
    data class Dropped(override val reason: String) : CaptureLoss
}
