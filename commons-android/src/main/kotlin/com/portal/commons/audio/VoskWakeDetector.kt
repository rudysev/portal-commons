package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat
import com.portal.commons.audio.WakeMatcher.RecWord
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The **Vosk** [WakeDetector] (free, keyless, offline): model handoff, grammar building, warm-up, result
 * parsing, the [WakeMatcher] call, the pre-ready buffer + replay, the idle reset that bounds Vosk's decode
 * lattice, and the grammar hot-swap. Native libvosk sits behind the [VoskModelLoader] / [VoskModel] /
 * [VoskDecoder] seam; the accuracy *decision* lives in [WakeMatcher]; the mic lives in [WakeMicEngine].
 *
 * **The capture thread owns every decoder.** The loader only hands the model over ([delivered]); the next
 * frame adopts it, builds + warms the first decoder from the *current* wake words, then signals
 * [WakeDetector.Events.onReady] (or [WakeDetector.Events.onUnavailable] if that build fails). A model-*load*
 * failure signals unavailable straight from the loader. One thread touches decoders, so no locks.
 *
 * **Per-frame policy** ([accept]): until a decoder exists, ring-buffer frames (so speech during init isn't
 * lost); on adoption, replay them; then feed the decoder, bounding the native lattice via [IdleResetPolicy].
 * **On (re)start** ([start]) the decoder is reset — which re-warms it — so the first frame after a resume
 * isn't garbled.
 *
 * The **handoff cooldown** is [WakeMicEngine]'s: it simply stops feeding frames, so this detector never sees
 * it. The one place it would otherwise leak in is replay — the pre-ready buffer is fed in one go, so replay
 * stops at the first wake it emits, and the frame that triggered the replay is dropped with the rest.
 * A match reports via [WakeDetector.Events.onWake]; diagnostics carry the exact `near-miss …` /
 * `flushed final …` strings the mic owner used to log.
 */
