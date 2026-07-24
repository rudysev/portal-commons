package com.portal.commons.audio

import android.content.Context
import android.os.SystemClock
import com.portal.commons.PcmCaptureFormat
import java.io.File

/**
 * The **two-stage (cascade)** [WakeDetector]: openWakeWord as stage 1, a phrase-constrained Vosk decode as
 * stage 2, composed as an **AND**.
 *
 * ```
 * frame ──┬─> rolling 2 s window ─────────────────────────┐
 *         └─> stage 1 (oWW, loose threshold) ──candidate──┴─> TwoStagePolicy ──> wake / near-miss
 *                                                              └─> stage 2 (Vosk, this window)
 * ```
 *
 * **Why a cascade and not two detectors in parallel.** [WakeMicEngine] happily fans frames to several
 * detectors, and an earlier design ran openWakeWord and Vosk side by side with [WakeRouting] deciding which
 * fire wins. That is an **OR**: each detector's false accepts survive, so they add up. This is an AND —
 * stage 1 proposes, stage 2 disposes — and it is the configuration the measurements support:
 *
 * | | recall | false accepts |
 * |---|---|---|
 * | Vosk only (shipped v2.3.0) | 57% | 0 / 2.6 h |
 * | oWW only @0.30 | 92% | 0.5/h **+ 3 meeting FAs** |
 * | oWW only @0.50 | 73% | ~0 |
 * | **this, @0.30 → verify** | **85–90%** | **0 / 2.6 h** |
 *
 * The three meeting false accepts scored 0.451–0.477, squarely inside the band genuine far-field speech
 * occupies — no threshold separates them, and stage 2 rejects all three. See `hey-jarvis/RESULTS.md`.
 *
 * **Composition, not modification.** Stage 1 is an ordinary [WakeDetector] handed a [WakeDetector.Host]
 * whose [WakeDetector.Events] this class supplies, so its fires arrive here instead of at the engine. Stage
 * 1 needs no knowledge that it is being cascaded, and any future stage-1 engine drops in unchanged.
 *
 * **Threading.** [start]/[accept]/[updateWakeWords] run on the engine's capture thread, and stage 2 runs
 * **synchronously** on it — see [TwoStageTuning.VERIFY_BUDGET_MS] for why that is safe (candidates are
 * rare, and `AudioRecord` is buffered well past one decode) and how an overrun is reported.
 */
