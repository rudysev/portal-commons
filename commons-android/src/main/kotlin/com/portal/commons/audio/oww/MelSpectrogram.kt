package com.portal.commons.audio.oww

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import java.nio.FloatBuffer

/**
 * openWakeWord melspectrogram stage: raw 16 kHz audio → mel frames (32 bins), with openWakeWord's
 * `x/10 + 2` transform applied.
 *
 * Adapted from Re-MENTIA/openwakeword-android-kt (Apache-2.0) and dscripka/openWakeWord (Apache-2.0).
 * Change from the reference: the `OrtSession` is created **once** and reused — the reference recreated
 * it on every call, which is far too slow for a continuously-running detector.
 *
 * Input samples are float **int16-magnitude** values (e.g. -32768..32767), NOT normalised to [-1,1] —
 * the ONNX melspectrogram was traced on int16-valued audio.
 */
internal class MelSpectrogram(
    assetManager: AssetManager,
    modelPath: String,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = assetManager.open(modelPath).use { input ->
        env.createSession(input.readBytes())
    }
    private val inputName: String = session.inputNames.first()

    /** Compute mel frames for [audioSamples]; returns [frames][32]. */
    fun compute(audioSamples: FloatArray): Array<FloatArray> {
        var inputTensor: OnnxTensor? = null
        return try {
            inputTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(audioSamples),
                longArrayOf(1L, audioSamples.size.toLong()),
            )
            session.run(mapOf(inputName to inputTensor)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val out = results[0].value as Array<Array<Array<FloatArray>>>
                // squeeze [time,1,frames,32] → [frames,32], then x/10 + 2
                val frames = out[0][0]
                Array(frames.size) { i ->
                    val row = frames[i]
                    FloatArray(row.size) { j -> row[j] / 10.0f + 2.0f }
                }
            }
        } finally {
            inputTensor?.close()
        }
    }

    override fun close() {
        session.close()
    }
}