internal class VoskWakeDetector(
    loader: VoskModelLoader,
    initialWakeWords: List<WakeWord>,
    private val events: WakeDetector.Events,
    // Monotonic milliseconds, for the idle-reset clock (a wall-clock jump could force a mid-decode reset).
    // Injected so tests drive time.
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : WakeDetector {

    override val id: String = ID

    // The loader → capture-thread handoff: set by the loader callback, taken by the next frame (or freed by
    // close()). Atomic so a delivery racing close() frees the model exactly once.
    private val delivered = AtomicReference<VoskModel?>(null)

    @Volatile private var closed = false

    // Capture-thread state. close() also touches it, but only after capture stops, under the engine's lock.
    private var model: VoskModel? = null
    private var decoder: VoskDecoder? = null // non-null ⇔ ready

    // The live wake set. Swapped in place by [updateWakeWords] (on a plugin install/remove) without reloading
    // the model — the model never changes, only the grammar does. Committed only once a decoder for it exists.
    private var wakeWords: List<WakeWord> = initialWakeWords

    private val idleReset = IdleResetPolicy()
    private val preReadyBuffer = PcmRingBuffer(PRE_READY_MAX_FRAMES)

    init {
        try {
            loader.load(::onModelDelivered) { events.onUnavailable(ID) }
        } catch (
            // Throwable, not Exception: native init fails with Errors too (UnsatisfiedLinkError from libvosk).
            @Suppress("SwallowedException", "TooGenericExceptionCaught")
            t: Throwable,
        ) {
            // The throwable is intentionally swallowed: don't let a model-load / native-init failure crash the
            // service. By contract any init failure is surfaced as onUnavailable, which the engine's consumer logs.
            events.onUnavailable(ID)
        }
    }

    /** Loader callback (any thread): hand the model to the capture thread, or free it if we're already closed. */
    private fun onModelDelivered(m: VoskModel) {
        if (closed) {
            runCatching { m.close() }
            return
        }
        delivered.set(m)
        // close() landed between the check and the set: whichever of us takes it back frees it.
        if (closed && delivered.compareAndSet(m, null)) runCatching { m.close() }
    }

    override fun start() {
        reset()
        idleReset.noteFlushed(clock())
    }

    override fun accept(buf: ByteArray, n: Int) {
        if (decoder == null) {
            if (!adoptDeliveredModel()) {
                preReadyBuffer.add(buf, n)
                return
            }
            if (replayPreReadyFrames()) return // this frame came after the replayed wake: inside the handoff cooldown
        }
        maybeIdleReset()
        decode(buf, n)
    }

    /**
     * Swap the grammar to a new wake set **without reloading the model**: build a fresh decoder from the
     * adopted model, swap it in and close the old one. Runs on the capture thread at a frame boundary, so a
     * decoder is never closed mid-[accept]. Before a decoder exists, just stash the set — adoption builds from
     * it. If the build fails, the old decoder AND the old [wakeWords] keep running, so the matcher never
     * disagrees with the live native grammar (we don't go deaf).
     */
    override fun updateWakeWords(words: List<WakeWord>) {
        val m = model
        val old = decoder
        if (m == null || old == null) {
            wakeWords = words
        } else {
            val dec = buildDecoder(m, words)
            if (dec != null) {
                wakeWords = words
                decoder = dec
                runCatching { old.close() }
            }
        }
        idleReset.noteFlushed(clock())
    }

    override fun close() {
        closed = true
        delivered.getAndSet(null)?.let { runCatching { it.close() } }
        runCatching { decoder?.close() }
        decoder = null
        runCatching { model?.close() }
        model = null
    }

    /**
     * Take a model the loader delivered: build + warm the first decoder, signal readiness. False when there is
     * no model yet, or its first decoder build failed (unavailable).
     */
    private fun adoptDeliveredModel(): Boolean {
        val m = delivered.getAndSet(null) ?: return false
        model = m
        val dec = buildDecoder(m, wakeWords)
        if (dec == null) {
            events.onUnavailable(ID)
        } else {
            decoder = dec
            events.onReady(ID)
        }
        return dec != null
    }

    /** Decode the pre-ready buffer in order, stopping at the first wake. True if a wake fired. */
    private fun replayPreReadyFrames(): Boolean {
        val buffered = preReadyBuffer.drain()
        if (buffered.isNotEmpty()) events.onDiagnostic(ID, "flushing ${buffered.size} pre-ready frame(s)")
        return buffered.any { frame -> decode(frame, frame.size) } // any() stops at the first wake
    }

    private fun maybeIdleReset() {
        val now = clock()
        val decision = idleReset.decide(now, ::isMidUtterance)
        if (decision == IdleResetPolicy.Decision.KEEP) return
        reset()
        idleReset.noteFlushed(now)
        when (decision) {
            IdleResetPolicy.Decision.RESET ->
                events.onDiagnostic(ID, "idle reset (bounding Vosk lattice)")

            IdleResetPolicy.Decision.FORCE_RESET ->
                events.onDiagnostic(ID, "idle reset forced mid-decode (bounding Vosk lattice)")

            IdleResetPolicy.Decision.KEEP -> {}
        }
    }

    /**
     * Feed one frame. When it finalizes a decode (Vosk endpointed) the lattice was just flushed; report a
     * match, a near-miss (a keyword decoded but a gate rejected it), or a non-empty keyword-free decode — a
     * leading "hey" swallowed into a finalization right before the keyword decodes alone surfaces there (the
     * dropped-lead suspect). The decode is carried so the log shows *what Vosk heard*. True if a wake fired.
     */
    private fun decode(buf: ByteArray, n: Int): Boolean {
        val dec = decoder
        if (dec == null || !dec.acceptFrame(buf, n)) return false
        idleReset.noteFlushed(clock())
        val words = parseResult(dec.finalResult())
        val transcript = render(words)
        val outcome = WakeMatcher.evaluate(words, wakeWords)
        when (val o = outcome) {
            is WakeMatcher.Outcome.Match ->
                events.onWake(WakeEvent(ID, o.id, transcript))

            is WakeMatcher.Outcome.NearMiss ->
                events.onDiagnostic(ID, "near-miss [$transcript] rejected: ${o.reason}")

            WakeMatcher.Outcome.None ->
                if (transcript.isNotEmpty()) events.onDiagnostic(ID, "flushed final [$transcript]")
        }
        return outcome is WakeMatcher.Outcome.Match
    }

    /**
     * Whether the decoder holds a non-empty partial hypothesis — mid-way through decoding sound into words —
     * so the idle reset (which discards that partial) can wait. False when the native call fails: an
     * unreadable partial must not block the memory bound.
     */
    private fun isMidUtterance(): Boolean {
        val dec = decoder ?: return false
        return runCatching { parsePartialText(dec.partialResult()) }.getOrDefault("").isNotEmpty()
    }

    /** Drop any partial decode state, then re-warm — so stale audio doesn't linger into the next utterance. */
    private fun reset() {
        val dec = decoder ?: return
        runCatching { dec.reset() }
        warmUp(dec)
    }

    /**
     * Build a warmed decoder biased to [words]; fall back to open vocabulary if the model rejects the grammar.
     * Null if the build fails outright.
     */
    private fun buildDecoder(m: VoskModel, words: List<WakeWord>): VoskDecoder? = runCatching {
        val dec = runCatching { m.newDecoder(buildGrammar(words)) }.getOrElse { m.newDecoder(null) }
        warmUp(dec)
        dec
    }.getOrNull()

    /**
     * Prime Kaldi's online decoder with ~1 s of silence, not just one frame. A single frame forces the lazy
     * decoding-graph allocation, but the online-decoding state needs more run-up to settle: otherwise the FIRST
     * word of the first utterance after a reset (fresh start, handoff reclaim, idle reset) is dropped — observed
     * on-device as `[jarvis(..)] rejected: no 'hey'` misses where the leading "hey" never decodes.
     */
    private fun warmUp(dec: VoskDecoder) {
        runCatching {
            val silence = ByteArray(PcmCaptureFormat.FRAME_BYTES)
            repeat(WARMUP_SILENCE_FRAMES) { dec.acceptFrame(silence, silence.size) }
        }
    }

    companion object {
        const val ID = "vosk"

        const val WARMUP_SILENCE_FRAMES = 10 // ~1 s of silence to settle the online decoder after a reset

        private const val NO_CONF = -1.0 // sentinel: the decoder gave no per-word confidence

        private val PRE_READY_MAX_FRAMES = (8_000 / PcmCaptureFormat.FRAME_MS).toInt()

        fun factory(modelDir: File? = null): WakeDetector.Factory = WakeDetector.Factory { host ->
            VoskWakeDetector(
                LibVoskModelLoader(host.context, modelDir),
                host.wakeWords,
                host.events,
            )
        }

        /**
         * Vosk JSON grammar built from the wake phrases + their keywords + each word's declared lead (e.g.
         * "hey"), plus an "[unk]" escape token. "[unk]" absorbs non-matching speech so the decoder doesn't force
         * look-alikes onto a wake phrase; the per-phrase entries bias it toward a real wake — the single biggest
         * accuracy win. Only the declared lead is added (not its [WakeMatcher.LEAD_ALIASES] mishearings), so the
         * grammar stays identical to what a "hey jarvis" produced before — on-device recall is unaffected.
         */
        private fun buildGrammar(words: List<WakeWord>): String {
            val entries = LinkedHashSet<String>()
            words.forEach {
                entries.add(it.phrase.lowercase())
                entries.add(it.keyword.lowercase())
                it.lead?.let { lead -> entries.add(lead.lowercase()) }
            }
            entries.add(WakeMatcher.UNK_TOKEN) // same token WakeMatcher's strict contamination gate rejects
            return entries.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        }

        /**
         * Parse a Vosk final-result JSON into recognized words. Prefers per-word confidence (from
         * `setWords(true)`); falls back to the plain transcript with unknown confidence.
         */
        private fun parseResult(json: String): List<RecWord> {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
            val arr = obj.optJSONArray("result")
            val text = obj.optString("text").trim()
            return when {
                arr != null && arr.length() > 0 -> (0 until arr.length()).mapNotNull { idx ->
                    arr.optJSONObject(idx)?.let { o -> RecWord(o.optString("word"), o.optDouble("conf", NO_CONF)) }
                }

                text.isEmpty() -> emptyList()

                else -> text.split(Regex("\\s+")).map { RecWord(it, NO_CONF) }
            }
        }

        /** The hypothesis text of a Vosk partial-result JSON (`{"partial" : "hey jar"}`); empty if quiet/malformed. */
        private fun parsePartialText(json: String): String {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return ""
            return obj.optString("partial").trim()
        }

        /** Render the decode as `word(conf%)` tokens (confidence omitted when the model gave none). */
        private fun render(words: List<RecWord>): String = words.joinToString(" ") { w ->
            if (w.conf < 0) w.word else "${w.word}(${(w.conf * 100).toInt()})"
        }
    }
}