class TwoStageWakeDetector internal constructor(
    private val host: WakeDetector.Host,
    stage1Factory: WakeDetector.Factory,
    verifierFactory: (WakeDetector.Host, (String) -> Unit) -> WakePhraseVerifier,
    private val bypassScore: Float = TwoStageTuning.BYPASS_SCORE,
    private val fallbackScore: Float = TwoStageTuning.FALLBACK_SCORE,
    private val verifyBudgetMs: Long = TwoStageTuning.VERIFY_BUDGET_MS,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : WakeDetector {

    override val id: String = ID

    private val events get() = host.events

    /** The audio stage 2 re-decodes: the same span openWakeWord's classifier window covers. */
    private val window = PcmWindowBuffer(PcmCaptureFormat.SAMPLE_RATE * TwoStageTuning.VERIFY_WINDOW_MS / 1000)

    private val verifier: WakePhraseVerifier = verifierFactory(host) { msg -> events.onDiagnostic(ID, msg) }

    /**
     * Stage 1, built against a host whose events land on [stage1Events]. Everything else — context, wake
     * set, handoff cooldown — is the real host's, so stage 1 still gates itself on the engine's post-fire
     * cooldown exactly as it would standalone.
     */
    private val stage1: WakeDetector = stage1Factory.create(
        object : WakeDetector.Host {
            override val context: Context get() = host.context
            override val wakeWords: List<WakeWord> get() = stage1WakeWords(host.wakeWords)
            override val events: WakeDetector.Events = stage1Events()
            override val handoffCooldown: WakeHandoffCooldown get() = host.handoffCooldown
        },
    )

    private fun stage1Events() = object : WakeDetector.Events {
        // Readiness is reported under the CASCADE's id, not stage 1's: to the engine and to portal-wake
        // this is one detector. Stage 1 loading is what gates capture — stage 2 arriving later only
        // upgrades the decision (see TwoStagePolicy's fallback path), so it must not gate readiness.
        override fun onReady(detectorId: String) = events.onReady(ID)

        // Stage 1 gone means there are no candidates at all, so the cascade genuinely cannot fire. Unlike a
        // missing stage 2 (which degrades to single-stage), this is fatal and is reported as such.
        override fun onUnavailable(detectorId: String) = events.onUnavailable(ID)

        override fun onDiagnostic(detectorId: String, message: String) = events.onDiagnostic(ID, "stage-1 ($detectorId): $message")

        override fun onWake(event: WakeEvent) = onStage1Candidate(event)
    }

    override fun start() {
        window.clear() // audio from before a pause must not be verifiable in the next session
        stage1.start()
    }

    override fun accept(buf: ByteArray, n: Int) {
        // Window first: stage 1 can fire synchronously from inside its accept(), and when it does, the
        // frame that triggered it must already be in the window stage 2 will decode.
        window.add(buf, n)
        stage1.accept(buf, n)
    }

    override fun updateWakeWords(words: List<WakeWord>) {
        stage1.updateWakeWords(stage1WakeWords(words))
        // Stage 2 gets the words verbatim: it only needs the phrases, for its grammar. Thresholds are a
        // stage-1 concept — stage 2's answer is categorical (the phrase decoded, or it didn't).
        verifier.updateWakeWords(words) // else a newly registered phrase could never be confirmed
    }

    override fun close() {
        stage1.close()
        verifier.close()
    }

    /**
     * Hot-swap stage 1's per-phrase ONNX classifiers (portal-wake's plugin models). No-op unless stage 1 is
     * an [OpenWakeWordDetector] built for explicit configs — see [OpenWakeWordDetector.updatePhraseModels].
     */
    fun updatePhraseModels(configs: List<OpenWakeWordDetector.PhraseClassifierConfig>) {
        (stage1 as? OpenWakeWordDetector)?.updatePhraseModels(stage1PhraseConfigs(configs))
    }

    /**
     * A stage-1 candidate: apply [TwoStagePolicy], and fire or log accordingly. Runs on the capture thread,
     * synchronously inside [accept].
     */
    private fun onStage1Candidate(event: WakeEvent) {
        val score = event.score
        if (score == null) {
            // Stage 1 reported no score, so neither the bypass nor the fallback can be evaluated. Verify
            // outright when stage 2 is up; otherwise there is no evidence to fire on. Not expected from
            // OpenWakeWordDetector — a custom stage 1 that omits the score gets the safe reading.
            val ok = verifier.state == TwoStagePolicy.VerifierState.READY &&
                timedVerify(event.wakeId)
            report(event, if (ok) TwoStagePolicy.Decision.Fire("stage-2 confirmed (no stage-1 score)") else TwoStagePolicy.Decision.Block("no stage-1 score and stage 2 ${verifier.state.name.lowercase()}"))
            return
        }

        val decision = TwoStagePolicy.decide(
            score = score,
            verifierState = verifier.state,
            verify = { timedVerify(event.wakeId) },
            bypassScore = bypassScore,
            fallbackScore = fallbackScore,
        )
        report(event, decision)
    }

    private fun report(event: WakeEvent, decision: TwoStagePolicy.Decision) {
        when (decision) {
            is TwoStagePolicy.Decision.Fire ->
                events.onWake(event.copy(detectorId = ID, transcript = decision.reason))

            is TwoStagePolicy.Decision.Block ->
                events.onDiagnostic(ID, "near-miss [${event.wakeId}] ${decision.reason}")
        }
    }

    /**
     * Run stage 2, timing it. The budget is not enforced by cancellation — a native decode cannot be
     * interrupted — but an overrun is *reported*, because it means the capture loop lost ground and audio
     * may have been dropped. That is the kind of failure that is invisible without a log line.
     */
    private fun timedVerify(wakeId: String): Boolean {
        val snapshot = window.snapshot()
        val t0 = clock()
        val ok = verifier.verify(snapshot, wakeId)
        val tookMs = clock() - t0
        if (tookMs > verifyBudgetMs) {
            events.onDiagnostic(ID, "stage-2 decode took ${tookMs}ms (budget ${verifyBudgetMs}ms) — capture may have dropped audio")
        }
        return ok
    }

    companion object {
        const val ID = "two-stage"

        /**
         * Stage 1's view of the wake set, with any phrase still carrying the **generic**
         * [WakeWord.DEFAULT_SCORE_THRESHOLD] loosened to [TwoStageTuning.DEFAULT_STAGE1_THRESHOLD].
         *
         * This is load-bearing, not a nicety. openWakeWord's standalone default is 0.50 — chosen to be safe
         * with *no* second stage — and at 0.50 stage 1 already rejects the far-field and noisy utterances
         * the cascade exists to recover (73% recall vs 92% at 0.30). Left alone, the cascade would quietly
         * be *worse* than the measurements predict and stage 2 would have almost nothing to filter: the
         * regression would look like "it works", just with the recall the port was meant to buy missing.
         *
         * A phrase that declares its own threshold keeps it — a plugin's `com.portal.wake.min_confidence`
         * **is** its stage-1 threshold. The one ambiguity is a plugin that explicitly declares 0.50: it is
         * indistinguishable from "declared nothing" and gets loosened too. That is the safe direction
         * behind a verifier (a looser stage 1 only means more candidates for stage 2 to reject), and it is
         * why precision here comes from stage 2 rather than from a per-phrase floor.
         */
        internal fun stage1WakeWords(words: List<WakeWord>): List<WakeWord> = words.map { w ->
            if (w.scoreThreshold == WakeWord.DEFAULT_SCORE_THRESHOLD) {
                w.copy(scoreThreshold = TwoStageTuning.DEFAULT_STAGE1_THRESHOLD)
            } else {
                w
            }
        }

        /** [stage1WakeWords] for the explicit per-phrase model path (portal-wake's plugin classifiers). */
        internal fun stage1PhraseConfigs(
            configs: List<OpenWakeWordDetector.PhraseClassifierConfig>,
        ): List<OpenWakeWordDetector.PhraseClassifierConfig> = configs.map { c ->
            if (c.scoreThreshold == WakeWord.DEFAULT_SCORE_THRESHOLD.toFloat()) {
                c.copy(scoreThreshold = TwoStageTuning.DEFAULT_STAGE1_THRESHOLD.toFloat())
            } else {
                c
            }
        }

        /**
         * The cascade with bundled openWakeWord models as stage 1 and Vosk as stage 2.
         *
         * @param modelDir stage 2's Vosk model: null = bundled `assets/model-en-us` (portal-wake);
         *   a directory = an already-unpacked, downloaded model (portal-assistant on gen2).
         */
        fun factory(modelDir: File? = null): WakeDetector.Factory = factory(OpenWakeWordDetector.factory(), modelDir)

        /** The cascade with explicit per-phrase stage-1 ONNX models (portal-wake plugin models). */
        fun factory(
            phraseConfigs: List<OpenWakeWordDetector.PhraseClassifierConfig>,
            modelDir: File? = null,
        ): WakeDetector.Factory = factory(OpenWakeWordDetector.factory(stage1PhraseConfigs(phraseConfigs)), modelDir)

        /** The cascade over an arbitrary stage-1 detector — the seam unit tests and benchmarks use. */
        fun factory(stage1: WakeDetector.Factory, modelDir: File? = null): WakeDetector.Factory = WakeDetector.Factory { host ->
            TwoStageWakeDetector(
                host = host,
                stage1Factory = stage1,
                verifierFactory = { h, diag -> VoskPhraseVerifier(h.context, h.wakeWords, diag, modelDir) },
            )
        }
    }
}
