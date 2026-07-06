package com.portal.commons.audio.oww

/**
 * The two ONNX stages [AudioFeatures] depends on, expressed as interfaces so the feature buffering can be
 * driven by deterministic fakes in a pure-JVM unit test (no ONNX Runtime on the test classpath). Production
 * implementations are [MelSpectrogram] and [EmbeddingModel].
 */
internal interface MelExtractor {
    /** Raw 16 kHz int16-magnitude audio → mel frames [frames][32]. */
    fun compute(audioSamples: FloatArray): Array<FloatArray>
}

internal interface Embedder {
    /** A batch of 76-frame mel windows [batch][76][32][1] → 96-dim embeddings [batch][96]. */
    fun generate(input: Array<Array<Array<FloatArray>>>): Array<FloatArray>
}
