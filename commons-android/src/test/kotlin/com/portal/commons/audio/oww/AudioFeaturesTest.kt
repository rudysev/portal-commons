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

    /** Emits `input.size / 160` mel frames (hop 160), each a distinct increasing id — traces which samples land where. */
    private class FakeMel : MelExtractor {
        var nextId = 0f
        override fun compute(audioSamples: FloatArray): Array<FloatArray> =
            Array(audioSamples.size / 160) { val id = nextId++; FloatArray(32) { id } }
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
        mel.nextId = 0f
        emb.spans.clear()
        // 150 × 100 ms production frames (1600 samples): enough to fill the 16-window score buffer and cross
        // both the feature-buffer (120) and mel-buffer (970) trim caps.
        repeat(150) { af.accept(FloatArray(1600)) }
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
        // Captured from the known-good buffering. Regenerate deliberately only if the algorithm is meant to change.
        private const val GOLDEN = "count=187 checksum=-677914761722745295 tail=[1711-1786;1722-1797;1733-1808;1741-1816;1752-1827;1763-1838;1774-1849;1785-1860;1793-1868;1804-1879;1815-1890;1826-1901;1837-1912;1845-1920;1856-1931;1867-1942]"
    }
}
