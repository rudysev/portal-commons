package com.portal.commons.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.portal.commons.PcmCaptureFormat
import java.nio.FloatBuffer
import java.util.ArrayDeque

/**
 * **openWakeWord** [WakeDetector] — the community neural KWS (Home Assistant's default) run on-device with
 * **ONNX Runtime** (a self-contained native lib, **no Google Mobile Services**). Shared in `commons-android`
 * so it is the routing wake detector for **both** portal-wake (gen1 handset) and portal-assistant (gen2
 * foreground); it sits behind the [WakeDetector] seam and reads the same PCM as Vosk.
 *
 * Three-stage ONNX pipeline, exactly as the reference `openwakeword.Model`:
 *  1. **melspectrogram.onnx**: 16 kHz int16 PCM (as float) -> 32-bin mel frames, then the reference's
 *     `x/10 + 2` transform that aligns the ONNX melspec with Google's `speech_embedding` training.
 *  2. **embedding_model.onnx**: a 76-mel-frame window (Google speech_embedding) -> a 96-d feature vector.
 *  3. **hey_jarvis_v0.1.onnx**: the newest 16 feature vectors -> a single probability in [0,1].
 *
 * **Streaming cadence** (mirrors `AudioFeatures._streaming_features`): every 80 ms (1280 samples) we melspec
 * the newest ~1760 samples (1280 + 480 STFT look-back), append the 8 new mel frames, take one 96-d embedding
 * from the newest 76 mel frames, and — once 16 embeddings exist — classify. The mel buffer is seeded with the
 * reference's `ones((76,32))` and the feature buffer is pre-filled so short clips can score immediately (the
 * classifier always needs 16 features ~= 1.28 s of context); without this a 1 s "hey jarvis" clip never fires.
 *
 * Emits [Events.onWake] when the score clears [threshold], with a refractory gap so one utterance fires once.
 *
 * Single-threaded by contract (the engine's capture thread): [start]/[accept]/[close] are never concurrent.
 */
