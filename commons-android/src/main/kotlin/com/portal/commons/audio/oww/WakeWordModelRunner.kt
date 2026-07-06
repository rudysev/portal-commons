package com.portal.commons.audio.oww

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager

/**
 * Runs a single openWakeWord wake-word model: last-16 embeddings [1,16,96] → one score in [0,1].
 *
 * Adapted from Re-MENTIA/openwakeword-android-kt (Apache-2.0). Session is created once and reused.
 */
internal class WakeWordModelRunner(
    assetManager: AssetManager,
    modelPath: String,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = assetManager.open(modelPath).use { input ->
        env.createSession(input.readBytes())
    }
    private val inputName: String = session.inputNames.first()

    /** @param features [1][16][96] → @return score in [0,1] */
    fun score(features: Array<Array<FloatArray>>): Float {
        var inputTensor: OnnxTensor? = null
        return try {
            inputTensor = OnnxTensor.createTensor(env, features)
            session.run(mapOf(inputName to inputTensor)).use { outputs ->
                @Suppress("UNCHECKED_CAST")
                val result = outputs[0].value as Array<FloatArray>
                result[0][0]
            }
        } finally {
            inputTensor?.close()
        }
    }

    override fun close() {
        session.close()
    }
}
