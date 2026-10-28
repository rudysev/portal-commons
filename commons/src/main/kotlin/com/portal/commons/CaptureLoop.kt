package com.portal.commons

import com.portal.commons.audio.CaptureLoss
import java.util.concurrent.TimeUnit

/**
 * The frame rules of one capture, as a pure state machine: fed the results of the reads and rebuilds a
 * [PcmCaptureSession] carries out and the monotonic time each one finished, it returns the next [Action]. It touches no
 * device, thread or log, so every rule is tested synchronously. Built right after the device opens, and used on the
 * capture thread only.
 *
 * **Frames.** [Action.Read] asks for at most what the current [PcmCaptureFormat.FRAME_BYTES] frame still needs, so
 * reads end exactly on frame boundaries; the loop copies each read into its private frame buffer and hands a completed
 * frame out in [Action.Deliver]. A read of 0 is a normal non-blocking "no data yet" and polls again after
 * [READ_POLL_MS]; a read error (`< 0`) discards the partial frame and reads again after [READ_ERROR_BACKOFF_MS].
 *
 * **Rebuild.** With [CapturePolicy.rebuildAfterReadFailures] set, that many consecutive read errors rebuild the
 * device. With [CapturePolicy.idleRebuildMs] set, the device is *also* rebuilt when no full frame has been
 * **delivered** for that long, catching a stolen / half-dead mic that returns a clean 0, a sub-frame trickle, or
 * partials interleaved with sub-threshold errors (none of which the `< 0` path sees), so a consumer can't go silently
 * deaf. The idle clock is monotonic, and restarts when the device opens or reopens and at the first input after a
 * delivered frame (the session reads again as soon as the frame's callback returns, so a slow callback is not an idle
 * mic). Both are transient mic-loss recovery (the always-listening apps opt in). A rebuild that fails to reopen ends
 * capture as [CaptureLoss.Dropped].
 *
 * **Silence.** With [CapturePolicy.silence] set, a capture that delivers its [CapturePolicy.Silence.windowMs] of
 * exact-zero samples in a row ends as [CaptureLoss.Silenced], with no rebuild: a denied app-op's silence survives a
 * reopen (and on gen1 the reopen fails). With [CapturePolicy.Silence.rebuildFirst] also set, a full window first
 * rebuilds the device, once per silence: Portal+ gen1 silences a client's open input when its app leaves the
 * foreground, and a reopen is live again. Only a window that follows that rebuild with no sample heard in between ends
 * capture. Real mics never produce exact zeros for that long, even in a quiet room (checked on Portal+ gen1), so there
 * is no tolerance band. The window is counted in samples, not on a clock, and starts again at every open, every
 * rebuild and every non-zero sample. A frame is counted against it once delivered: [Action.Deliver.then] carries that
 * decision.
 */
internal class CaptureLoop(policy: CapturePolicy, openedAtNs: Long) {
    private val rebuildAfterReadFailures = policy.rebuildAfterReadFailures
    private val idleRebuildMs = policy.idleRebuildMs
    private val silence = policy.silence

    private val frame = ByteArray(PcmCaptureFormat.FRAME_BYTES)
    private var off = 0 // bytes assembled into the current frame so far
    private var readFails = 0

    // Monotonic time the idle clock last restarted; null right after a delivered frame, until the next input.
    private var lastFrameNs: Long? = openedAtNs

    // Consecutive exact-zero samples delivered, against the silence window counted in samples of audio.
    private var zeroSamples = 0L

    // With rebuildFirst: a silence window already rebuilt the device, and no sample has been heard since.
    private var silenceRebuilt = false

    /** The first action: always a [Action.Read] of a whole frame. */
    fun first(): Action = next()

    /** The device returned [n] (bytes read, 0 for no data, `< 0` for an error) into [bytes], at [nowNs]. */
    fun onRead(bytes: ByteArray, n: Int, nowNs: Long): Action {
        val since = lastFrameNs ?: nowNs // the first input after a delivered frame restarts the idle clock
        lastFrameNs = since
        return when {
            // The read's result is dropped: the rebuild discards any partial frame anyway.
            idleRebuildMs != null && nowNs - since >= TimeUnit.MILLISECONDS.toNanos(idleRebuildMs) ->
                Action.Rebuild("no frame for ${idleRebuildMs}ms")

            n == 0 -> next(READ_POLL_MS)

            n < 0 -> onDeviceError()

            else -> onData(bytes, n)
        }
    }

