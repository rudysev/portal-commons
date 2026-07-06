package com.portal.commons.audio.oww

import android.content.res.AssetManager
import java.util.ArrayDeque
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
 * NOT thread-safe: drive it from a single capture thread (as [OwwRecognizer] does).
 */
internal class AudioFeatures(
    assetManager: AssetManager,
    melModelPath: String,
    embeddingModelPath: String,
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

    private val mel = MelSpectrogram(assetManager, melModelPath)
    private val embedding = EmbeddingModel(assetManager, embeddingModelPath)

    private var featureBuffer: Array<FloatArray>
    private val rawDataBuffer = ArrayDeque<Float>(SAMPLE_RATE * 10)
    private var rawDataRemainder = FloatArray(0)
    private var melBuffer: Array<FloatArray> = Array(WINDOW_SIZE) { FloatArray(MEL_BINS) { 1.0f } }
    private var accumulatedSamples = 0

    init {
        // Warm the feature buffer with embeddings of random audio (matches openWakeWord's init), so
        // getFeatures(16) always has 16 frames from the first real chunk.
        val random = FloatArray(SAMPLE_RATE * 4) { Random.nextFloat() * 2000f - 1000f }
        featureBuffer = embeddingsFor(random)
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
        rawDataBuffer.clear()
        rawDataRemainder = FloatArray(0)
        melBuffer = Array(WINDOW_SIZE) { FloatArray(MEL_BINS) { 1.0f } }
        accumulatedSamples = 0
        featureBuffer = Array(SCORE_WINDOW) { FloatArray(EMBED_DIM) }
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
                val evenChunks = buffer.copyOfRange(0, buffer.size - remainder)
                bufferRawData(evenChunks)
                accumulatedSamples += evenChunks.size
                rawDataRemainder = buffer.copyOfRange(buffer.size - remainder, buffer.size)
            } else {
                bufferRawData(buffer)
                accumulatedSamples += buffer.size
            }
        } else {
            accumulatedSamples += buffer.size
            bufferRawData(buffer)
        }

        if (accumulatedSamples >= N_PREPARED_SAMPLES && accumulatedSamples % N_PREPARED_SAMPLES == 0) {
            streamingMelSpectrogram(accumulatedSamples)
            for (i in (accumulatedSamples / N_PREPARED_SAMPLES) - 1 downTo 0) {
                val ndx = if (i == 0) melBuffer.size else melBuffer.size - STEP_SIZE * i
                val start = maxOf(0, ndx - WINDOW_SIZE)
                val window = Array(WINDOW_SIZE) { k ->
                    Array(MEL_BINS) { w ->
                        val src = start + k
                        FloatArray(1) { if (src < ndx && src < melBuffer.size) melBuffer[src][w] else 0f }
                    }
                }
                val newFeatures = embedding.generate(arrayOf(window))
                featureBuffer += newFeatures
            }
            accumulatedSamples = 0
        }

        if (featureBuffer.size > FEATURE_BUFFER_MAX_LEN) {
            featureBuffer = featureBuffer.takeLast(FEATURE_BUFFER_MAX_LEN).toTypedArray()
        }
    }

    private fun bufferRawData(data: FloatArray) {
        while (rawDataBuffer.size + data.size > SAMPLE_RATE * 10) rawDataBuffer.poll()
        for (v in data) rawDataBuffer.offer(v)
    }

    private fun streamingMelSpectrogram(nSamples: Int) {
        require(rawDataBuffer.size >= 400) { "need >=400 samples (25 ms) for melspectrogram" }
        val list = rawDataBuffer.toArray()
        val take = minOf(list.size, nSamples + 480) // +480 (160*3) samples of context, like openWakeWord
        val temp = FloatArray(take) { list[list.size - take + it] as Float }
        melBuffer += mel.compute(temp)
        if (melBuffer.size > MEL_SPECTROGRAM_MAX_LEN) {
            melBuffer = melBuffer.takeLast(MEL_SPECTROGRAM_MAX_LEN).toTypedArray()
        }
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
    private fun lastFeatures(n: Int): Array<Array<FloatArray>> {
        val start = maxOf(0, featureBuffer.size - n)
        return arrayOf(featureBuffer.copyOfRange(start, featureBuffer.size))
    }

    override fun close() {
        mel.close()
        embedding.close()
    }
}