class OpenWakeWordDetector(
    private val context: Context,
    wakeWords: List<WakeWord>,
    private val events: WakeDetector.Events,
    private val assetDir: String = ASSET_DIR,
    private val threshold: Float = DEFAULT_THRESHOLD,
) : WakeDetector {

    override val name: String = NAME

    // This is a fixed "hey jarvis" model, so it fires the id of the jarvis word in the set (see [ownedWakeId]).
    // Falls back to the literal WAKE_ID only for a degenerate set with no jarvis word (it shouldn't fire then).
    private val wakeId: String = ownedWakeId(wakeWords) ?: WAKE_ID

    private var env: OrtEnvironment? = null
    private var melSess: OrtSession? = null
    private var embSess: OrtSession? = null
    private var clfSess: OrtSession? = null
    private var melInName = "input"
    private var embInName = "input_1"
    private var clfInName = "input"

    // Rolling raw-audio history (last RAW_CONTEXT samples) so each melspec has its STFT look-back.
    private val history = ShortArray(RAW_CONTEXT)
    private var historyLen = 0
    private var carry = ShortArray(0) // samples not yet at a 1280 boundary

    private val melBuf = ArrayDeque<FloatArray>() // 32-d mel frames (seeded with ones)
    private val featBuf = ArrayDeque<FloatArray>() // 96-d embeddings (seeded so 16 exist from the start)
    private var stepsSinceFire = Int.MAX_VALUE

    init {
        val ok = runCatching { build() }.getOrDefault(false)
        if (ok) events.onReady(NAME) else events.onUnavailable(NAME)
    }

    private fun build(): Boolean {
        close()
        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
        val mel = e.createSession(asset("$assetDir/$MEL_ASSET"), opts)
        val emb = e.createSession(asset("$assetDir/$EMB_ASSET"), opts)
        val clf = e.createSession(asset("$assetDir/$WAKE_ASSET"), opts)
        melInName = mel.inputNames.first()
        embInName = emb.inputNames.first()
        clfInName = clf.inputNames.first()
        env = e; melSess = mel; embSess = emb; clfSess = clf
        return true
    }

    override fun start() {
        historyLen = 0
        carry = ShortArray(0)
        stepsSinceFire = Int.MAX_VALUE
        melBuf.clear()
        featBuf.clear()
        // Seed the mel buffer with ones((76,32)) — the reference's melspectrogram_buffer initial state.
        repeat(MEL_WINDOW) { melBuf.addLast(FloatArray(NUM_MEL) { 1f }) }
        // Pre-fill the feature buffer so the classifier has 16 features immediately (else short clips can't fire).
        // Use the embedding of the ones-mel window as deterministic non-wake filler.
        val seed = embed(flattenNewestMelWindow())
        repeat(CLF_WINDOW) { featBuf.addLast(seed.copyOf()) }
    }

    override fun accept(buf: ByteArray, n: Int) {
        if (clfSess == null) return
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
        // openWakeWord is a fixed per-phrase model — a wake-set swap can't change what it listens for.
    }

    override fun close() {
        runCatching { melSess?.close() }
        runCatching { embSess?.close() }
        runCatching { clfSess?.close() }
        melSess = null; embSess = null; clfSess = null
        // Do not close the shared OrtEnvironment singleton.
        env = null
    }

    // ---- one 80 ms step -----------------------------------------------------------------------------

    private fun step() {
        val frames = melspec() // newest mel frames for this chunk
        val take = minOf(MEL_PER_CHUNK, frames.size)
        for (i in frames.size - take until frames.size) {
            melBuf.addLast(frames[i])
            if (melBuf.size > MEL_MAX) melBuf.removeFirst()
        }
        val emb = embed(flattenNewestMelWindow())
        featBuf.addLast(emb)
        if (featBuf.size > FEAT_MAX) featBuf.removeFirst()
        if (featBuf.size < CLF_WINDOW) return

        val score = classify()
        stepsSinceFire = if (stepsSinceFire == Int.MAX_VALUE) stepsSinceFire else stepsSinceFire + 1
        if (score >= threshold && stepsSinceFire >= REFRACTORY_STEPS) {
            stepsSinceFire = 0
            events.onWake(NAME, wakeId, "oww p=${"%.3f".format(score)}")
        }
    }

    // ---- ONNX stages --------------------------------------------------------------------------------

    /** Melspec the newest history (up to RAW_CONTEXT samples); returns mel frames [T][32] with the `x/10+2` transform. */
    private fun melspec(): Array<FloatArray> {
        val e = env ?: return emptyArray()
        val len = historyLen
        val f = FloatArray(len)
        val startIdx = RAW_CONTEXT - len
        for (i in 0 until len) f[i] = history[startIdx + i].toFloat()
        OnnxTensor.createTensor(e, FloatBuffer.wrap(f), longArrayOf(1, len.toLong())).use { t ->
            melSess!!.run(mapOf(melInName to t)).use { r ->
                val out = r[0] as OnnxTensor
                // melspec output is [1, 1, T, 32] — the time axis is the 2nd-to-last dim, NOT dim 0 (see
                // [melTimeSteps]). The two leading 1-dims add no stride, so the flat buffer is T*32 row-major
                // (frame t, bin c -> t*32+c).
                val T = melTimeSteps(out.info.shape)
                val fb = out.floatBuffer
                return Array(T) { row ->
                    FloatArray(NUM_MEL) { c -> fb.get(row * NUM_MEL + c) / 10f + 2f }
                }
            }
        }
    }

    /** Embed the newest 76 mel frames -> 96-d feature. [flat] is a 76*32 row-major float array. */
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

    /** Classify the newest 16 embeddings -> probability [0,1]. */
    private fun classify(): Float {
        val e = env ?: return 0f
        val flat = FloatArray(CLF_WINDOW * EMB_DIM)
        var idx = 0
        val it = featBuf.descendingIterator()
        val newest = ArrayList<FloatArray>(CLF_WINDOW)
        while (newest.size < CLF_WINDOW && it.hasNext()) newest.add(it.next())
        newest.reverse()
        for (v in newest) { System.arraycopy(v, 0, flat, idx, EMB_DIM); idx += EMB_DIM }
        OnnxTensor.createTensor(e, FloatBuffer.wrap(flat), longArrayOf(1, CLF_WINDOW.toLong(), EMB_DIM.toLong()))
            .use { t ->
                clfSess!!.run(mapOf(clfInName to t)).use { r ->
                    val out = r[0] as OnnxTensor
                    return out.floatBuffer.get(0)
                }
            }
    }

    /** Row-major 76*32 float array of the newest 76 mel frames (the embedding input window). */
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

    companion object {
        const val NAME = "oww"
        const val ASSET_DIR = "oww"

        // The fixed phrase this model detects, and the default id it maps to. Routing sends an oww fire to
        // the jarvis wake target; [ownedWakeId] resolves which id that is within a discovered set.
        const val WAKE_PHRASE = "hey jarvis"
        const val WAKE_ID = "jarvis"

        /**
         * The wake id this fixed hey-jarvis model owns within a (possibly multi-word) discovered set: the word
         * whose [WakeWord.phrase] is [WAKE_PHRASE] (robust to a plugin that remaps the phrase to a non-"jarvis"
         * id), else a word already keyed [WAKE_ID], else null when the set has no jarvis word at all. Pure and
         * shared with the router so what oww *fires* and which Vosk fire the router *suppresses* can't drift; a
         * null result means this detector owns nothing in that set (it should not route).
         */
        fun ownedWakeId(words: List<WakeWord>): String? =
            (words.firstOrNull { it.phrase == WAKE_PHRASE } ?: words.firstOrNull { it.id == WAKE_ID })?.id

        /**
         * Time-step count from the melspectrogram ONNX output shape. The output is `[1, 1, T, 32]` — the time
         * axis is the **2nd-to-last** dim, not dim 0 (the two leading 1-dims add no stride). Pulled out pure to
         * lock in the fix for the shape-parsing bug where T was read from dim 0 (always 1 → the mel buffer never
         * filled and every score was ~0).
         */
        internal fun melTimeSteps(shape: LongArray): Int = if (shape.size >= 2) shape[shape.size - 2].toInt() else 0

        /** Decode [n] bytes of little-endian signed 16-bit PCM in [buf] to [n]/2 shorts. Pure. */
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

        private const val MEL_ASSET = "melspectrogram.onnx"
        private const val EMB_ASSET = "embedding_model.onnx"
        private const val WAKE_ASSET = "hey_jarvis_v0.1.onnx"

        private const val NUM_MEL = 32
        private const val MEL_WINDOW = 76 // embedding input frames
        private const val EMB_DIM = 96
        private const val CLF_WINDOW = 16 // classifier input embeddings

        // 80 ms chunk (1280 samples) + 480 samples (3 hops) STFT look-back = the reference melspec input length.
        private const val CHUNK = PcmCaptureFormat.SAMPLE_RATE * 80 / 1000 // 1280
        private const val RAW_CONTEXT = CHUNK + 160 * 3 // 1760
        private const val MEL_PER_CHUNK = 8 // mel frames produced per 80 ms chunk

        private const val MEL_MAX = 200
        private const val FEAT_MAX = 32
        private const val REFRACTORY_STEPS = 20 // ~1.6 s between fires

        // The production decision threshold. 0.5 is the "stops triggering while I talk" point: 0
        // background/generic-speech false-accepts, only jarvis-family near-misses; lower toward ~0.35 to
        // trade a little precision for higher recall.
        const val DEFAULT_THRESHOLD = 0.5f

        /** True when the oww model assets are bundled (they are shipped in commons-android). */
        fun assetsPresent(context: Context): Boolean = runCatching {
            val files = context.assets.list(ASSET_DIR)?.toSet() ?: emptySet()
            MEL_ASSET in files && EMB_ASSET in files && WAKE_ASSET in files
        }.getOrDefault(false)

        fun factory(threshold: Float = DEFAULT_THRESHOLD): WakeDetector.Factory =
            WakeDetector.Factory { context, words, events ->
                OpenWakeWordDetector(context, words, events, threshold = threshold)
            }
    }
}
