package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat
import com.portal.commons.audio.WakeMatcher.RecWord
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
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
 * **Replay is spread over frames.** The pre-ready buffer becomes a backlog that each [accept] decodes at most
 * [REPLAY_FRAMES_PER_ACCEPT] frames of, with every live frame queued behind it until it drains. Decoding a full
 * buffer in one call (~38 ms a frame on gen2, so 1–2 s) held the capture thread inside the callback past a
 * pause's stop join, and the stop force-released the mic. A [start] drops a backlog left by the previous capture.
 *
 * **Audio buffered before a wake is never decoded after it.** A backlog frame that wakes ends the call and drops the
 * rest of the backlog, the live frames queued behind it included.
 * A match is [accept]'s return value; diagnostics carry the exact `near-miss …` / `flushed final …` strings the mic
 * owner used to log.
 */
internal class VoskWakeDetector(
    loader: VoskModelLoader,
    initialWakeWords: List<WakeWord>,
    private val events: WakeDetector.Events,
    // Monotonic milliseconds, for the idle-reset clock (a wall-clock jump could force a mid-decode reset).
    // Injected so tests drive time.
    private val clock: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
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

    // Replay frames not yet decoded, then the live frames queued behind them. Empty once replay has caught up.
    private val backlog = ArrayDeque<ByteArray>()

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
        backlog.clear() // audio from before the pause
        reset()
        idleReset.noteFlushed(clock())
    }

    override fun accept(buf: ByteArray, n: Int): WakeEvent? {
        if (decoder == null) {
            if (!adoptDeliveredModel()) {
                preReadyBuffer.add(buf, n)
                return null
            }
        }
        maybeIdleReset()
        return if (backlog.isEmpty()) {
            decode(buf, n)
        } else {
            backlog.addLast(buf.copyOf(n))
            decodeBacklog()
        }
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
        backlog.clear()
        runCatching { decoder?.close() }
        decoder = null
        runCatching { model?.close() }
        model = null
    }

    /**
     * Take a model the loader delivered: build + warm the first decoder, signal readiness and move the pre-ready
     * buffer into the backlog. False when there is no model yet, or its first decoder build failed (unavailable).
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
            val buffered = preReadyBuffer.drain()
            if (buffered.isNotEmpty()) events.onDiagnostic(ID, "flushing ${buffered.size} pre-ready frame(s)")
            backlog.addAll(buffered)
        }
        return dec != null
    }

    /**
     * Decode up to [REPLAY_FRAMES_PER_ACCEPT] backlog frames in order, returning the first wake. A wake drops the rest:
     * it was buffered before the wake, so it is never decoded after it.
     */
    private fun decodeBacklog(): WakeEvent? {
        val wake = generateSequence { backlog.removeFirstOrNull() }
            .take(REPLAY_FRAMES_PER_ACCEPT)
            .firstNotNullOfOrNull { decode(it, it.size) }
        if (wake != null) backlog.clear()
        return wake
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
     * dropped-lead suspect). The decode is carried so the log shows *what Vosk heard*. Returns the wake, if one fired.
     */
    private fun decode(buf: ByteArray, n: Int): WakeEvent? {
        val dec = decoder
        if (dec == null || !dec.acceptFrame(buf, n)) return null
        idleReset.noteFlushed(clock())
        val words = parseResult(dec.finalResult())
        val transcript = render(words)
        return when (val o = WakeMatcher.evaluate(words, wakeWords)) {
            is WakeMatcher.Outcome.Match -> WakeEvent(ID, o.id, transcript)

            is WakeMatcher.Outcome.NearMiss -> {
                events.onDiagnostic(ID, "near-miss [$transcript] rejected: ${o.reason}")
                null
            }

            WakeMatcher.Outcome.None -> {
                if (transcript.isNotEmpty()) events.onDiagnostic(ID, "flushed final [$transcript]")
                null
            }
        }
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

    companion object {
        const val ID = "vosk"

        const val WARMUP_SILENCE_FRAMES = 10 // ~1 s of silence to settle the online decoder after a reset

        private const val NO_CONF = -1.0 // sentinel: the decoder gave no per-word confidence

        private const val PERCENT = 100 // a 0..1 confidence renders as a whole percent

        private val PRE_READY_MAX_FRAMES = 8_000 / PcmCaptureFormat.FRAME_MS

        /**
         * Backlog frames one [accept] decodes. About 115 ms of decoding on gen2, well inside a stop's join, and the
         * backlog shrinks by two frames for each live one, so a full pre-ready buffer catches up in about 4 s.
         */
        const val REPLAY_FRAMES_PER_ACCEPT = 3

        fun factory(modelDir: File? = null): WakeDetector.Factory = WakeDetector.Factory { host ->
            VoskWakeDetector(
                LibVoskModelLoader(host.context, modelDir),
                host.wakeWords,
                host.events,
            )
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
            if (w.conf < 0) w.word else "${w.word}(${(w.conf * PERCENT).toInt()})"
        }
    }
}
