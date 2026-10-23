package com.portal.commons.audio

/**
 * Translates the capture session's open-failure, error and stopped signals into at most one [CaptureLoss] per
 * [WakeMicEngine.start], reported only while capture is wanted and only for the current capture generation.
 *
 * Each capture run (one capture thread) claims the generation that is current at its first signal and keeps it,
 * so a late stop from a run that an earlier pause ended never counts against a later start. A loss is raised on the
 * capture thread, handed to [post], and checked again when it is delivered: a [want] or [unwant] in between drops it.
 */
internal class CaptureLossTracker(
    private val post: (deliver: () -> Unit) -> Unit,
    private val onLost: (CaptureLoss) -> Unit,
) {
    private class Run(val generation: Long) {
        var started = false
        var error: String? = null
    }

    private val lock = Any()
    private var wanted = false
    private var generation = 0L
    private var raised = false

    // Run state of the capture thread calling in. The session starts a new thread for every run.
    private val run = ThreadLocal<Run>()

    private fun currentRun(): Run = run.get() ?: Run(synchronized(lock) { generation }).also { run.set(it) }

    /**
     * [WakeMicEngine.start]: begins a new generation and returns true, or returns false while the current one is still
     * wanted and has raised nothing. Its run is then still opening, running or about to report how it ended, so that
     * start must not start a run of its own: one started now could not be told apart from that run's late signals.
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

    /** Capture thread: the mic opened and capture is running. */
    fun runStarted() {
        currentRun().started = true
    }

    /** Capture thread: before the run started this is a failed open; after, the reason the run is about to end. */
    fun runError(message: String) {
        val current = currentRun()
        if (current.started) {
            current.error = message
        } else {
            raise(current.generation, CaptureLoss.NotGranted(message))
        }
    }

    /** Capture thread: the run ended. A run that never started was stopped while opening, which a pause asks for. */
    fun runStopped() {
        val current = run.get()?.takeIf { it.started } ?: return
        raise(current.generation, CaptureLoss.Dropped(current.error ?: "capture ended unexpectedly"))
    }

    private fun raise(raisedIn: Long, loss: CaptureLoss) {
        val first = synchronized(lock) { (wanted && raisedIn == generation && !raised).also { if (it) raised = true } }
        if (first) post { if (claimDelivery(raisedIn)) onLost(loss) }
    }

    // Delivering a loss leaves capture unwanted: the engine is idle, as after a pause.
    private fun claimDelivery(raisedIn: Long): Boolean = synchronized(lock) {
        (wanted && raisedIn == generation).also { if (it) wanted = false }
    }
}
