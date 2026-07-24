package com.portal.commons.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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
 * that scores the shared embedding stream. Every [INFERENCE_STEP_MS] step scores every loaded classifier
 * independently.
 *
 * **Inference stride vs capture frames.** [PcmCaptureFormat.FRAME_MS] is 100 ms (what [WakeMicEngine] delivers),
 * but openWakeWord's reference `frame_size` is 1280 samples @ 16 kHz = **80 ms**. Each capture frame is
 * sliced into 80 ms chunks with carry — do not conflate the two.
 *
 * **Loading.** ONNX sessions are built on [OwwModelThread] so service/activity startup is not blocked.
 * While loading, frames are ring-buffered (see [preReadyMaxFrames]); [Events.onReady] / [Events.onUnavailable]
 * fire from the model thread.
 *
 * **Threading.** [start]/[accept] run on the engine's capture thread. All session open/close/swap runs on
 * the model thread; capture reads volatile snapshots only. [close] joins the model thread then tears down.
 */
class OpenWakeWordDetector private constructor(
    private val host: WakeDetector.Host,
    private val modelSource: ModelSource,
    private var phraseConfigs: List<PhraseClassifierConfig>,
    private val assetDir: String = ASSET_DIR,
    private val testHooks: TestHooks? = null,
) : WakeDetector {

    override val id: String = ID

    private val context get() = host.context
    private val events get() = host.events
    private val handoffCooldown get() = host.handoffCooldown

    /** Shared mel + embedding sessions — written on the model thread, read on the capture thread after [ready]. */
    private data class SharedModels(
        val env: OrtEnvironment,
        val melSess: OrtSession,
        val embSess: OrtSession,
        val melInName: String,
        val embInName: String,
    )

    /** A loaded per-phrase ONNX classifier session (the runtime counterpart of [PhraseClassifierConfig]). */
    internal class LoadedClassifierHandle(
        val wakeId: String,
        val session: OrtSession,
        val inName: String,
        val scoreThreshold: Float,
        var stepsSinceFire: Int = Int.MAX_VALUE,
        private val onClose: ((wakeId: String) -> Unit)? = null,
    ) {
        @Volatile var closed = false

        fun close() {
            if (closed) return
            closed = true
            runCatching { session.close() }
            onClose?.invoke(wakeId)
        }
    }

    @Volatile private var shared: SharedModels? = null

    @Volatile private var loadedClassifiers: List<LoadedClassifierHandle> = emptyList()

    @Volatile private var ready = false

    @Volatile private var closed = false

    /** True once [loadInitial] has finished (ready or unavailable) — gates hot-swap vs stash-only. */
    @Volatile private var initialLoadFinished = false
    private var wasReady = false
    private var streamingSeeded = false

    /** Latest bundled wake set from [updateWakeWords]; falls back to [WakeDetector.Host.wakeWords]. */
    @Volatile private var latestBundledWords: List<WakeWord>? = null

    /** Latest explicit configs from [updatePhraseModels]; falls back to the construction-time list. */
    @Volatile private var latestExplicitConfigs: List<PhraseClassifierConfig>? = null

    private val modelThread = OwwModelThread()
    private val captureGuard = OwwCaptureGuard(modelThread)
    private val preReadyBuffer = PcmRingBuffer(preReadyMaxFrames())

    private val history = ShortArray(RAW_CONTEXT)
    private var historyLen = 0
    private var carry = ShortArray(0)

    private val melBuf = ArrayDeque<FloatArray>()
    private val featBuf = ArrayDeque<FloatArray>()
    private val lastDiagMs = HashMap<String, Long>()

    init {
        modelThread.submit { loadInitial() }
    }

    private fun bundledWords(): List<WakeWord> = latestBundledWords ?: host.wakeWords

    private fun explicitConfigs(): List<PhraseClassifierConfig> = latestExplicitConfigs ?: phraseConfigs

    private fun loadInitial() {
        if (closed) return
        try {
            val okShared = runCatching { buildShared() }.getOrDefault(false)
            if (!okShared || closed) {
                tearDownOnModelThread()
                events.onUnavailable(ID)
                return
            }
            // Read the wake/config set as late as possible so a pre-ready hot-swap is not lost.
            testHooks?.beforeResolveConfigs?.invoke()
            if (closed) {
                tearDownOnModelThread()
                return
            }
            val toInstall = when (modelSource) {
                ModelSource.BUNDLED -> buildBundledPhraseConfigs(context, bundledWords())
                ModelSource.EXPLICIT -> explicitConfigs()
            }
            val ok = runCatching { installClassifiersOnModelThread(toInstall) }.getOrDefault(false)
            if (closed) {
                tearDownOnModelThread()
                return
            }
            publishReady(ok)
        } finally {
            initialLoadFinished = true
        }
    }

    private fun publishReady(ok: Boolean) {
        if (closed) {
            tearDownOnModelThread()
            ready = false
            return
        }
        testHooks?.beforePublishReady?.invoke()
        if (closed) {
            tearDownOnModelThread()
            ready = false
            return
        }
        ready = ok
        testHooks?.readyEvents?.add(ok)
        if (ok) events.onReady(ID) else events.onUnavailable(ID)
    }

    private fun buildShared(): Boolean {
        tearDownSharedOnModelThread()
        var mel: OrtSession? = null
        var emb: OrtSession? = null
        return try {
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
            mel = e.createSession(asset("$assetDir/$MEL_ASSET"), opts)
            emb = e.createSession(asset("$assetDir/$EMB_ASSET"), opts)
            shared = SharedModels(
                env = e,
                melSess = mel,
                embSess = emb,
                melInName = mel.inputNames.first(),
                embInName = emb.inputNames.first(),
            )
            true
        } catch (_: Throwable) {
            runCatching { mel?.close() }
            runCatching { emb?.close() }
            shared = null
            false
        }
    }

    /**
     * Load ONNX classifier sessions for each phrase config. Builds the full new set first; on any failure
     * the previous [loadedClassifiers] are left untouched. **Model thread only.**
     */
    private fun installClassifiersOnModelThread(configs: List<PhraseClassifierConfig>): Boolean {
        if (configs.isEmpty()) return false
        val models = shared ?: return false
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
        val built = mutableListOf<LoadedClassifierHandle>()
        val onClose: ((wakeId: String) -> Unit)? = testHooks?.let { hooks ->
            { wakeId: String ->
                hooks.onClassifierClosed.add(wakeId)
                Unit
            }
        }
        try {
            for (cfg in configs) {
                val sess = models.env.createSession(cfg.modelBytes, opts)
                built.add(
                    LoadedClassifierHandle(
                        wakeId = cfg.wakeId,
                        session = sess,
                        inName = sess.inputNames.first(),
                        scoreThreshold = cfg.scoreThreshold,
                        onClose = onClose,
                    ),
                )
            }
        } catch (_: Throwable) {
            built.forEach { runCatching { it.close() } }
            return false
        }
        val previous = loadedClassifiers
        loadedClassifiers = built
        phraseConfigs = configs
        captureGuard.retireOnModelThread(previous)
        return true
    }

    override fun start() {
        wasReady = false
        streamingSeeded = false
        resetStreamingState()
    }

    override fun accept(buf: ByteArray, n: Int) {
        if (closed) return
        if (!ready) {
            preReadyBuffer.add(buf, n)
            return
        }
        captureGuard.enterCapture()
        try {
            if (!wasReady) {
                wasReady = true
                flushPreReadyBuffer()
            }
            if (handoffCooldown.isAnyCoolingDown()) return
            if (loadedClassifiers.isEmpty()) return
            processFrame(buf, n)
        } finally {
            captureGuard.exitCapture()
        }
    }

    private fun flushPreReadyBuffer() {
        val buffered = preReadyBuffer.drain()
        if (buffered.isEmpty()) return
        events.onDiagnostic(ID, "flushing ${buffered.size} pre-ready frame(s)")
        flushBufferedFrames(buffered, handoffCooldown::isAnyCoolingDown, ::processFrame)
    }

    private fun processFrame(buf: ByteArray, n: Int) {
        ensureStreamingSeeded()
        val incoming = pcm16ToShorts(buf, n)
        val samples = if (carry.isEmpty()) incoming else carry + incoming
        var off = 0
        while (samples.size - off >= CHUNK_SAMPLES) {
            pushHistory(samples, off, CHUNK_SAMPLES)
            step()
            off += CHUNK_SAMPLES
        }
        carry = if (off == 0) samples else samples.copyOfRange(off, samples.size)
    }

    /**
     * Hot-swap bundled phrase models. Only for detectors created via [factory] (no-arg).
     * Detectors with explicit [PhraseClassifierConfig] ignore this — [WakeMicEngine.updateWakeWords]
     * cannot supply ONNX bytes; call [updatePhraseModels] on the detector instance instead.
     */
    override fun updateWakeWords(words: List<WakeWord>) {
        if (modelSource != ModelSource.BUNDLED) return
        latestBundledWords = words
        if (!initialLoadFinished) return // loadInitial reads [bundledWords]
        queueClassifierSwap(buildBundledPhraseConfigs(context, words), bundledSwap = true)
    }

    /**
     * Hot-swap explicit phrase classifier configs (portal-wake plugin + bundled mix).
     * Safe to call before the initial load finishes — the latest set is applied when load completes.
     */
    fun updatePhraseModels(configs: List<PhraseClassifierConfig>) {
        if (modelSource != ModelSource.EXPLICIT) return
        latestExplicitConfigs = configs
        phraseConfigs = configs
        if (!initialLoadFinished) return // loadInitial reads [explicitConfigs]
        queueClassifierSwap(configs, bundledSwap = false)
    }

    private fun queueClassifierSwap(configs: List<PhraseClassifierConfig>, bundledSwap: Boolean) {
        modelThread.submit {
            if (closed) return@submit
            if (configs.isEmpty()) {
                val previous = loadedClassifiers
                loadedClassifiers = emptyList()
                captureGuard.retireOnModelThread(previous)
                val wasReady = ready
                ready = false
                if (wasReady) events.onUnavailable(ID)
                return@submit
            }
            if (!installClassifiersOnModelThread(configs)) {
                val message = if (bundledSwap) {
                    "oww: bundled phrase model swap failed — keeping previous models"
                } else {
                    "oww: phrase model swap failed — keeping previous models"
                }
                events.onDiagnostic(ID, message)
            } else if (!ready) {
                // Restored models after an empty/unavailable swap (or first successful install post-load).
                ready = true
                testHooks?.readyEvents?.add(true)
                events.onReady(ID)
            }
        }
    }

    override fun close() {
        closed = true
        modelThread.submitAndJoin(MODEL_THREAD_JOIN_MS) { tearDownOnModelThread() }
        modelThread.shutdown(MODEL_THREAD_JOIN_MS)
        ready = false
    }

    private fun tearDownOnModelThread() {
        val previous = loadedClassifiers
        loadedClassifiers = emptyList()
        captureGuard.retireOnModelThread(previous)
        captureGuard.forceCloseRetiredOnModelThread()
        tearDownSharedOnModelThread()
    }

    private fun tearDownSharedOnModelThread() {
        val models = shared ?: return
        runCatching { models.melSess.close() }
        runCatching { models.embSess.close() }
        shared = null
    }

    private fun resetStreamingState() {
        historyLen = 0
        carry = ShortArray(0)
        loadedClassifiers.forEach { it.stepsSinceFire = Int.MAX_VALUE }
        melBuf.clear()
        featBuf.clear()
        lastDiagMs.clear()
    }

    private fun ensureStreamingSeeded() {
        if (streamingSeeded) return
        repeat(MEL_WINDOW) { melBuf.addLast(FloatArray(NUM_MEL) { 1f }) }
        val seed = embed(flattenNewestMelWindow())
        repeat(CLF_WINDOW) { featBuf.addLast(seed.copyOf()) }
        streamingSeeded = true
    }

    private fun step() {
        val nowMs = System.currentTimeMillis()
        val models = shared ?: return
        val classifiers = loadedClassifiers
        if (classifiers.isEmpty()) return

        val frames = melspec(models)
        val take = minOf(MEL_PER_CHUNK, frames.size)
        for (i in frames.size - take until frames.size) {
            melBuf.addLast(frames[i])
            if (melBuf.size > MEL_MAX) melBuf.removeFirst()
        }
        val emb = embed(models, flattenNewestMelWindow())
        featBuf.addLast(emb)
        if (featBuf.size > FEAT_MAX) featBuf.removeFirst()
        if (featBuf.size < CLF_WINDOW) return

        val flat = flattenNewestFeatureWindow()
        for (classifier in classifiers) {
            if (classifier.closed) continue
            val score = classify(models, classifier, flat)
            classifier.stepsSinceFire =
                if (classifier.stepsSinceFire == Int.MAX_VALUE) {
                    classifier.stepsSinceFire
                } else {
                    classifier.stepsSinceFire + 1
                }
            maybeLogNearMiss(classifier.wakeId, score, classifier.scoreThreshold, nowMs)
            if (score >= classifier.scoreThreshold && classifier.stepsSinceFire >= REFRACTORY_STEPS) {
                classifier.stepsSinceFire = 0
                events.onWake(
                    WakeEvent(ID, classifier.wakeId, "score=${"%.3f".format(score)}"),
                )
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

    private fun melspec(models: SharedModels): Array<FloatArray> {
        val f = historyToFloat(history, historyLen)
        OnnxTensor.createTensor(models.env, FloatBuffer.wrap(f), longArrayOf(1, f.size.toLong())).use { t ->
            models.melSess.run(mapOf(models.melInName to t)).use { r ->
                val out = r[0] as OnnxTensor
                val timeSteps = melTimeSteps(out.info.shape)
                val fb = out.floatBuffer
                return Array(timeSteps) { row ->
                    FloatArray(NUM_MEL) { c -> fb.get(row * NUM_MEL + c) / 10f + 2f }
                }
            }
        }
    }

    private fun embed(models: SharedModels, flat: FloatArray): FloatArray {
        OnnxTensor.createTensor(
            models.env,
            FloatBuffer.wrap(flat),
            longArrayOf(1, MEL_WINDOW.toLong(), NUM_MEL.toLong(), 1),
        ).use { t ->
            models.embSess.run(mapOf(models.embInName to t)).use { r ->
                val out = r[0] as OnnxTensor
                val fb = out.floatBuffer
                return FloatArray(EMB_DIM) { fb.get(it) }
            }
        }
    }

    private fun embed(flat: FloatArray): FloatArray {
        val models = shared ?: return FloatArray(EMB_DIM)
        return embed(models, flat)
    }

    private fun classify(models: SharedModels, classifier: LoadedClassifierHandle, flat: FloatArray): Float {
        testHooks?.classifyOverride?.invoke(classifier.wakeId)?.let { return it }
        if (classifier.closed) return 0f
        OnnxTensor.createTensor(
            models.env,
            FloatBuffer.wrap(flat),
            longArrayOf(1, CLF_WINDOW.toLong(), EMB_DIM.toLong()),
        ).use { t ->
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
        historyLen = pushHistorySamples(history, historyLen, src, off, len)
    }

    private fun asset(name: String): ByteArray = testHooks?.assetLoader?.invoke(name)
        ?: context.assets.open(name).use { it.readBytes() }

    /**
     * Static description of one per-phrase ONNX classifier: which wake [wakeId] it fires for, the model
     * bytes, and the [scoreThreshold] (openWakeWord probability in [0, 1]).
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

    /** Injectable hooks for unit tests — never set in production factories. */
    internal class TestHooks {
        var beforePublishReady: (() -> Unit)? = null

        /** Invoked on the model thread after shared models load, before phrase configs are resolved. */
        var beforeResolveConfigs: (() -> Unit)? = null
        var classifyOverride: ((wakeId: String) -> Float?)? = null
        var assetLoader: ((String) -> ByteArray)? = null
        val onClassifierClosed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val readyEvents = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
    }

    companion object {
        const val ID = "oww"
        const val ASSET_DIR = "oww"

        const val JARVIS_ASSET = "hey_jarvis_v0.1.onnx"
        const val ALEXA_ASSET = "alexa_v0.1.onnx"

        const val WAKE_PHRASE = "hey jarvis"
        const val WAKE_ID = "jarvis"

        const val DEFAULT_SCORE_THRESHOLD = 0.5f

        /**
         * openWakeWord inference stride in milliseconds — 1280 samples @ 16 kHz. Matches the reference
         * `openwakeword.Model` `frame_size`; independent of [PcmCaptureFormat.FRAME_MS] (100 ms).
         */
        const val INFERENCE_STEP_MS = 80

        /**
         * Ring-buffer budget while ONNX sessions load. Sized to cover measured load on bundled assets
         * (see [OpenWakeWordDetectorLoadTest]) plus margin for slow Portal hardware.
         */
        const val PRE_READY_BUDGET_MS = 5_000

        internal const val NEAR_MISS_MARGIN = 0.15f
        internal const val DIAG_MIN_INTERVAL_MS = 500L

        /** Frames to retain during model load: [PRE_READY_BUDGET_MS] converted to [PcmCaptureFormat.FRAME_MS] slots. */
        fun preReadyMaxFrames(budgetMs: Int = PRE_READY_BUDGET_MS): Int = (budgetMs + PcmCaptureFormat.FRAME_MS - 1) / PcmCaptureFormat.FRAME_MS

        /**
         * The wake id the bundled hey-jarvis model owns within a discovered set.
         */
        fun ownedWakeId(words: List<WakeWord>): String? = (words.firstOrNull { it.phrase == WAKE_PHRASE } ?: words.firstOrNull { it.id == WAKE_ID })?.id

        internal fun melTimeSteps(shape: LongArray): Int = if (shape.size >= 2) shape[shape.size - 2].toInt() else 0

        /**
         * Left-align [len] new samples into [history], dropping the oldest when full.
         * Returns the new occupied length. [history] is always packed at index 0.
         */
        internal fun pushHistorySamples(
            history: ShortArray,
            historyLen: Int,
            src: ShortArray,
            off: Int,
            len: Int,
        ): Int {
            val capacity = history.size
            if (len >= capacity) {
                System.arraycopy(src, off + len - capacity, history, 0, capacity)
                return capacity
            }
            val keep = minOf(historyLen, capacity - len)
            if (keep > 0) System.arraycopy(history, historyLen - keep, history, 0, keep)
            System.arraycopy(src, off, history, keep, len)
            return keep + len
        }

        /** Convert the occupied prefix of a left-aligned [history] buffer to floats for the mel model. */
        internal fun historyToFloat(history: ShortArray, historyLen: Int): FloatArray {
            require(historyLen in 0..history.size) {
                "historyLen $historyLen out of range for buffer size ${history.size}"
            }
            val f = FloatArray(historyLen)
            for (i in 0 until historyLen) f[i] = history[i].toFloat()
            return f
        }

        internal fun pcm16ToShorts(buf: ByteArray, n: Int): ShortArray {
            require(n % 2 == 0) { "PCM16 byte count must be even, got $n" }
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

        fun loadBuiltinModel(context: android.content.Context, word: WakeWord): ByteArray? {
            val asset = builtinAssetFor(word) ?: return null
            return runCatching {
                context.assets.open("$ASSET_DIR/$asset").use { it.readBytes() }
            }.getOrNull()
        }

        fun buildBundledPhraseConfigs(context: android.content.Context, words: List<WakeWord>): List<PhraseClassifierConfig> = buildList {
            for (word in words) {
                val bytes = loadBuiltinModel(context, word) ?: continue
                val threshold = word.scoreThreshold.toFloat().coerceIn(0f, 1f)
                add(PhraseClassifierConfig(word.id, bytes, threshold))
            }
        }

        fun assetsPresent(context: android.content.Context): Boolean = runCatching {
            val files = context.assets.list(ASSET_DIR)?.toSet() ?: emptySet()
            MEL_ASSET in files && EMB_ASSET in files
        }.getOrDefault(false)

        /** Factory with explicit phrase configs (portal-wake, including plugin models). */
        fun factory(phraseConfigs: List<PhraseClassifierConfig>): WakeDetector.Factory = WakeDetector.Factory { host ->
            OpenWakeWordDetector(host, ModelSource.EXPLICIT, phraseConfigs)
        }

        /** Test-only factory with injectable hooks and explicit phrase configs. */
        internal fun testFactory(
            phraseConfigs: List<PhraseClassifierConfig>,
            testHooks: TestHooks = TestHooks(),
        ): Pair<WakeDetector.Factory, TestHooks> {
            val hooks = testHooks
            val factory = WakeDetector.Factory { host ->
                OpenWakeWordDetector(host, ModelSource.EXPLICIT, phraseConfigs, testHooks = hooks)
            }
            return factory to hooks
        }

        /** Factory that resolves bundled phrase models from the wake word list (portal-assistant). */
        fun factory(): WakeDetector.Factory = WakeDetector.Factory { host ->
            OpenWakeWordDetector(host, ModelSource.BUNDLED, emptyList())
        }

        internal fun flushBufferedFrames(
            frames: List<ByteArray>,
            isAnyCoolingDown: () -> Boolean,
            acceptFrame: (ByteArray, Int) -> Unit,
        ) {
            for (frame in frames) {
                if (isAnyCoolingDown()) break
                acceptFrame(frame, frame.size)
            }
        }

        private const val MEL_ASSET = "melspectrogram.onnx"
        private const val EMB_ASSET = "embedding_model.onnx"

        private const val NUM_MEL = 32
        private const val MEL_WINDOW = 76
        private const val EMB_DIM = 96
        private const val CLF_WINDOW = 16

        private const val CHUNK_SAMPLES = PcmCaptureFormat.SAMPLE_RATE * INFERENCE_STEP_MS / 1000
        private const val RAW_CONTEXT = CHUNK_SAMPLES + 160 * 3
        private const val MEL_PER_CHUNK = 8

        private const val MEL_MAX = 200
        private const val FEAT_MAX = 32
        private const val REFRACTORY_STEPS = 20
        private const val MODEL_THREAD_JOIN_MS = 30_000L
    }
}
