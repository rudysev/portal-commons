package com.portal.commons.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.portal.commons.PcmCaptureFormat
import java.nio.FloatBuffer
import java.util.ArrayDeque

/**
 * **openWakeWord** [WakeDetector] — the community neural KWS run on-device with **ONNX Runtime** (a
 * self-contained native lib, **no Google Mobile Services**). Shared in `commons-android` so it is the wake
 * detector for **both** portal-wake (gen1 handset) and portal-assistant (gen2 foreground).
 *
 * Three-stage ONNX pipeline, exactly as the reference `openwakeword.Model`:
 *  1. **melspectrogram.onnx**: 16 kHz int16 PCM (as float) -> 32-bin mel frames, then the reference's
 *     `x/10 + 2` transform that aligns the ONNX melspec with Google's `speech_embedding` training.
 *  2. **embedding_model.onnx**: a 76-mel-frame window (Google speech_embedding) -> a 96-d feature vector.
 *  3. **&lt;phrase&gt;_v0.1.onnx** (one per wake word): the newest 16 feature vectors -> a probability in [0,1].
 *
 * The mel + embedding stages are **shared** across all active wake words; each [HeadConfig] adds a classifier
 * head. Every 80 ms step scores every head and fires independently when its score clears its threshold.
 *
 * Single-threaded by contract (the engine's capture thread): [start]/[accept]/[close] are never concurrent.
 */
