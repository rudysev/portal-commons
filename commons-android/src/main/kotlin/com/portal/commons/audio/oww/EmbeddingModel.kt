package com.portal.commons.audio.oww

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager

/**
 * openWakeWord embedding stage: a batch of 76-frame mel windows → 96-dim speech embeddings.
 * Input shape [batch, 76, 32, 1] → output squeezed from [batch,1,1,96] to [batch,96].
 *
 * Adapted from Re-MENTIA/openwakeword-android-kt (Apache-2.0). Change from the reference: the
 * `OrtSession` is created **once** and reused (the reference rebuilt it every call).
 */
internal class EmbeddingModel(
    assetManager: AssetManager,
    modelPath: String,
) : Embedder, AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = assetManager.open(modelPath).use { input ->
        env.createSession(input.readBytes())
    }
    private val inputName: String = session.inputNames.first()

    /** @param input [batch][76][32][1] → @return [batch][96] */
    override fun generate(input: Array<Array<Array<FloatArray>>>): Array<FloatArray> {
        var inputTensor: OnnxTensor? = null
        return try {
            inputTensor = OnnxTensor.createTensor(env, input)
            session.run(mapOf(inputName to inputTensor)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val raw = results[0].value as Array<Array<Array<FloatArray>>>
                // [batch,1,1,96] → [batch,96]
                Array(raw.size) { i -> raw[i][0][0].copyOf() }
            }
        } finally {
            inputTensor?.close()
        }
    }

    override fun close() {
        session.close()
    }
}
