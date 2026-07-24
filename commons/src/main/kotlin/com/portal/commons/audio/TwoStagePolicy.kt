package com.portal.commons.audio

/**
 * The **cascade decision**, as a pure function — no Vosk, no openWakeWord, no Android, no I/O — so the
 * accuracy policy lives in one obvious place and is fully unit-tested. Same shape as [WakeMatcher] and
 * [IdleResetPolicy]: the mechanism lives in the detector, the *rules* live here.
 *
 * Stage 1 (openWakeWord) has already fired at its own loose per-phrase threshold by the time this is
 * called; this decides whether that candidate becomes a wake:
 *
 * ```
 * score >= BYPASS_SCORE          -> FIRE   (no decode: stage 1 is more noise-robust than stage 2)
 * stage 2 ready                  -> FIRE iff it decodes the phrase
 * stage 2 loading / unavailable  -> FIRE iff score >= FALLBACK_SCORE   (single-stage degradation)
 * ```
 *
 * [verify] is a lambda, not a value, so the **decode only runs when it is actually needed** — the bypass
 * and the fallback paths never pay for it. That matters: a decode costs ~100–300 ms on the capture thread
 * (see [TwoStageTuning.VERIFY_BUDGET_MS]).
 *
 * **This is an AND, not an OR.** Running the two detectors in parallel and firing on either is a *union*
 * of their false accepts; the cascade is an intersection. That distinction is the whole point — see
 * `hey-jarvis/HANDOFF.md` §4a.
 */
object TwoStagePolicy {

    /** Whether stage 2 can be consulted right now. */
    enum class VerifierState {
        /** Model loaded; [decide] may call `verify`. */
        READY,

        /** Model still loading (~2.8 s on the Portal). Transient — the fallback covers this window. */
        LOADING,

        /** No usable model, ever. Permanent — the detector runs single-stage rather than going deaf. */
        UNAVAILABLE,
    }

    /**
     * What to do with a stage-1 candidate. [reason] is human-readable and goes verbatim into `debug.txt` —
     * it is the only way to tell, after the fact, *which* rule fired or blocked a given utterance, which is
     * what made every diagnosis in this project possible.
     */
    sealed interface Decision {
        val reason: String

        /** Hand off. */
        data class Fire(override val reason: String) : Decision

        /** Not a wake. Logged as a near-miss, not acted on. */
        data class Block(override val reason: String) : Decision
    }

    /**
     * Decide a stage-1 candidate scoring [score].
     *
     * @param verifierState whether stage 2 can be consulted; see [VerifierState].
     * @param verify runs the stage-2 decode and reports whether the phrase was actually spoken. Called
     *   **at most once**, and only when neither the bypass nor the fallback path already settled it.
     * @param bypassScore see [TwoStageTuning.BYPASS_SCORE]. Should sit above [fallbackScore]; if it is set
     *   at or below it, the fallback path can never be reached and the cascade silently degrades to
     *   openWakeWord-only whenever stage 2 is down.
     * @param fallbackScore see [TwoStageTuning.FALLBACK_SCORE].
     */
    fun decide(
        score: Float,
        verifierState: VerifierState,
        verify: () -> Boolean,
        bypassScore: Float = TwoStageTuning.BYPASS_SCORE,
        fallbackScore: Float = TwoStageTuning.FALLBACK_SCORE,
    ): Decision {
        // Checked first, and independently of [verifierState]: a very confident stage 1 is *more* reliable
        // than stage 2 for this phrase (stage 2 rejected genuine utterances scoring 0.94/0.98 under running
        // water), and skipping the decode makes the most confident wakes the fastest ones.
        if (score >= bypassScore) return Decision.Fire("bypass (${fmt(score)} >= ${fmt(bypassScore)})")

        return when (verifierState) {
            VerifierState.READY ->
                if (verify()) {
                    Decision.Fire("stage-2 confirmed (${fmt(score)})")
                } else {
                    Decision.Block("stage-2 rejected (${fmt(score)}) — phrase did not decode")
                }

            // Stage 2 can't be consulted. Degrade to single-stage at a threshold known to reject every
            // recorded false accept, rather than firing blind (an OR) or going deaf (an AND against
            // nothing). LOADING is transient; UNAVAILABLE is permanent and is a supported configuration.
            VerifierState.LOADING, VerifierState.UNAVAILABLE ->
                if (score >= fallbackScore) {
                    Decision.Fire("single-stage fallback (${fmt(score)} >= ${fmt(fallbackScore)}, stage 2 ${label(verifierState)})")
                } else {
                    Decision.Block("below fallback (${fmt(score)} < ${fmt(fallbackScore)}, stage 2 ${label(verifierState)})")
                }
        }
    }

    private fun label(state: VerifierState): String = state.name.lowercase()

    /** Two decimals, locale-independent — these strings are compared in tests and read in logs. */
    private fun fmt(v: Float): String {
        val hundredths = Math.round(v * 100)
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }
}