class OpenWakeWordDetector(
    private val context: Context,
    private var heads: List<HeadConfig>,
    private val events: WakeDetector.Events,
    private val assetDir: String = ASSET_DIR,
) : WakeDetector {

    override val name: String = NAME

    private var env: OrtEnvironment? = null
    private var melSess: OrtSession? = null
    private var embSess: OrtSession? = null
    private var melInName = "input"
    private var embInName = "input_1"

    private data class ActiveHead(
        val wakeId: String,
        val session: OrtSession,
        val inName: String,
        val threshold: Float,
        var stepsSinceFire: Int = Int.MAX_VALUE,
    )

    private var activeHeads: List<ActiveHead> = emptyList()

    // Rolling raw-audio history (last RAW_CONTEXT samples) so each melspec has its STFT look-back.
    private val history = ShortArray(RAW_CONTEXT)
    private var historyLen = 0
    private var carry = ShortArray(0)

    private val melBuf = ArrayDeque<FloatArray>()
    private val featBuf = ArrayDeque<FloatArray>()

    init {
        val ok = runCatching { buildShared() && loadHeads(heads) }.getOrDefault(false)
        if (ok) events.onReady(NAME) else events.onUnavailable(NAME)
    }

    private fun buildShared(): Boolean {
        closeShared()
        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
        val mel = e.createSession(asset("$assetDir/$MEL_ASSET"), opts)
        val emb = e.createSession(asset("$assetDir/$EMB_ASSET"), opts)
        melInName = mel.inputNames.first()
        embInName = emb.inputNames.first()
        env = e; melSess = mel; embSess = emb
        return true
    }

    private fun loadHeads(configs: List<HeadConfig>): Boolean {
        closeHeads()
        if (configs.isEmpty()) return false
        val e = env ?: return false
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
        activeHeads = configs.map { cfg ->
            val sess = e.createSession(cfg.modelBytes, opts)
            ActiveHead(cfg.wakeId, sess, sess.inputNames.first(), cfg.threshold)
        }
        return true
    }

    override fun start() {
        historyLen = 0
        carry = ShortArray(0)
        activeHeads.forEach { it.stepsSinceFire = Int.MAX_VALUE }
        melBuf.clear()
        featBuf.clear()
        repeat(MEL_WINDOW) { melBuf.addLast(FloatArray(NUM_MEL) { 1f }) }
        val seed = embed(flattenNewestMelWindow())
        repeat(CLF_WINDOW) { featBuf.addLast(seed.copyOf()) }
    }

    override fun accept(buf: ByteArray, n: Int) {
        if (activeHeads.isEmpty()) return
        val incoming = pcm16ToShorts(buf, n)
        val samples = if (carry.isEmpty()) incoming else carry + incoming
        var off = 0
        while (samples.size - off >= CHUNK) {
            pushHistory(samples, off, CHUNK)
            step()
            off += CHUNK
        }
        carry = if (off == 0) samples else samples.copyOfRange(off, samples.size)
    }

    override fun updateWakeWords(words: List<WakeWord>) {
        val rebuilt = buildBundledHeads(context, words)
        if (rebuilt.isEmpty()) return
        runCatching { loadHeads(rebuilt) }
    }

    override fun close() {
        closeHeads()
        closeShared()
    }

    private fun closeHeads() {
        activeHeads.forEach { runCatching { it.session.close() } }
        activeHeads = emptyList()
    }

    private fun closeShared() {
        runCatching { melSess?.close() }
        runCatching { embSess?.close() }
        melSess = null; embSess = null
        env = null
    }

    // ---- one 80 ms step -----------------------------------------------------------------------------

    private fun step() {
        val frames = melspec()
        val take = minOf(MEL_PER_CHUNK, frames.size)
        for (i in frames.size - take until frames.size) {
            melBuf.addLast(frames[i])
            if (melBuf.size > MEL_MAX) melBuf.removeFirst()
        }
        val emb = embed(flattenNewestMelWindow())
        featBuf.addLast(emb)
        if (featBuf.size > FEAT_MAX) featBuf.removeFirst()
        if (featBuf.size < CLF_WINDOW) return

        val flat = flattenNewestFeatureWindow()
        for (head in activeHeads) {
            val score = classify(head, flat)
            head.stepsSinceFire = if (head.stepsSinceFire == Int.MAX_VALUE) head.stepsSinceFire else head.stepsSinceFire + 1
            if (score >= head.threshold && head.stepsSinceFire >= REFRACTORY_STEPS) {
                head.stepsSinceFire = 0
                events.onWake(NAME, head.wakeId, "oww p=${"%.3f".format(score)}")
            }
        }
    }

    // ---- ONNX stages --------------------------------------------------------------------------------

    private fun melspec(): Array<FloatArray> {
        val e = env ?: return emptyArray()
        val len = historyLen
        val f = FloatArray(len)
        val startIdx = RAW_CONTEXT - len
        for (i in 0 until len) f[i] = history[startIdx + i].toFloat()
        OnnxTensor.createTensor(e, FloatBuffer.wrap(f), longArrayOf(1, len.toLong())).use { t ->
            melSess!!.run(mapOf(melInName to t)).use { r ->
                val out = r[0] as OnnxTensor
                val T = melTimeSteps(out.info.shape)
                val fb = out.floatBuffer
                return Array(T) { row ->
                    FloatArray(NUM_MEL) { c -> fb.get(row * NUM_MEL + c) / 10f + 2f }
                }
            }
        }
    }

    private fun embed(flat: FloatArray): FloatArray {
        val e = env ?: return FloatArray(EMB_DIM)
        OnnxTensor.createTensor(e, FloatBuffer.wrap(flat), longArrayOf(1, MEL_WINDOW.toLong(), NUM_MEL.toLong(), 1))
            .use { t ->
                embSess!!.run(mapOf(embInName to t)).use { r ->
                    val out = r[0] as OnnxTensor
                    val fb = out.floatBuffer
                    return FloatArray(EMB_DIM) { fb.get(it) }
                }
            }
    }

    private fun classify(head: ActiveHead, flat: FloatArray): Float {
        val e = env ?: return 0f
        OnnxTensor.createTensor(e, FloatBuffer.wrap(flat), longArrayOf(1, CLF_WINDOW.toLong(), EMB_DIM.toLong()))
            .use { t ->
                head.session.run(mapOf(head.inName to t)).use { r ->
                    val out = r[0] as OnnxTensor
                    return out.floatBuffer.get(0)
                }
            }
    }

    private fun flattenNewestMelWindow(): FloatArray {
        val flat = FloatArray(MEL_WINDOW * NUM_MEL)
        var idx = 0
        val it = melBuf.descendingIterator()
        val newest = ArrayList<FloatArray>(MEL_WINDOW)
        while (newest.size < MEL_WINDOW && it.hasNext()) newest.add(it.next())
        newest.reverse()
        for (frame in newest) { System.arraycopy(frame, 0, flat, idx, NUM_MEL); idx += NUM_MEL }
        return flat
    }

    private fun flattenNewestFeatureWindow(): FloatArray {
        val flat = FloatArray(CLF_WINDOW * EMB_DIM)
        var idx = 0
        val it = featBuf.descendingIterator()
        val newest = ArrayList<FloatArray>(CLF_WINDOW)
        while (newest.size < CLF_WINDOW && it.hasNext()) newest.add(it.next())
        newest.reverse()
        for (v in newest) { System.arraycopy(v, 0, flat, idx, EMB_DIM); idx += EMB_DIM }
        return flat
    }

    private fun pushHistory(src: ShortArray, off: Int, len: Int) {
        if (len >= RAW_CONTEXT) {
            System.arraycopy(src, off + len - RAW_CONTEXT, history, 0, RAW_CONTEXT)
            historyLen = RAW_CONTEXT
            return
        }
        val keep = minOf(historyLen, RAW_CONTEXT - len)
        if (keep > 0) System.arraycopy(history, historyLen - keep, history, 0, keep)
        System.arraycopy(src, off, history, keep, len)
        historyLen = keep + len
    }

    private fun asset(name: String): ByteArray = context.assets.open(name).use { it.readBytes() }

    /** One wake-word classifier: the wake id it fires and the ONNX bytes for its phrase model. */
    data class HeadConfig(
        val wakeId: String,
        val modelBytes: ByteArray,
        val threshold: Float,
    ) {
        override fun equals(other: Any?): Boolean =
            other is HeadConfig && wakeId == other.wakeId && threshold == other.threshold &&
                modelBytes.contentEquals(other.modelBytes)

        override fun hashCode(): Int = wakeId.hashCode()
    }

    companion object {
        const val NAME = "oww"
        const val ASSET_DIR = "oww"

        const val JARVIS_ASSET = "hey_jarvis_v0.1.onnx"
        const val ALEXA_ASSET = "alexa_v0.1.onnx"

        const val DEFAULT_THRESHOLD = 0.5f

        internal fun melTimeSteps(shape: LongArray): Int = if (shape.size >= 2) shape[shape.size - 2].toInt() else 0

        internal fun pcm16ToShorts(buf: ByteArray, n: Int): ShortArray {
            val out = ShortArray(n / 2)
            var bi = 0
            for (i in out.indices) {
                val lo = buf[bi].toInt() and 0xFF
                val hi = buf[bi + 1].toInt()
                out[i] = ((hi shl 8) or lo).toShort()
                bi += 2
            }
            return out
        }

        /** Built-in bundled phrase model for a wake word, or null when none exists. Pure. */
        fun builtinAssetFor(word: WakeWord): String? = when (word.id) {
            "jarvis" -> JARVIS_ASSET
            "alexa" -> ALEXA_ASSET
            else -> null
        }

        /** Load a bundled classifier model for [word], or null when no built-in model exists. */
        fun loadBuiltinModel(context: Context, word: WakeWord): ByteArray? {
            val asset = builtinAssetFor(word) ?: return null
            return runCatching {
                context.assets.open("$ASSET_DIR/$asset").use { it.readBytes() }
            }.getOrNull()
        }

        /** Build head configs from bundled assets for each word that has a model. */
        fun buildBundledHeads(context: Context, words: List<WakeWord>): List<HeadConfig> = buildList {
            for (word in words) {
                val bytes = loadBuiltinModel(context, word) ?: continue
                val threshold = word.minConf.toFloat().coerceIn(0f, 1f)
                add(HeadConfig(word.id, bytes, threshold))
            }
        }

        /** True when the shared mel + embedding assets are bundled. */
        fun assetsPresent(context: Context): Boolean = runCatching {
            val files = context.assets.list(ASSET_DIR)?.toSet() ?: emptySet()
            MEL_ASSET in files && EMB_ASSET in files
        }.getOrDefault(false)

        /** Factory with explicit heads (portal-wake, including plugin models). */
        fun factory(heads: List<HeadConfig>): WakeDetector.Factory =
            WakeDetector.Factory { context, _, events ->
                OpenWakeWordDetector(context, heads, events)
            }

        /** Factory that resolves bundled models from the wake word list (portal-assistant). */
        fun factory(): WakeDetector.Factory =
            WakeDetector.Factory { context, words, events ->
                OpenWakeWordDetector(context, buildBundledHeads(context, words), events)
            }

        private const val MEL_ASSET = "melspectrogram.onnx"
        private const val EMB_ASSET = "embedding_model.onnx"

        private const val NUM_MEL = 32
        private const val MEL_WINDOW = 76
        private const val EMB_DIM = 96
        private const val CLF_WINDOW = 16

        private const val CHUNK = PcmCaptureFormat.SAMPLE_RATE * 80 / 1000
        private const val RAW_CONTEXT = CHUNK + 160 * 3
        private const val MEL_PER_CHUNK = 8

        private const val MEL_MAX = 200
        private const val FEAT_MAX = 32
        private const val REFRACTORY_STEPS = 20
    }
}
