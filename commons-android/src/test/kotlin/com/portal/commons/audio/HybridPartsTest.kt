package com.portal.commons.audio

import com.portal.commons.audio.HybridEvidence.Disagreement.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hybrid wake detector's pure parts: the decision rule, the candidate gate, the model lease, disagreements. */
class HybridPartsTest {

    // ---- HybridRule ---------------------------------------------------------------------------------

    private fun decide(spotted: List<String>?, vosk: Boolean) = HybridRule.decide(spotted, "HEY_JARVIS", vosk)

    @Test
    fun `the spotter alone confirming the wake keyword wakes`() {
        assertEquals("sherpa", decide(listOf("HEY_JARVIS"), vosk = false).wokeBy)
    }

    @Test
    fun `a rival keyword vetoes both paths`() {
        val d = decide(listOf("HEY_JARVIS", "OK_JARVIS"), vosk = true)
        assertNull(d.wokeBy)
        assertEquals("OK_JARVIS", d.rival)
        assertNull(decide(listOf("HEY_JERVIS"), vosk = true).wokeBy)
    }

    @Test
    fun `the Vosk window wakes when the spotter heard nothing or failed`() {
        assertEquals("vosk-window", decide(emptyList(), vosk = true).wokeBy)
        assertEquals("vosk-window", decide(null, vosk = true).wokeBy)
        assertNull(decide(emptyList(), vosk = false).wokeBy)
        assertNull(decide(null, vosk = false).wokeBy)
    }

    // ---- CandidateGate ------------------------------------------------------------------------------

    /** Scores [LOUD]-carrying frames 0.9 and everything else 0; counts the frames it scored. */
    private class ToneGate : WakeGate {
        var scored = 0

        override fun score(frame: ShortArray): Float {
            scored++
            return if (frame.any { it == LOUD }) HIGH else 0f
        }

        override fun close() = Unit
    }

    private fun samples(ms: Int, value: Short = 0) = ShortArray(CandidateGate.samplesFor(ms.toLong())) { value }

    @Test
    fun `a candidate completes 400 ms after the gate opens, with the last 2 s as its window`() {
        val gate = ToneGate()
        val cg = CandidateGate(gate) { 0.3f }
        assertNull(cg.feed(samples(2_000), nowMs = 1))
        assertNull(cg.feed(samples(80, LOUD), nowMs = 2)) // opens the candidate
        assertNull(cg.feed(samples(320), nowMs = 3))
        val c = cg.feed(samples(80), nowMs = 4)!! // the fifth gate frame after it
        assertEquals(2L, c.openedAtMs)
        assertEquals(HIGH, c.peak)
        assertEquals(CandidateGate.samplesFor(CandidateGate.WINDOW_MS), c.window.size)
        assertEquals(LOUD, c.window[c.window.size - CandidateGate.samplesFor(480)]) // the tone starts 480 ms back
        assertEquals(0.toShort(), c.window[c.window.size - CandidateGate.samplesFor(481)])
        assertEquals(CandidateGate.samplesFor(2_480), c.audio.size) // all the audio so far: under 4 s
    }

    @Test
    fun `the 2 s refractory makes one utterance one candidate, and reset forgets an open one`() {
        val cg = CandidateGate(ToneGate()) { 0.3f }
        val found = (0 until 40).mapNotNull { cg.feed(samples(80, LOUD), nowMs = it.toLong()) }
        assertEquals(listOf(0L, 25L), found.map { it.openedAtMs }) // 2 s = 25 gate frames apart
        cg.feed(samples(80, LOUD), nowMs = 100) // a long time later: opens again
        cg.reset()
        assertTrue((0 until 10).all { cg.feed(samples(80), nowMs = 200) == null })
    }

    @Test
    fun `capture frames are re-cut into whole gate frames`() {
        val gate = ToneGate()
        val cg = CandidateGate(gate) { 0.3f }
        repeat(4) { cg.feed(samples(100), nowMs = 0) } // 400 ms = 5 gate frames
        assertEquals(5, gate.scored)
    }

    // ---- ModelLease ---------------------------------------------------------------------------------

    @Test
    fun `a borrow in flight defers the free, and a borrow after close is refused`() {
        val model = FakeVoskModel()
        val lease = ModelLease(model)
        lease.borrow {
            lease.close()
            assertEquals(0, model.closeCount)
        }
        assertEquals(1, model.closeCount)
        assertNull(lease.borrow { "ran" })
        lease.close()
        assertEquals(1, model.closeCount)
    }

    // ---- Disagreements ------------------------------------------------------------------------------

    @Test
    fun `a Vosk shadow wake with no hybrid wake near it settles as vosk-only`() {
        val d = Disagreements()
        d.voskWake(10_000)
        d.candidate(9_000, woke = false, verdict = "sherpa=[] vosk=none")
        assertTrue(d.settle(17_000).isEmpty())
        val out = d.settle(18_000).single()
        assertEquals(Kind.VOSK_ONLY, out.kind)
        assertEquals(9_000L, out.candidateAtMs)
        assertTrue(d.settle(40_000).isEmpty())
    }

    @Test
    fun `a hybrid wake with no shadow wake settles as hybrid-only, and a paired one as nothing`() {
        val d = Disagreements()
        d.candidate(1_000, woke = true, verdict = "a")
        d.candidate(30_000, woke = true, verdict = "b")
        d.voskWake(31_000)
        assertEquals(listOf(Kind.HYBRID_ONLY), d.settle(9_000).map { it.kind })
        assertTrue(d.settle(50_000).isEmpty())
    }

    private companion object {
        const val LOUD: Short = 0x2020
        const val HIGH = 0.9f
    }
}