    private fun onData(bytes: ByteArray, n: Int): Action {
        readFails = 0
        bytes.copyInto(frame, destinationOffset = off, endIndex = n)
        off += n
        if (off != PcmCaptureFormat.FRAME_BYTES) return next() // partial frame: drain whatever else is buffered
        off = 0
        lastFrameNs = null
        return Action.Deliver(frame, afterDelivery())
    }

    /** The [Action.Rebuild] finished at [nowNs]: the device [reopened], or failed to. */
    fun onRebuilt(reopened: Boolean, nowNs: Long): Action {
        if (!reopened) return Action.End(CaptureLoss.Dropped("microphone unavailable on rebuild"))
        off = 0
        readFails = 0
        lastFrameNs = nowNs // fresh device: restart the idle clock
        zeroSamples = 0 // and the silence window
        return next()
    }

    /** Counts the frame in [frame] against the silence window, and decides what follows its delivery. */
    private fun afterDelivery(): Action {
        val rule = silence ?: return next()
        val window = rule.windowMs * PcmCaptureFormat.SAMPLE_RATE / MS_PER_SECOND // in samples of audio
        val runBefore = zeroSamples
        zeroSamples = trailingZeroSamples(runBefore)
        if (zeroSamples < runBefore + FRAME_SAMPLES) silenceRebuilt = false // a sample heard: the stream is live
        val reason = "digital silence for ${rule.windowMs}ms"
        return when {
            zeroSamples < window -> next()

            rule.rebuildFirst && !silenceRebuilt -> {
                silenceRebuilt = true
                Action.Rebuild(reason)
            }

            else -> Action.End(CaptureLoss.Silenced(reason))
        }
    }

    // The zero run after this frame: it grows by the whole frame if the frame is all zeros, else it is the frame's own
    // trailing zeros.
    private fun trailingZeroSamples(runBefore: Long): Long {
        var i = PcmCaptureFormat.FRAME_BYTES - PcmCaptureFormat.BYTES_PER_SAMPLE
        var trailing = 0L
        while (i >= 0 && frame[i] == ZERO && frame[i + 1] == ZERO) {
            trailing++
            i -= PcmCaptureFormat.BYTES_PER_SAMPLE
        }
        return if (i < 0) runBefore + trailing else trailing
    }

    private fun onDeviceError(): Action {
        off = 0 // discard any partial frame to avoid splicing audio
        readFails++
        val rebuildAfter = rebuildAfterReadFailures
        if (rebuildAfter != null && readFails >= rebuildAfter) return Action.Rebuild("read failing")
        return next(READ_ERROR_BACKOFF_MS)
    }

    private fun next(afterMs: Long = 0) = Action.Read(PcmCaptureFormat.FRAME_BYTES - off, afterMs)

    /** What the session carries out next. Every [Action.Rebuild] and [Action.End] reason is for its log. */
    sealed interface Action {
        /** Wait [afterMs], then read at most [length] bytes and feed the result to [onRead]. */
        data class Read(val length: Int, val afterMs: Long = 0) : Action

        /** Hand [frame] to the consumer, then carry out [then]. The frame is valid only until the next input. */
        data class Deliver(val frame: ByteArray, val then: Action) : Action

        /** Stop, release and reopen the device, and feed the result to [onRebuilt]. */
        data class Rebuild(val reason: String) : Action

        /** End the capture with [loss]. */
        data class End(val loss: CaptureLoss) : Action
    }

    companion object {
        // Sleep between non-blocking reads when no data is buffered yet. At 32 B/ms this wakes ~50×/s
        // (negligible CPU), drains ≤ ~640 B per idle wake, adds ≤ this many ms to a 100 ms frame, and leaves
        // ~20× headroom under the ~400 ms AudioRecord buffer. When data is flowing the loop drains without
        // sleeping, so steady-state cadence stays ~100 ms/frame.
        const val READ_POLL_MS = 20L
        const val READ_ERROR_BACKOFF_MS = 20L

        private const val MS_PER_SECOND = 1_000L
        private const val FRAME_SAMPLES = PcmCaptureFormat.FRAME_BYTES / PcmCaptureFormat.BYTES_PER_SAMPLE
        private const val ZERO: Byte = 0
    }
}
