package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat

/**
 * The hybrid's first check over the capture stream: re-cuts the engine's frames into [WakeGate.FRAME_SAMPLES]-sample
 * gate frames, scores each with the [WakeGate], and opens a **candidate** on a score at or above [threshold].
 * [POST_ROLL_MS] later (the gate peaks around the end of "jarvis", so the word finishes inside the window) the
 * candidate is complete: the last [WINDOW_MS] of audio is what both checks decode. After a candidate opens, none
 * opens for [REFRACTORY_MS], so one utterance is one candidate. Pure and single-threaded: the gate thread owns it.
 */
internal class CandidateGate(private val gate: WakeGate, private val threshold: () -> Float) {

    /** A completed candidate: [window] is what the checks decode; [audio] the longer clip kept as evidence. */
    class Candidate(val openedAtMs: Long, val peak: Float, val window: ShortArray, val audio: ShortArray)

    private class Open(val frame: Long, val atMs: Long, var peak: Float)

    private val pending = ShortArray(WakeGate.FRAME_SAMPLES)
    private var pendingSamples = 0
    private val ring = ShortRing(samplesFor(HybridEvidence.CANDIDATE_AUDIO_MS.toLong()))
    private var frames = 0L
    private var open: Open? = null
    private var lastOpenFrame = -REFRACTORY_FRAMES

    /** Nanoseconds the last [feed] spent in the gate, for the stats line. */
    var lastGateNanos = 0L
        private set

    /**
     * Feed [samples] of capture audio; returns the candidate it completed, if any. [capturedAtMs], the wall-clock
     * time [samples] were captured, stamps a candidate that opens inside this call.
     */
    fun feed(samples: ShortArray, capturedAtMs: Long): Candidate? {
        var completed: Candidate? = null
        var gateNanos = 0L
        var i = 0
        while (i < samples.size) {
            val n = minOf(samples.size - i, pending.size - pendingSamples)
            samples.copyInto(pending, pendingSamples, i, i + n)
            pendingSamples += n
            i += n
            if (pendingSamples == pending.size) {
                pendingSamples = 0
                val started = System.nanoTime()
                val c = gateFrame(pending.copyOf(), capturedAtMs)
                gateNanos += System.nanoTime() - started
                if (c != null) completed = c
            }
        }
        lastGateNanos = gateNanos
        return completed
    }

    /** Forget the audio and any open candidate (a capture (re)start or mode switch): no window spans a gap. */
    fun reset() {
        pendingSamples = 0
        ring.clear()
        open = null
        lastOpenFrame = frames - REFRACTORY_FRAMES
    }

    private fun gateFrame(frame: ShortArray, nowMs: Long): Candidate? {
        ring.write(frame)
        frames++
        val score = gate.score(frame)
        val current = open
        if (current != null) {
            current.peak = maxOf(current.peak, score)
        } else if (score >= threshold() && frames - lastOpenFrame >= REFRACTORY_FRAMES) {
            open = Open(frames, nowMs, score)
            lastOpenFrame = frames
        }
        val due = open?.takeIf { frames - it.frame >= POST_ROLL_FRAMES } ?: return null
        open = null
        return Candidate(due.atMs, due.peak, ring.latest(samplesFor(WINDOW_MS)), ring.latest(ring.capacity))
    }

    companion object {
        /** The window both checks decode: the last ~2 s, ending [POST_ROLL_MS] after the candidate opened. */
        const val WINDOW_MS = 2_000L
        const val POST_ROLL_MS = 400L
        const val REFRACTORY_MS = 2_000L

        private const val MS_PER_S = 1_000L
        private const val GATE_FRAME_MS = WakeGate.FRAME_SAMPLES * MS_PER_S / PcmCaptureFormat.SAMPLE_RATE
        private const val POST_ROLL_FRAMES = POST_ROLL_MS / GATE_FRAME_MS
        private const val REFRACTORY_FRAMES = REFRACTORY_MS / GATE_FRAME_MS

        fun samplesFor(ms: Long): Int = (ms * PcmCaptureFormat.SAMPLE_RATE / MS_PER_S).toInt()
    }
}

/** The last [capacity] samples written, oldest dropped first. Single-threaded. */
internal class ShortRing(val capacity: Int) {
    private val buf = ShortArray(capacity)
    private var next = 0
    private var size = 0

    fun write(samples: ShortArray) {
        for (s in samples) {
            buf[next] = s
            next = (next + 1) % capacity
        }
        size = minOf(capacity, size + samples.size)
    }

    /** A copy of the newest [n] samples (fewer while filling), oldest first. */
    fun latest(n: Int): ShortArray {
        val count = minOf(n, size)
        val start = Math.floorMod(next - count, capacity)
        return ShortArray(count) { buf[(start + it) % capacity] }
    }

    fun clear() {
        next = 0
        size = 0
    }
}
