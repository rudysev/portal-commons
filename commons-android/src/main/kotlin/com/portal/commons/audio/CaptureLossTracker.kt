package com.portal.commons.audio

/**
 * Passes the capture session's [CaptureLoss] on as at most one loss per [WakeMicEngine.start], reported only while
 * capture is wanted and only for the current capture generation.
 *
 * The session decides the cause and never reports a loss once it was asked to stop, so a loss always belongs to the
 * generation current when it is raised. It is raised on the capture thread, handed to [post], and checked again when it
 * is delivered: a [want] or [unwant] in between drops it.
 */
internal class CaptureLossTracker(
    private val post: (deliver: () -> Unit) -> Unit,
    private val onLost: (CaptureLoss) -> Unit,
) {
    private val lock = Any()
    private var wanted = false
    private var generation = 0L
    private var raised = false

    /**
     * [WakeMicEngine.start]: begins a new generation and returns true, or returns false while the current one is still
     * wanted and has raised nothing. Its capture is then still opening, running or about to report how it ended, so
     * that start must not start a capture of its own.
     */
    fun want(): Boolean = synchronized(lock) {
        if (wanted && !raised) return false
        generation++
        raised = false
        wanted = true
        true
    }

    /** [WakeMicEngine.pause]: whatever stops capture from here on was asked for. */
    fun unwant() = synchronized(lock) { wanted = false }

    /** Capture thread: the session reported a capture loss. */
    fun lost(loss: CaptureLoss) {
        val raisedIn = synchronized(lock) {
            if (!wanted || raised) return
            raised = true
            generation
        }
        post { if (claimDelivery(raisedIn)) onLost(loss) }
    }

    // Delivering a loss leaves capture unwanted: the engine is idle, as after a pause.
    private fun claimDelivery(raisedIn: Long): Boolean = synchronized(lock) {
        (wanted && raisedIn == generation).also { if (it) wanted = false }
    }
}
