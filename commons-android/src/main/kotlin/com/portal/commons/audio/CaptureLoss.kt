package com.portal.commons.audio

/**
 * A capture loss and its cause, as defined under **Capture loss** in `GLOSSARY.md`. Delivered through
 * [WakeMicConfig.onCaptureLost]; [reason] is for the log.
 */
sealed interface CaptureLoss {
    val reason: String

    /** Not granted: the mic could not be opened. */
    data class NotGranted(override val reason: String) : CaptureLoss

    /** Dropped: capture was running and died. */
    data class Dropped(override val reason: String) : CaptureLoss

    /** Silenced: capture was running but delivered only digital silence, so it was ended. */
    data class Silenced(override val reason: String) : CaptureLoss
}
