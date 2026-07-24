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
 * **Deliberately lenient — do not "harden" this into [WakeMatcher].** The check is "did the keyword
 * decode", not [WakeMatcher]'s strict gates (mandatory confident lead, no `[unk]`, ≤3 words). Those gates
 * are what hold the *Vosk-only* detector at zero false accepts, and they cost it 12 of 19 recoverable
 * misses — Vosk hears "jarvis" reliably but loses "hey" at distance or in noise. Behind stage 1 they are no
 * longer load-bearing for precision, because stage 1 has already established that the phrase was spoken.
 * Reusing them here would drag recall back down to the Vosk-only baseline and make the cascade pointless.
 * Measured: lenient stage 2 confirms 88% of positives, and its 9.6/h false-accept rate *in isolation* is
 * exactly what stage 1 exists to suppress. See `hey-jarvis/PHASE_A.md`.
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
        val keyword = wakeWords.firstOrNull { it.id == wakeId }?.keyword?.lowercase() ?: return false
        if (window.isEmpty()) return false

        return runCatching {
            rec.reset() // discard any state from the previous verification — each window is independent
            rec.acceptWaveForm(window, window.size)
            val text = JSONObject(rec.finalResult).optString("text", "").lowercase()
            // Whole-word match: "jarvis" must be a token, not a substring of some longer decode.
            val decoded = text.split(' ').any { it == keyword }
            if (!decoded && text.isNotEmpty()) onDiagnostic("stage-2 decode [$text] carries no '$keyword'")
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
}
