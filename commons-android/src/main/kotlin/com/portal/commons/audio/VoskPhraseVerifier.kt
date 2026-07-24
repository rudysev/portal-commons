package com.portal.commons.audio

import android.content.Context
import android.os.SystemClock
import com.portal.commons.PcmCaptureFormat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * [WakePhraseVerifier] backed by **Vosk** — stage 2 of the cascade.
 *
 * Decodes the flagged window under a grammar constrained to the registered wake phrases plus an `[unk]`
 * escape, and confirms only if the candidate's own keyword actually comes out. Speech that merely *scores*
 * like the wake word — meeting dialogue, TV, a soundalike — decodes as `[unk]` and is rejected. That is the
 * property no openWakeWord threshold could provide: the three confirmed live false accepts and the genuine
 * far-field utterances occupy the same score band (0.45–0.48 vs 0.30–0.50), but they are trivially
 * separable *phonetically*.
 *
 * **Lenient, but not unconditionally so — see [containsPhrase].** The check is "did the whole declared
 * phrase decode, intact", *not* [WakeMatcher]'s strict gates (confidence floors, no `[unk]`, ≤3 words).
 * Those gates are what hold the *Vosk-primary* detector at zero false accepts, and they cost it 12 of 19
 * recoverable misses — Vosk hears "jarvis" reliably but loses "hey" at distance or in noise. Behind stage 1
 * they are no longer load-bearing for precision, because stage 1 has already established that the phrase
 * was spoken; reusing them here would drag recall back to the Vosk-only baseline and make the cascade
 * pointless. Measured: this rule confirms ~88% of MIC positives and 100% of VR positives, while its
 * false-accept rate *in isolation* (9.6/h) is exactly what stage 1 exists to suppress.
 * See `hey-jarvis/PHASE_A.md` and `PHASE_B.md` §1d.
 *
 * **One recognizer, reused.** Building a [Recognizer] compiles the grammar into a decoding FST — far too
 * slow to do per candidate on the capture thread. One is built when the model loads and reset between
 * verifications; it is rebuilt only when the wake set actually changes.
 *
 * Log-free by design, like [WakeRecognizer]: everything diagnostic goes through [onDiagnostic] so the
 * owning detector controls the log prefix.
 */
