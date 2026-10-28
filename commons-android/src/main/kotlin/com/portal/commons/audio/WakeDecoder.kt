package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat
import com.portal.commons.audio.WakeMatcher.RecWord
import org.json.JSONObject

/**
 * The **wake decoder**: one Vosk **Decoder** at a time for the current wake words, and the judgement of each decode
 * it finalizes as a wake, a near-miss or nothing. It owns the adopted [VoskModel] and the live [VoskDecoder] (and
 * closes both), builds and warms the grammar, parses Vosk's final and partial JSON, calls [WakeMatcher], and bounds
 * the native lattice with the idle reset ([IdleResetPolicy]), so every flush point (endpoint, reset, new Decoder,
 * capture (re)start) is internal to it.
 *
 * Diagnostics (`near-miss …`, `flushed final …`, the idle resets) go through a plain `(String) -> Unit`, so it
 * doesn't depend on [WakeDetector.Events]. Single-threaded by contract: owned and driven on the capture thread only.
 */
internal class WakeDecoder private constructor(
    private val model: VoskModel,
    private var decoder: VoskDecoder,
    // Committed only once a Decoder for it exists, so the matcher never disagrees with the live native grammar.
    private var wakeWords: List<WakeWord>,
    private val diagnostic: (String) -> Unit,
    // Monotonic milliseconds, for the idle-reset clock.
    private val clock: () -> Long,
) {
    private val idleReset = IdleResetPolicy().also { it.noteFlushed(clock()) }

    /**
     * Feed one frame, running the idle reset check first. When it finalizes a decode (Vosk endpointed) the lattice
     * was just flushed; report a match, a near-miss (a keyword decoded but a gate rejected it), or a non-empty
     * keyword-free decode — a leading "hey" swallowed into a finalization right before the keyword decodes alone
     * surfaces there (the dropped-lead suspect). The decode is carried so the log shows *what Vosk heard*. Returns
     * the wake, if one fired.
     */
    fun accept(buf: ByteArray, n: Int): WakeEvent? {
        maybeIdleReset()
        if (!decoder.acceptFrame(buf, n)) return null
        idleReset.noteFlushed(clock())
        val words = parseResult(decoder.finalResult())
        val transcript = render(words)
        return when (val o = WakeMatcher.evaluate(words, wakeWords)) {
            is WakeMatcher.Outcome.Match -> WakeEvent(VoskWakeDetector.ID, o.id, transcript)

            is WakeMatcher.Outcome.NearMiss -> {
                diagnostic("near-miss [$transcript] rejected: ${o.reason}")
                null
            }

            WakeMatcher.Outcome.None -> {
                if (transcript.isNotEmpty()) diagnostic("flushed final [$transcript]")
                null
            }
        }
    }

    /**
     * Swap the grammar to [words] **without reloading the model**: build a fresh Decoder, swap it in and close the
     * old one. If the build fails, the old Decoder AND the old wake words keep running, so the matcher never
     * disagrees with the live native grammar (we don't go deaf).
     */
    fun updateWakeWords(words: List<WakeWord>) {
        val old = decoder
        val dec = buildDecoder(model, words)
        if (dec != null) {
            wakeWords = words
            decoder = dec
            runCatching { old.close() }
        }
        idleReset.noteFlushed(clock())
    }

    /** Reset and re-warm the Decoder and restart the idle-reset clock (a capture (re)start). */
    fun reset() {
        flush()
        idleReset.noteFlushed(clock())
    }

    /** Close the Decoder and the model. */
    fun close() {
        runCatching { decoder.close() }
        runCatching { model.close() }
    }

    private fun maybeIdleReset() {
        val now = clock()
        val decision = idleReset.decide(now, ::isMidUtterance)
        if (decision == IdleResetPolicy.Decision.KEEP) return
        flush()
        idleReset.noteFlushed(now)
        when (decision) {
            IdleResetPolicy.Decision.RESET -> diagnostic("idle reset (bounding Vosk lattice)")
            IdleResetPolicy.Decision.FORCE_RESET -> diagnostic("idle reset forced mid-decode (bounding Vosk lattice)")
            IdleResetPolicy.Decision.KEEP -> {}
        }
    }

    /**
     * Whether the Decoder holds a non-empty partial hypothesis — mid-way through decoding sound into words — so the
     * idle reset (which discards that partial) can wait. False when the native call fails: an unreadable partial must
     * not block the memory bound.
     */
    private fun isMidUtterance(): Boolean =
        runCatching { parsePartialText(decoder.partialResult()) }.getOrDefault("").isNotEmpty()

    /** Drop any partial decode state, then re-warm — so stale audio doesn't linger into the next utterance. */
    private fun flush() {
        runCatching { decoder.reset() }
        warmUp(decoder)
    }

    companion object {
        const val WARMUP_SILENCE_FRAMES = 10 // ~1 s of silence to settle the online decoder after a reset

        private const val NO_CONF = -1.0 // sentinel: the decoder gave no per-word confidence

        private const val PERCENT = 100 // a 0..1 confidence renders as a whole percent

        /** A wake decoder for [words] on [model]; null, with [model] closed, when the first Decoder can't be built. */
        fun create(
            model: VoskModel,
            words: List<WakeWord>,
            diagnostic: (String) -> Unit,
            clock: () -> Long,
        ): WakeDecoder? {
            val dec = buildDecoder(model, words)
            if (dec == null) {
                runCatching { model.close() }
                return null
            }
            return WakeDecoder(model, dec, words, diagnostic, clock)
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
