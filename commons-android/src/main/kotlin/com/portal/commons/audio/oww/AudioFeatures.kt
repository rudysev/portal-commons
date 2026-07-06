package com.portal.commons.audio.oww

import kotlin.random.Random

/**
 * openWakeWord streaming feature extractor: raw 16 kHz int16-magnitude audio → the rolling
 * [1,16,96] embedding window a wake-word model scores. Shares the (expensive) mel + embedding
 * computation so one instance can feed several wake models.
 *
 * Faithfully adapted from Re-MENTIA/openwakeword-android-kt's `AudioProcessor` (Apache-2.0), which
 * mirrors dscripka/openWakeWord's Python `AudioFeatures`. Differences: (1) the ML sessions are
 * cached (see the ml/ classes); (2) `predictWakeWord` is split out — this class only produces
 * features, so [OwwRecognizer] can run multiple wake models over one feature computation.
 *
 * The [MelExtractor]/[Embedder] stages are injected (production: [MelSpectrogram]/[EmbeddingModel]) so the
 * buffering can be exercised by deterministic fakes in a pure-JVM test.
 *
 * NOT thread-safe: drive it from a single capture thread (as [OwwRecognizer] does).
 */
internal class AudioFeatures(
    private val mel: MelExtractor,
    private val embedding: Embedder,
) : AutoCloseable {

    private companion object {
        const val N_PREPARED_SAMPLES = 1280   // 80 ms @ 16 kHz — openWakeWord's step
        const val SAMPLE_RATE = 16_000
        const val MEL_SPECTROGRAM_MAX_LEN = 10 * 97
        const val FEATURE_BUFFER_MAX_LEN = 120
        const val WINDOW_SIZE = 76             // mel frames per embedding window
        const val STEP_SIZE = 8                // mel-frame stride between embeddings
        const val MEL_BINS = 32
        const val SCORE_WINDOW = 16            // embedding frames the wake model scores: [1,16,96]
        const val EMBED_DIM = 96               // embedding vector length
    }

    // Primitive fixed-capacity ring buffers (no per-step boxing/reallocation): raw samples for the mel
    // context, mel frames for the embedding windows, embedding frames for the score window. Capacities are
    // the old `takeLast` caps, so logical indexing (0 = oldest retained) is identical.
    private val rawRing = SampleRing(SAMPLE_RATE * 10)
    private var rawDataRemainder = FloatArray(0)
    private val melRing = RowRing(MEL_SPECTROGRAM_MAX_LEN)
    private val featureRing = RowRing(FEATURE_BUFFER_MAX_LEN)
    private var accumulatedSamples = 0

    init {
        seedMel()
        // Warm the feature buffer with embeddings of random audio (matches openWakeWord's init), so
        // getFeatures(16) always has 16 frames from the first real chunk.
        val random = FloatArray(SAMPLE_RATE * 4) { Random.nextFloat() * 2000f - 1000f }
        featureRing.addAll(embeddingsFor(random))
    }

    /** Seed the mel ring with [WINDOW_SIZE] frames so the first embedding window is full (openWakeWord's init state). */
    private fun seedMel() {
        repeat(WINDOW_SIZE) { melRing.add(FloatArray(MEL_BINS) { 1.0f }) }
    }

    /** Push one buffer of int16-magnitude float samples; returns the current [1,16,96] feature window. */
    fun accept(audioBuffer: FloatArray): Array<Array<FloatArray>> {
        streamingFeatures(audioBuffer)
        return lastFeatures(16)
    }

    /**
     * Drop buffered audio/features so audio captured before a pause can't linger into the next
     * session (called on each (re)start). Reuses the loaded sessions — no model reload.
     *
     * Re-seed the score window with zero embeddings (cheap) rather than re-running the init warm-up:
     * this runs on the capture thread every re-arm, and the first [SCORE_WINDOW] real frames replace
     * the zeros within ~1.3 s while scores ramp below threshold (gated by the consecutive-frame debounce),
     * so the priming content never matters — only its shape does.
     */
    fun reset() {
        rawRing.clear()
        rawDataRemainder = FloatArray(0)
        melRing.clear()
        seedMel()
        accumulatedSamples = 0
        featureRing.clear()
        repeat(SCORE_WINDOW) { featureRing.add(FloatArray(EMBED_DIM)) }
    }

    private fun streamingFeatures(audioBuffer: FloatArray) {
        accumulatedSamples = 0
        var buffer = audioBuffer
        if (rawDataRemainder.isNotEmpty()) {
            buffer = rawDataRemainder + audioBuffer
            rawDataRemainder = FloatArray(0)
        }

        if (accumulatedSamples + buffer.size >= N_PREPARED_SAMPLES) {
            val remainder = (accumulatedSamples + buffer.size) % N_PREPARED_SAMPLES
            if (remainder != 0) {
                rawRing.add(buffer, 0, buffer.size - remainder)
                accumulatedSamples += buffer.size - remainder
                rawDataRemainder = buffer.copyOfRange(buffer.size - remainder, buffer.size)
            } else {
                rawRing.add(buffer, 0, buffer.size)
                accumulatedSamples += buffer.size
            }
        } else {
            accumulatedSamples += buffer.size
            rawRing.add(buffer, 0, buffer.size)
        }

        if (accumulatedSamples >= N_PREPARED_SAMPLES && accumulatedSamples % N_PREPARED_SAMPLES == 0) {
            streamingMelSpectrogram(accumulatedSamples)
            for (i in (accumulatedSamples / N_PREPARED_SAMPLES) - 1 downTo 0) {
                val ndx = if (i == 0) melRing.size else melRing.size - STEP_SIZE * i
                val start = maxOf(0, ndx - WINDOW_SIZE)
                val window = Array(WINDOW_SIZE) { k ->
                    Array(MEL_BINS) { w ->
                        val src = start + k
                        FloatArray(1) { if (src < ndx && src < melRing.size) melRing.get(src)[w] else 0f }
                    }
                }
                val newFeatures = embedding.generate(arrayOf(window))
                featureRing.addAll(newFeatures) // ring auto-caps at FEATURE_BUFFER_MAX_LEN
            }
            accumulatedSamples = 0
        }
    }

    private fun streamingMelSpectrogram(nSamples: Int) {
        require(rawRing.size >= 400) { "need >=400 samples (25 ms) for melspectrogram" }
        val take = minOf(rawRing.size, nSamples + 480) // +480 (160*3) samples of context, like openWakeWord
        val temp = FloatArray(take)
        rawRing.copyLast(take, temp)
        melRing.addAll(mel.compute(temp)) // ring auto-caps at MEL_SPECTROGRAM_MAX_LEN
    }

    /** Embeddings for a whole clip (init warm-up only). */
    private fun embeddingsFor(audio: FloatArray): Array<FloatArray> {
        val spec = mel.compute(audio)
        val windows = ArrayList<Array<Array<FloatArray>>>()
        var i = 0
        while (i <= spec.size - WINDOW_SIZE) {
            val window = Array(WINDOW_SIZE) { k ->
                Array(MEL_BINS) { w -> FloatArray(1) { spec[i + k][w] } }
            }
            windows.add(window)
            i += STEP_SIZE
        }
        return if (windows.isEmpty()) arrayOf(FloatArray(96)) else embedding.generate(windows.toTypedArray())
    }

    /** The last [n] embeddings as a [1][n][96] tensor. */
    private fun lastFeatures(n: Int): Array<Array<FloatArray>> = arrayOf(featureRing.copyLast(n))

    override fun close() {
        (mel as? AutoCloseable)?.close()
        (embedding as? AutoCloseable)?.close()
    }

    /**
     * Fixed-capacity primitive FIFO of raw samples — one backing [FloatArray], O(1) append, O(n) tail read.
     * Replaces an `ArrayDeque<Float>` (boxed) whose only read was the last (nSamples+480) samples.
     */
    private class SampleRing(private val capacity: Int) {
        private val buf = FloatArray(capacity)
        private var head = 0 // next write position
        var size = 0; private set

        fun clear() { head = 0; size = 0 }

        fun add(data: FloatArray, from: Int, len: Int) {
            var s = from
            repeat(len) {
                buf[head] = data[s++]
                if (++head == capacity) head = 0
                if (size < capacity) size++
            }
        }

        /** Copy the most recent [n] samples (n ≤ size) into [out], oldest-of-those first. */
        fun copyLast(n: Int, out: FloatArray) {
            var idx = head - n
            if (idx < 0) idx += capacity
            for (i in 0 until n) {
                out[i] = buf[idx]
                if (++idx == capacity) idx = 0
            }
        }
    }

    /**
     * Fixed-capacity ring of frame rows ([FloatArray]) — O(1) append with automatic oldest-eviction, and
     * O(1) indexed read by logical position (0 = oldest retained). Replaces `Array += row` + `takeLast(cap)`,
     * whose grow-then-trim copied the whole row array every step.
     */
    private class RowRing(private val capacity: Int) {
        private val rows = arrayOfNulls<FloatArray>(capacity)
        private var head = 0
        var size = 0; private set

        fun clear() { head = 0; size = 0 }

        fun add(row: FloatArray) {
            rows[head] = row
            if (++head == capacity) head = 0
            if (size < capacity) size++
        }

        fun addAll(newRows: Array<FloatArray>) { for (r in newRows) add(r) }

        /** Row at logical index [i] in [0, size): 0 = oldest retained. */
        fun get(i: Int): FloatArray {
            var idx = (head - size + i) % capacity
            if (idx < 0) idx += capacity
            return rows[idx]!!
        }

        /** The most recent min(n, size) rows, oldest-first. */
        fun copyLast(n: Int): Array<FloatArray> {
            val m = if (n < size) n else size
            return Array(m) { get(size - m + it) }
        }
    }
}
