package com.portal.commons.audio

/**
 * Owns the [WakeMicEngine]'s **capture requests** (see `GLOSSARY.md`) and the at most one [CaptureLoss] each reports.
 *
 * [begin] hands out a [CaptureRequest] whose session callbacks are bound to it. The handle answers both "is this still
 * the current capture?" ([CaptureRequest.isCurrent]) and "report this loss" ([CaptureRequest.lost]). A loss is raised
 * on the capture thread, handed to [post], and checked again when it is delivered: an [end] or a newer [begin] in
 * between drops it.
 */
internal class CaptureRequests(
    private val post: (deliver: () -> Unit) -> Unit,
    private val onLost: (CaptureLoss) -> Unit,
) {
    private val lock = Any()

    // The open request, if any: always the newest one, since only begin() sets it.
    private var open: CaptureRequest? = null

    // The newest request begun. Read on every frame, so volatile rather than under the lock.
    @Volatile private var current: CaptureRequest? = null

    /**
     * [WakeMicEngine.start]: begins and returns a new capture request, or returns null while one is open and has
     * raised no loss. Its capture is then still opening, running or about to report how it ended, so that start joins
     * it.
     */
    fun begin(): CaptureRequest? = synchronized(lock) {
        val joined = open
        if (joined != null && !joined.raised) return null
        CaptureRequest().also {
            open = it
            current = it
        }
    }

    /** [WakeMicEngine.pause]: ends the open request, so whatever stops capture from here on was asked for. */
    fun end() = synchronized(lock) { open = null }

    /** One capture request, and the handle its capture session's callbacks are bound to. */
    inner class CaptureRequest {
        // Guarded by lock.
        var raised = false
            private set

        /** True while no newer request has begun. */
        val isCurrent: Boolean get() = current === this

        /** Capture thread: this request's session reported a capture loss. Raised at most once, while open. */
        fun lost(loss: CaptureLoss) {
            synchronized(lock) {
                if (open !== this || raised) return
                raised = true
            }
            post { if (claimDelivery()) onLost(loss) }
        }

        // Delivering a loss ends the request: the engine is idle, as after a pause.
        private fun claimDelivery(): Boolean = synchronized(lock) {
            (open === this).also { if (it) open = null }
        }
    }
}
