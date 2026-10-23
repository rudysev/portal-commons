package com.portal.commons.audio

import android.os.Handler
import android.os.Looper

/** Marshals [WakeMicConfig] consumer callbacks onto the agreed threads. */
internal object WakeCallbackThreads {

    /**
     * Always posts to the main looper, even from the main thread, so a callback never runs inside a call the consumer
     * is making to the engine (its constructor included).
     */
    fun mainThreadPoster(): (Runnable) -> Unit {
        val handler = Handler(Looper.getMainLooper())
        return { runnable -> handler.post(runnable) }
    }
}
