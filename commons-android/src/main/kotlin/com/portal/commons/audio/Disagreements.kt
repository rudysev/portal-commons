package com.portal.commons.audio

import com.portal.commons.audio.HybridEvidence.Companion.PAIR_MS
import com.portal.commons.audio.HybridEvidence.Companion.SETTLE_MS
import com.portal.commons.audio.HybridEvidence.Disagreement

/**
 * Pairs the Vosk shadow's wakes with the hybrid's candidates by time and reports each utterance the two decided
 * differently. A Vosk wake and a hybrid wake within [PAIR_MS] of each other are the same utterance. Each event is
 * settled [SETTLE_MS] after it, by when its partner would have arrived (the hybrid's verdict lands seconds after the
 * gate opened). Thread-safe: the capture thread reports Vosk wakes and settles, the verify thread reports candidates.
 */
internal class Disagreements {

    private class Event(val atMs: Long, val woke: Boolean, val verdict: String?) {
        var settled = false
    }

    private val vosk = ArrayList<Event>()
    private val candidates = ArrayList<Event>()

    @Synchronized
    fun voskWake(atMs: Long) {
        vosk.add(Event(atMs, woke = true, verdict = null))
    }

    @Synchronized
    fun candidate(atMs: Long, woke: Boolean, verdict: String) {
        candidates.add(Event(atMs, woke, verdict))
    }

    /** Forget everything unsettled (the Vosk shadow was switched off, or the hybrid stopped deciding). */
    @Synchronized
    fun clear() {
        vosk.clear()
        candidates.clear()
    }

    /** The disagreements settled by [nowMs], each reported once. */
    @Synchronized
    fun settle(nowMs: Long): List<Disagreement> {
        val out = ArrayList<Disagreement>()
        for (v in vosk) {
            if (v.settled || v.atMs + SETTLE_MS > nowMs) continue
            v.settled = true
            val near = candidates.filter { near(it.atMs, v.atMs) }
            if (near.none { it.woke }) {
                val nearest = near.minByOrNull { kotlin.math.abs(it.atMs - v.atMs) }
                out += Disagreement(nowMs, Disagreement.Kind.VOSK_ONLY, v.atMs, nearest?.atMs, nearest?.verdict)
            }
        }
        for (c in candidates) {
            if (c.settled || c.atMs + SETTLE_MS > nowMs) continue
            c.settled = true
            if (c.woke && vosk.none { near(it.atMs, c.atMs) }) {
                out += Disagreement(nowMs, Disagreement.Kind.HYBRID_ONLY, null, c.atMs, c.verdict)
            }
        }
        // Kept until nothing unsettled can still pair with them.
        vosk.removeAll { it.settled && it.atMs + 2 * SETTLE_MS < nowMs }
        candidates.removeAll { it.settled && it.atMs + 2 * SETTLE_MS < nowMs }
        return out
    }

    private fun near(a: Long, b: Long) = kotlin.math.abs(a - b) <= PAIR_MS
}
