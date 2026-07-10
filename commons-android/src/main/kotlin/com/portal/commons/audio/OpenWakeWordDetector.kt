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
 * The mel + embedding stages are **shared**; each [PhraseClassifierConfig] adds a per-phrase ONNX classifier
 * that scores the shared embedding stream. Every 80 ms step scores every loaded classifier independently.
 *
 * **Loading.** ONNX sessions are built on a background thread so service/activity startup is not blocked.
 * Frames are dropped until loading finishes; [Events.onReady] / [Events.onUnavailable] fire from the loader.
 *
 * **Threading.** [start]/[accept] run on the engine's capture thread. [close] joins the loader then tears
 * down native sessions.
 */
class OpenWakeWordDetector private constructor(
    private val context: Context,
    private val modelSource: ModelSource,
    private var phraseConfigs: List<PhraseClassifierConfig>,
    private val bundledWords: List<WakeWord>,
    private val events: WakeDetector.Events,
    private val assetDir: String = ASSET_DIR,
) : WakeDetector {

    override val id: String = ID

    private var env: OrtEnvironment? = null
    private var melSess: OrtSession? = null
    private var embSess: OrtSession? = null
    private var melInName = "input"
    private var embInName = "input_1"

    /** A loaded per-phrase ONNX classifier session (the runtime counterpart of [PhraseClassifierConfig]). */
    private data class LoadedClassifier(
        val wakeId: String,
        val session: OrtSession,
        val inName: String,
        val scoreThreshold: Float,
        var stepsSinceFire: Int = Int.MAX_VALUE,
    )

    private var loadedClassifiers: List<LoadedClassifier> = emptyList()

    @Volatile
    private var ready = false

    @Volatile
    private var closed = false

    private val loader = Thread(::loadInBackground, "oww-loader")

    private val history = ShortArray(RAW_CONTEXT)
    private var historyLen = 0
    private var carry = ShortArray(0)

    private val melBuf = ArrayDeque<FloatArray>()
    private val featBuf = ArrayDeque<FloatArray>()
    private val lastDiagMs = HashMap<String, Long>()

    init {
        loader.start()
    }

    private fun loadInBackground() {
        val toInstall = when (modelSource) {
            ModelSource.BUNDLED -> buildBundledPhraseConfigs(context, bundledWords)
            ModelSource.EXPLICIT -> phraseConfigs
        }
        if (closed) return
        val ok = runCatching { buildShared() && installClassifiers(toInstall) }.getOrDefault(false)
        if (closed) {
            closeClassifiers()
            closeShared()
            return
        }
        ready = ok
        if (ok) events.onReady(ID) else events.onUnavailable(ID)
    }

    private fun buildShared(): Boolean {
        closeShared()
        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
        val mel = e.createSession(asset("$assetDir/$MEL_ASSET"), opts)
        val emb = e.createSession(asset("$assetDir/$EMB_ASSET"), opts)
        melInName = mel.inputNames.first()
        embInName = emb.inputNames.first()
        env = e
        melSess = mel
        embSess = emb
        return true
    }

    /**
     * Load ONNX classifier sessions for each phrase config. Builds the full new set first; on any failure
     * the previous [loadedClassifiers] are left untouched.
     */
    private fun installClassifiers(configs: List<PhraseClassifierConfig>): Boolean {
        if (configs.isEmpty()) return false
        val e = env ?: return false
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
        val built = mutableListOf<LoadedClassifier>()
        try {
            for (cfg in configs) {
                val sess = e.createSession(cfg.modelBytes, opts)
                built.add(
                    LoadedClassifier(
                        wakeId = cfg.wakeId,
                        session = sess,
                        inName = sess.inputNames.first(),
                        scoreThreshold = cfg.scoreThreshold,
                    ),
                )
            }
        } catch (_: Throwable) {
            built.forEach { runCatching { it.session.close() } }
            return false
        }
        closeClassifiers()
        loadedClassifiers = built
        phraseConfigs = configs
        return true
    }

    override fun start() {
        historyLen = 0
        carry = ShortArray(0)
        loadedClassifiers.forEach { it.stepsSinceFire = Int.MAX_VALUE }
        melBuf.clear()
        featBuf.clear()
        lastDiagMs.clear()
        repeat(MEL_WINDOW) { melBuf.addLast(FloatArray(NUM_MEL) { 1f }) }
        val seed = embed(flattenNewestMelWindow())
        repeat(CLF_WINDOW) { featBuf.addLast(seed.copyOf()) }
    }

    override fun accept(buf: ByteArray, n: Int) {
        if (!ready || loadedClassifiers.isEmpty()) return
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

    /**
     * Hot-swap bundled phrase models from [words]. Only for detectors created via [factory] (no-arg).
     * Detectors with explicit [PhraseClassifierConfig] ignore this — rebuild the engine or call
     * [updatePhraseModels].
     */
    override fun updateWakeWords(words: List<WakeWord>) {
        if (modelSource != ModelSource.BUNDLED) return
        if (!ready) return
        val configs = buildBundledPhraseConfigs(context, words)
        if (configs.isEmpty()) {
            events.onUnavailable(ID)
            return
        }
        if (!installClassifiers(configs)) {
            events.onDiagnostic(ID, "oww: bundled phrase model swap failed — keeping previous models")
        }
    }

    /** Hot-swap explicit phrase classifier configs (portal-wake plugin + bundled mix). */
    fun updatePhraseModels(configs: List<PhraseClassifierConfig>) {
        if (modelSource != ModelSource.EXPLICIT) return
        if (!ready) return
        if (configs.isEmpty()) {
            closeClassifiers()
            events.onUnavailable(ID)
            return
        }
        if (!installClassifiers(configs)) {
            events.onDiagnostic(ID, "oww: phrase model swap failed — keeping previous models")
        }
    }

    override fun close() {
        closed = true
        loader.join(LOADER_JOIN_MS)
        closeClassifiers()
        closeShared()
        ready = false
    }

    private fun closeClassifiers() {
        loadedClassifiers.forEach { runCatching { it.session.close() } }
        loadedClassifiers = emptyList()
    }

    private fun closeShared() {
        runCatching { melSess?.close() }
        runCatching { embSess?.close() }
        melSess = null
        embSess = null
        env = null
    }

    private fun step() {
        val nowMs = System.currentTimeMillis()
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
        for (classifier in loadedClassifiers) {
            val score = classify(classifier, flat)
            classifier.stepsSinceFire =
                if (classifier.stepsSinceFire == Int.MAX_VALUE) {
                    classifier.stepsSinceFire
                } else {
                    classifier.stepsSinceFire + 1
                }
            maybeLogNearMiss(classifier.wakeId, score, classifier.scoreThreshold, nowMs)
            if (score >= classifier.scoreThreshold && classifier.stepsSinceFire >= REFRACTORY_STEPS) {
                classifier.stepsSinceFire = 0
                events.onWake(WakeEvent(ID, classifier.wakeId, "score=${"%.3f".format(score)}"))
            }
        }
    }

    private fun maybeLogNearMiss(wakeId: String, score: Float, threshold: Float, nowMs: Long) {
        if (score < threshold - NEAR_MISS_MARGIN || score >= threshold) return
        val last = lastDiagMs[wakeId] ?: 0L
        if (nowMs - last < DIAG_MIN_INTERVAL_MS) return
        lastDiagMs[wakeId] = nowMs
        events.onDiagnostic(
            ID,
            "oww near-miss $wakeId score=${"%.3f".format(score)} threshold=${"%.3f".format(threshold)}",
        )
    }

    private fun melspec(): Array<FloatArray> {
        val e = env ?: return emptyArray()
        val len = historyLen
        val f = FloatArray(len)
        val startIdx = RAW_CONTEXT - len
        for (i in 0 until len) f[i] = history[startIdx + i].toFloat()
        OnnxTensor.createTensor(e, FloatBuffer.wrap(f), longArrayOf(1, len.toLong())).use { t ->
            melSess!!.run(mapOf(melInName to t)).use { r ->
                val out = r[0] as OnnxTensor
                val timeSteps = melTimeSteps(out.info.shape)
                val fb = out.floatBuffer
                return Array(timeSteps) { row ->
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

    private fun classify(classifier: LoadedClassifier, flat: FloatArray): Float {
        val e = env ?: return 0f
        OnnxTensor.createTensor(e, FloatBuffer.wrap(flat), longArrayOf(1, CLF_WINDOW.toLong(), EMB_DIM.toLong()))
            .use { t ->
                classifier.session.run(mapOf(classifier.inName to t)).use { r ->
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
        for (frame in newest) {
            System.arraycopy(frame, 0, flat, idx, NUM_MEL)
            idx += NUM_MEL
        }
        return flat
    }

    private fun flattenNewestFeatureWindow(): FloatArray {
        val flat = FloatArray(CLF_WINDOW * EMB_DIM)
        var idx = 0
        val it = featBuf.descendingIterator()
        val newest = ArrayList<FloatArray>(CLF_WINDOW)
        while (newest.size < CLF_WINDOW && it.hasNext()) newest.add(it.next())
        newest.reverse()
        for (v in newest) {
            System.arraycopy(v, 0, flat, idx, EMB_DIM)
            idx += EMB_DIM
        }
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

    /**
     * Static description of one per-phrase ONNX classifier: which wake [wakeId] it fires for, the model
     * bytes, and the [scoreThreshold] (openWakeWord probability in [0, 1] — same scale as
     * [WakeWord.minConf] / `com.portal.wake.min_confidence`).
     */
    data class PhraseClassifierConfig(
        val wakeId: String,
        val modelBytes: ByteArray,
        val scoreThreshold: Float,
    ) {
        override fun equals(other: Any?): Boolean = other is PhraseClassifierConfig &&
            wakeId == other.wakeId &&
            scoreThreshold == other.scoreThreshold &&
            modelBytes.contentEquals(other.modelBytes)

        override fun hashCode(): Int = 31 * wakeId.hashCode() + scoreThreshold.hashCode() + modelBytes.contentHashCode()
    }

    /** Whether phrase models are resolved from bundled assets or supplied explicitly at construction. */
    enum class ModelSource { BUNDLED, EXPLICIT }

    companion object {
        const val ID = "oww"
        const val ASSET_DIR = "oww"

        const val JARVIS_ASSET = "hey_jarvis_v0.1.onnx"
        const val ALEXA_ASSET = "alexa_v0.1.onnx"

        const val DEFAULT_SCORE_THRESHOLD = 0.5f

        internal const val NEAR_MISS_MARGIN = 0.15f
        internal const val DIAG_MIN_INTERVAL_MS = 500L

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

        fun builtinAssetFor(word: WakeWord): String? = when (word.id) {
            "jarvis" -> JARVIS_ASSET
            "alexa" -> ALEXA_ASSET
            else -> null
        }

        fun loadBuiltinModel(context: Context, word: WakeWord): ByteArray? {
            val asset = builtinAssetFor(word) ?: return null
            return runCatching {
                context.assets.open("$ASSET_DIR/$asset").use { it.readBytes() }
            }.getOrNull()
        }

        fun buildBundledPhraseConfigs(context: Context, words: List<WakeWord>): List<PhraseClassifierConfig> = buildList {
            for (word in words) {
                val bytes = loadBuiltinModel(context, word) ?: continue
                val threshold = word.minConf.toFloat().coerceIn(0f, 1f)
                add(PhraseClassifierConfig(word.id, bytes, threshold))
            }
        }

        fun assetsPresent(context: Context): Boolean = runCatching {
            val files = context.assets.list(ASSET_DIR)?.toSet() ?: emptySet()
            MEL_ASSET in files && EMB_ASSET in files
        }.getOrDefault(false)

        /** Factory with explicit phrase configs (portal-wake, including plugin models). */
        fun factory(phraseConfigs: List<PhraseClassifierConfig>): WakeDetector.Factory = WakeDetector.Factory { host ->
            OpenWakeWordDetector(host.context, ModelSource.EXPLICIT, phraseConfigs, host.wakeWords, host.events)
        }

        /** Factory that resolves bundled phrase models from the wake word list (portal-assistant). */
        fun factory(): WakeDetector.Factory = WakeDetector.Factory { host ->
            OpenWakeWordDetector(host.context, ModelSource.BUNDLED, emptyList(), host.wakeWords, host.events)
        }

        /**
         * Pure swap helper: if [failed], [built] is discarded and [current] is kept; otherwise [current]
         * is discarded and [built] is returned.
         */
        internal fun <T> swapOnSuccess(
            current: List<T>,
            built: List<T>,
            onDiscard: (List<T>) -> Unit,
            failed: Boolean,
        ): List<T> {
            if (failed) {
                onDiscard(built)
                return current
            }
            onDiscard(current)
            return built
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
        private const val LOADER_JOIN_MS = 30_000L
    }
}
