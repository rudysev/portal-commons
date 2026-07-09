package com.portal.commons.audio

import android.os.Handler
import android.os.Looper

/** Marshals [WakeMicConfig] consumer callbacks onto the agreed threads. */
internal object WakeCallbackThreads {

    fun mainThreadPoster(): (Runnable) -> Unit = { runnable ->
        val looper = Looper.getMainLooper()
        if (Looper.myLooper() == looper) runnable.run() else Handler(looper).post(runnable)
    }
}
