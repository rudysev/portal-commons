package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat
import com.portal.commons.audio.WakeMatcher.RecWord
import org.json.JSONObject

/**
 * The **wake decoder**: one Vosk **Decoder** at a time for the current wake words, and the judgement of each decode
 * it finalizes as a wake, a near-miss or nothing. It owns the adopted [VoskModel] and the live [VoskDecoder] (and
 * closes both), builds and warms the grammar, parses Vosk's final and partial JSON, calls [WakeMatcher], and bounds
 * the native lattice with the idle reset, so every flush point (endpoint, reset, new Decoder, capture (re)start) is
 * internal to it.
 *
 * **Idle reset.** The lattice only grows while Vosk is *not* endpointing: with continuous audio the grammar Decoder can
 * go a long time without finalizing, and the never-bounded lattice grows ~3.8 MB/min (measured on-device). A Decoder
 * reset flushes it — but reset also discards the in-flight partial, so one that lands mid "hey jarvis" throws away the
 * already-decoded "hey" and the wake vanishes without even a near-miss. So the reset is a **backstop**, issued only
 * when it is both needed and safe:
 *  1. **Reset only when the Decoder is quiet.** Once the lattice has gone [QUIET_RESET_MS] unflushed, the reset waits
 *     for a frame with no in-flight partial so it can't bisect an utterance. [FORCE_RESET_MS] is the hard cap: past it
 *     the reset fires even mid-decode, so pathological continuous noise (a TV that never yields a quiet frame) still
 *     can't grow the lattice unbounded — worst case it holds ~[FORCE_RESET_MS] of audio (< 4 MB).
 *  2. **Natural endpoints are the primary bound; this reset is only the fallback.** Every finalized decode means Kaldi
 *     already flushed the lattice, which restarts the clock — so the idle reset fires only after [QUIET_RESET_MS] with
 *     *no* endpoint at all. In any room with ambient sound Vosk endpoints every few seconds, so the clock keeps
 *     restarting and the reset may **never** fire. That is correct, not a stall: those endpoints are what bound memory.
 *
 * Diagnostics (`near-miss …`, `flushed final …`, the idle resets) go through a plain `(String) -> Unit`, so it
 * depends on no callback interface. Single-threaded by contract: owned and driven on the capture thread only.
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
    private var flushedAtMs = clock() // last instant the lattice was known bounded (reset, new Decoder, or endpoint)

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
        latticeBounded()
        val words = parseResult(decoder.finalResult())
        val transcript = render(words)
        return when (val o = WakeMatcher.evaluate(words, wakeWords)) {
            is WakeMatcher.Outcome.Match -> WakeEvent(o.id, transcript)

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
        latticeBounded()
    }

    /** Reset and re-warm the Decoder and restart the idle-reset clock (a capture (re)start). */
    fun reset() = flush()

    /** Close the Decoder and the model. */
    fun close() {
        runCatching { decoder.close() }
        runCatching { model.close() }
    }

    private fun maybeIdleReset() {
        val message = idleResetDue() ?: return
        flush()
        diagnostic(message)
    }

    /**
     * The diagnostic for the idle reset due now (rules in the class KDoc), or null to keep the Decoder. The partial —
     * a native call — is queried only once [QUIET_RESET_MS] has actually elapsed, not per frame.
     */
    private fun idleResetDue(): String? {
        val unflushedMs = clock() - flushedAtMs
        return when {
            unflushedMs < QUIET_RESET_MS -> null

            // Any non-empty partial defers — even a bare "[unk]": the decoder may hypothesize [unk] while a real
            // "hey" is still settling. FORCE_RESET_MS caps how long that deferral can hold the reset off.
            !isMidUtterance() -> "idle reset (bounding Vosk lattice)"

            unflushedMs >= FORCE_RESET_MS -> "idle reset forced mid-decode (bounding Vosk lattice)"

            else -> null
        }
    }

    /** The lattice was just bounded (a reset, a new Decoder or an endpoint): restart the idle-reset clock. */
    private fun latticeBounded() {
        flushedAtMs = clock()
    }

    /**
     * Whether the Decoder holds a non-empty partial hypothesis — mid-way through decoding sound into words — so the
     * idle reset (which discards that partial) can wait. False when the native call fails: an unreadable partial must
     * not block the memory bound.
     */
    private fun isMidUtterance(): Boolean =
        runCatching { parsePartialText(decoder.partialResult()) }.getOrDefault("").isNotEmpty()

    /**
     * Drop any partial decode state, then re-warm — so stale audio doesn't linger into the next utterance — and
     * restart the idle-reset clock.
     */
    private fun flush() {
        runCatching { decoder.reset() }
        warmUp(decoder)
        latticeBounded()
    }

    companion object {
        const val WARMUP_SILENCE_FRAMES = 10 // ~1 s of silence to settle the online decoder after a reset

        /** Unflushed this long with no natural endpoint → idle reset at the next quiet frame. */
        private const val QUIET_RESET_MS = 25_000L

        /** Hard cap on unflushed time — idle reset even mid-decode past here (~3.8 MB/min → < 4 MB held). */
        private const val FORCE_RESET_MS = 60_000L

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
         * Vosk JSON grammar built from the wake phrases + each word's declared lead (e.g. "hey"), plus an "[unk]"
         * escape token. "[unk]" absorbs non-matching speech so the decoder doesn't force look-alikes onto a wake
         * phrase; the per-phrase entries bias it toward a real wake — the single biggest accuracy win. Only the
         * declared lead is added (not its [WakeMatcher.LEAD_ALIASES] mishearings).
         *
         * The bare keyword is an entry only for a word with no lead. For a word with a lead the matcher never
         * fires on a bare keyword, and offering one let the decoder explain a quiet, unstressed "hey" as silence
         * and decode `[jarvis]` alone, the commonest missed wake on device. Without it the decoder has to account
         * for that audio as the lead (or "[unk]").
         *
         * A keyword's [WakeMatcher.DECOYS] follow its entries, so speech that only resembles it has a closer match.
         */
        private fun buildGrammar(words: List<WakeWord>): String {
            val entries = LinkedHashSet<String>()
            words.forEach {
                entries.add(it.phrase.lowercase())
                val lead = it.lead
                if (lead == null) entries.add(it.keyword.lowercase()) else entries.add(lead.lowercase())
                entries.addAll(WakeMatcher.decoysFor(it))
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
