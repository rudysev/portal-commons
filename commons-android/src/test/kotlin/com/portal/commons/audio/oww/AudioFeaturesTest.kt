package com.portal.commons.audio.oww

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden test for the streaming feature buffering — the exact behaviour the #8 primitive-buffer rewrite must
 * preserve. Deterministic [MelExtractor]/[Embedder] fakes stand in for ONNX (no model on the JVM classpath):
 * the mel emits uniquely-numbered frames, the embedder records the (first,last) mel-frame span of every
 * embedding window it's asked to compute. That span sequence is a fingerprint of the windowing/striding/
 * ring-buffer logic; if the rewrite changes it, this test fails.
 */
class AudioFeaturesTest {

    /**
     * Emits `input.size / 160` mel frames (hop 160). Each frame's value is the first raw sample of that hop —
     * **content-sensitive**, so the golden also pins the exact raw samples (and their order) that the raw ring
     * hands to the mel stage, not just the frame counts. With a distinct-per-sample ramp fed in, any ring
     * mis-ordering shows up in the checksum.
     */
    private class FakeMel : MelExtractor {
        override fun compute(audioSamples: FloatArray): Array<FloatArray> =
            Array(audioSamples.size / 160) { fi -> FloatArray(32) { audioSamples[fi * 160] } }
    }

    /** Records each embedding window's (firstFrameId, lastFrameId); returns a 96-vec carrying that span. */
    private class FakeEmbedder : Embedder {
        val spans = ArrayList<Pair<Float, Float>>()
        override fun generate(input: Array<Array<Array<FloatArray>>>): Array<FloatArray> =
            Array(input.size) { b ->
                val window = input[b] // [76][32][1]
                val first = window.first()[0][0]
                val last = window.last()[0][0]
                spans.add(first to last)
                FloatArray(96) { if (it == 0) first else if (it == 1) last else 0f }
            }
    }

    /** Compact, order-sensitive fingerprint of the window-span sequence. */
    private fun signature(spans: List<Pair<Float, Float>>): String {
        val checksum = spans.foldIndexed(0L) { i, acc, (a, b) ->
            acc * 1_000_003L + a.toRawBits().toLong() * 7 + b.toRawBits().toLong() * 13 + i
        }
        val tail = spans.takeLast(16).joinToString(";") { (a, b) -> "${a.toInt()}-${b.toInt()}" }
        return "count=${spans.size} checksum=$checksum tail=[$tail]"
    }

    @Test fun `feature window sequence is stable across the buffering rewrite`() {
        val mel = FakeMel()
        val emb = FakeEmbedder()
        val af = AudioFeatures(mel, emb)
        af.reset()                       // mirror a production re-arm; also isolates from the init warm-up
        emb.spans.clear()
        // 150 × 100 ms production frames (1600 samples) of a distinct-per-sample ramp: enough to fill the
        // 16-window score buffer and cross both the feature-buffer (120) and mel-buffer (970) trim caps.
        var sample = 0f
        repeat(150) { af.accept(FloatArray(1600) { sample++ }) }
        assertEquals(GOLDEN, signature(emb.spans))
    }

    @Test fun `reset seeds a full 1x16x96 score window before any real audio`() {
        val af = AudioFeatures(FakeMel(), FakeEmbedder())
        af.reset()
        // A sub-step frame adds no embedding yet, so this is the zero-seeded window the first frames score against.
        val window = af.accept(FloatArray(160))
        assertEquals(1, window.size)
        assertEquals(16, window[0].size)
        assertEquals(96, window[0][0].size)
    }

    @Test fun `score window stays 1x16x96 once real embeddings flow`() {
        val af = AudioFeatures(FakeMel(), FakeEmbedder())
        af.reset()
        repeat(30) { af.accept(FloatArray(1600)) }
        val window = af.accept(FloatArray(1600))
        assertEquals(1, window.size)
        assertEquals(16, window[0].size)
        assertEquals(96, window[0][0].size)
    }

    private companion object {
        // Captured from the known-good buffering (verified byte-identical between the ArrayDeque original and
        // the primitive-ring rewrite via a stash/pop cross-check). Regenerate only if the algorithm is meant
        // to change. The span values are raw-sample indices (content-sensitive), so this also pins ring ordering.
        private const val GOLDEN = "count=187 checksum=-684796710055088591 tail=[210880-220000;212160-221280;213440-222560;214240-223840;215520-225120;217280-226400;218560-227680;219840-228960;220640-230240;221920-231520;223680-232800;224960-234080;226240-235360;227040-236640;228320-237920;230080-239200]"
    }
}
