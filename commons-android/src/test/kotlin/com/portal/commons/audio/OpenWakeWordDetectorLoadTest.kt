package com.portal.commons.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.portal.commons.PcmCaptureFormat
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer
import java.util.ArrayDeque

/**
 * Integration tests that load the real bundled ONNX assets and exercise the inference pipeline on the JVM.
 * Validates load-time budget sizing and that silence does not score above threshold.
 */
class OpenWakeWordDetectorLoadTest {

    private val assetsDir = File("src/main/assets/oww")

    private fun assetBytes(name: String): ByteArray {
        val file = File(assetsDir, name)
        assumeTrue("Bundled asset missing: ${file.path}", file.isFile)
        return file.readBytes()
    }

    @Test fun bundledAssetsLoadWithinPreReadyBudget() {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }

        val startNs = System.nanoTime()
        val mel = env.createSession(assetBytes("melspectrogram.onnx"), opts)
        val emb = env.createSession(assetBytes("embedding_model.onnx"), opts)
        val jarvis = env.createSession(assetBytes("hey_jarvis_v0.1.onnx"), opts)
        val loadMs = (System.nanoTime() - startNs) / 1_000_000L

        mel.close()
        emb.close()
        jarvis.close()

        println("OWW bundled model load: ${loadMs}ms (budget ${OpenWakeWordDetector.PRE_READY_BUDGET_MS}ms)")
        assertTrue(
            "Measured load ${loadMs}ms exceeds PRE_READY_BUDGET_MS — increase budget or shrink assets",
            loadMs < OpenWakeWordDetector.PRE_READY_BUDGET_MS,
        )
        assertTrue(
            "Pre-ready ring buffer too small for measured load",
            OpenWakeWordDetector.preReadyMaxFrames() * PcmCaptureFormat.FRAME_MS >= loadMs,
        )
    }

    @Test fun silenceScoresBelowDefaultThreshold() {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
        val melSess = env.createSession(assetBytes("melspectrogram.onnx"), opts)
        val embSess = env.createSession(assetBytes("embedding_model.onnx"), opts)
        val clfSess = env.createSession(assetBytes("hey_jarvis_v0.1.onnx"), opts)

        val melIn = melSess.inputNames.first()
        val embIn = embSess.inputNames.first()
        val clfIn = clfSess.inputNames.first()

        val chunkSamples = PcmCaptureFormat.SAMPLE_RATE * OpenWakeWordDetector.INFERENCE_STEP_MS / 1000
        val silence = FloatArray(chunkSamples) { 0f }

        val melBuf = ArrayDeque<FloatArray>()
        val featBuf = ArrayDeque<FloatArray>()
        repeat(76) { melBuf.addLast(FloatArray(32) { 1f }) }
        repeat(16) { featBuf.addLast(FloatArray(96)) }

        var score = 0f
        repeat(40) {
            val melFrames = runMel(melSess, env, melIn, silence)
            val take = minOf(8, melFrames.size)
            for (i in melFrames.size - take until melFrames.size) {
                melBuf.addLast(melFrames[i])
                if (melBuf.size > 200) melBuf.removeFirst()
            }
            val emb = runEmb(embSess, env, embIn, flattenMel(melBuf))
            featBuf.addLast(emb)
            if (featBuf.size > 32) featBuf.removeFirst()
            if (featBuf.size >= 16) {
                score = runClf(clfSess, env, clfIn, flattenFeat(featBuf))
            }
        }

        melSess.close()
        embSess.close()
        clfSess.close()

        println("OWW silence score after warm-up: $score")
        assertTrue("Silence should not exceed default threshold", score < OpenWakeWordDetector.DEFAULT_SCORE_THRESHOLD)
    }

    private fun runMel(sess: OrtSession, env: OrtEnvironment, inName: String, samples: FloatArray): Array<FloatArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { t ->
            sess.run(mapOf(inName to t)).use { r ->
                val out = r[0] as OnnxTensor
                val shape = out.info.shape
                val T = if (shape.size >= 2) shape[shape.size - 2].toInt() else 0
                val fb = out.floatBuffer
                return Array(T) { row ->
                    FloatArray(32) { c -> fb.get(row * 32 + c) / 10f + 2f }
                }
            }
        }
    }

    private fun runEmb(sess: OrtSession, env: OrtEnvironment, inName: String, flat: FloatArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, 76, 32, 1)).use { t ->
            sess.run(mapOf(inName to t)).use { r ->
                val out = r[0] as OnnxTensor
                val fb = out.floatBuffer
                return FloatArray(96) { fb.get(it) }
            }
        }
    }

    private fun runClf(sess: OrtSession, env: OrtEnvironment, inName: String, flat: FloatArray): Float {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, 16, 96)).use { t ->
            sess.run(mapOf(inName to t)).use { r ->
                val out = r[0] as OnnxTensor
                return out.floatBuffer.get(0)
            }
        }
    }

    private fun flattenMel(melBuf: ArrayDeque<FloatArray>): FloatArray {
        val flat = FloatArray(76 * 32)
        var idx = 0
        val it = melBuf.descendingIterator()
        val newest = ArrayList<FloatArray>(76)
        while (newest.size < 76 && it.hasNext()) newest.add(it.next())
        newest.reverse()
        for (frame in newest) {
            System.arraycopy(frame, 0, flat, idx, 32)
            idx += 32
        }
        return flat
    }

    private fun flattenFeat(featBuf: ArrayDeque<FloatArray>): FloatArray {
        val flat = FloatArray(16 * 96)
        var idx = 0
        val it = featBuf.descendingIterator()
        val newest = ArrayList<FloatArray>(16)
        while (newest.size < 16 && it.hasNext()) newest.add(it.next())
        newest.reverse()
        for (v in newest) {
            System.arraycopy(v, 0, flat, idx, 96)
            idx += 96
        }
        return flat
    }
}