internal class VoskPhraseVerifier(
    context: Context,
    initialWakeWords: List<WakeWord>,
    private val onDiagnostic: (String) -> Unit,
    modelDir: File? = null,
) : WakePhraseVerifier {

    @Volatile private var model: Model? = null

    @Volatile private var recognizer: Recognizer? = null

    @Volatile override var state: TwoStagePolicy.VerifierState = TwoStagePolicy.VerifierState.LOADING
        private set

    // Honoured by the async load callback so a close() that races the load frees rather than assigns.
    @Volatile private var closed = false

    @Volatile private var wakeWords: List<WakeWord> = initialWakeWords

    private val startedAtMs = SystemClock.elapsedRealtime()

    init {
        VoskModelLoader.load(context, modelDir, onLoaded = ::onModelLoaded, onUnavailable = ::onModelUnavailable)
    }

    /**
     * The stage-2 grammar: the **full registered phrases** plus the `[unk]` escape — nothing else.
     *
     * Built from [WakeRegistry]'s live set rather than hardcoded, so a plugin's phrase can be verified too
     * (portal-wake supports arbitrary registered phrases). Note this is deliberately *narrower* than
     * [WakeRecognizer.buildGrammar], which also admits the bare keyword and the bare lead: that recognizer
     * runs open on a live stream and needs the looser entries to decode partial utterances, whereas this
     * one re-decodes a 2 s window that stage 1 has already judged to contain the phrase. Constraining it to
     * whole phrases is what makes `[unk]` absorb everything else.
     */
    private fun buildGrammar(words: List<WakeWord>): String {
        val entries = LinkedHashSet<String>()
        words.forEach { entries.add(it.phrase.lowercase()) }
        entries.add(WakeMatcher.UNK_TOKEN)
        return entries.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    }

    override fun verify(window: ShortArray, wakeId: String): Boolean {
        val rec = recognizer ?: return false
        // Verify against this candidate's OWN phrase: stage 1 fires per classifier, so confirming a
        // "jarvis" candidate with a decode of "hey alexa" would be a cross-phrase false accept.
        val phrase = wakeWords.firstOrNull { it.id == wakeId }?.phrase?.lowercase() ?: return false
        if (window.isEmpty()) return false

        return runCatching {
            rec.reset() // discard any state from the previous verification — each window is independent
            rec.acceptWaveForm(window, window.size)
            val text = JSONObject(rec.finalResult).optString("text", "").lowercase()
            val decoded = containsPhrase(text, phrase)
            if (!decoded && text.isNotEmpty()) onDiagnostic("stage-2 decode [$text] is not '$phrase'")
            decoded
        }.getOrElse {
            // A native failure must not be read as a confirmation — fail closed, and say so.
            onDiagnostic("stage-2 decode failed: ${it.message}")
            false
        }
    }

    override fun updateWakeWords(words: List<WakeWord>) {
        if (words.toSet() == wakeWords.toSet()) return // no grammar change — don't pay for an FST rebuild
        val m = model
        if (m == null) {
            wakeWords = words // not loaded yet — the load callback builds from this
            return
        }
        val old = recognizer
        // Commit [wakeWords] only on a successful build, so [verify]'s keyword lookup can never get ahead
        // of the live native grammar (same invariant WakeRecognizer.rebuildGrammar keeps).
        val rec = buildRecognizer(m, words) ?: return
        wakeWords = words
        recognizer = rec
        if (old !== rec) runCatching { old?.close() }
    }

    override fun close() {
        closed = true
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        model = null
    }

    private fun onModelLoaded(m: Model) {
        if (closed) {
            runCatching { m.close() }
            return
        }
        model = m
        val rec = buildRecognizer(m, wakeWords)
        if (closed) { // close() can land while the grammar compiles — free what we just built
            runCatching { rec?.close() }
            runCatching { m.close() }
            model = null
            return
        }
        recognizer = rec
        if (rec != null) {
            state = TwoStagePolicy.VerifierState.READY
            onDiagnostic("stage-2 ready in ${SystemClock.elapsedRealtime() - startedAtMs}ms")
        } else {
            onModelUnavailable()
        }
    }

    private fun onModelUnavailable() {
        state = TwoStagePolicy.VerifierState.UNAVAILABLE
        onDiagnostic("stage-2 unavailable — running single-stage at ${TwoStageTuning.FALLBACK_SCORE}")
    }

    private fun buildRecognizer(m: Model, words: List<WakeWord>): Recognizer? = runCatching {
        // No open-vocabulary fallback here, unlike [WakeRecognizer]: an unconstrained stage 2 would decode
        // arbitrary speech and confirm anything containing the keyword, which is worse than no stage 2 at
        // all. A grammar that won't compile means stage 2 stays unavailable and the fallback threshold runs.
        Recognizer(m, PcmCaptureFormat.SAMPLE_RATE.toFloat(), buildGrammar(words))
    }.getOrNull()

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /**
         * Did [text] decode the whole [phrase] — every word, in order, adjacent?
         *
         * **Whole phrase, not just the keyword.** The keyword-only rule this replaced accepted a bare
         * "jarvis", which fired on ordinary conversation about Jarvis (measured: 1 false accept in 1.6 h of
         * VR negatives, decoding `jarvis [unk]`). Requiring the declared lead costs **nothing** on the
         * VOICE_RECOGNITION capture path portal-wake uses — 100% recall either way over 80 utterances
         * spanning quiet and running water — because the lead is exactly what stage 2 is good at when the
         * signal is clean enough to reach it at all.
         *
         * **Still deliberately lenient — do not tighten further.** `[unk]` on either side is tolerated, and
         * so is surrounding speech; only the phrase itself must appear intact. Demanding an *exact*
         * `"hey jarvis"` decode with no `[unk]` measured 1.7 points worse on MIC positives and removed no
         * additional false accepts on either negative set. And [WakeMatcher]'s full gate set — confidence
         * floors, phrase-length limits — is stricter still and belongs to the Vosk-*primary* detector, where
         * it is the only thing standing between ambient speech and a handoff. Here stage 1 has already
         * established that the phrase was spoken; re-litigating that costs the recall the cascade exists to
         * buy (see `hey-jarvis/PHASE_A.md`).
         *
         * A single-word phrase (a wake word with no lead, e.g. a bare "computer") reduces to the old
         * keyword check, which is correct: there is no lead to require.
         *
         * Pure + static, so it is unit-tested.
         */
        fun containsPhrase(text: String, phrase: String): Boolean {
            val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            val target = phrase.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            if (target.isEmpty() || words.size < target.size) return false
            for (start in 0..words.size - target.size) {
                if ((target.indices).all { words[start + it] == target[it] }) return true
            }
            return false
        }
    }
}
