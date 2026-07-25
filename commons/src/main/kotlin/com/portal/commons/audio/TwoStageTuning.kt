package com.portal.commons.audio

/**
 * Tuning for the **two-stage (cascade)** wake detector — [TwoStagePolicy] and `TwoStageWakeDetector`.
 *
 * Every value here is justified by measurement on the Portal, not inherited from a default. The
 * measurement lives in `hey-jarvis/` (`RESULTS.md`, `PHASE_A.md`, `PHASE_B.md`); the short version:
 *
 * Thresholding a single detector is **exhausted** as a tuning lever. Three confirmed false accepts
 * captured live during recorded-meeting playback scored **0.451–0.477** — the same band as genuine
 * far-field and noisy utterances (0.30–0.50). No single threshold separates them. The cascade does:
 * stage 1 runs loose for recall, stage 2 (a phrase-constrained Vosk decode of the same 2 s window) throws
 * out what merely *sounds* like the phrase. On the saved audit clips, stage 2 rejects all three false
 * accepts and confirms all four genuine utterances.
 *
 * Kept in `:commons` (pure JVM, Android-free) beside [TwoStagePolicy] so the numbers and the decision
 * that consumes them sit together and are unit-tested as one.
 */
object TwoStageTuning {

    /**
     * Stage-1 (openWakeWord) threshold: **loose on purpose**. Recall at this setting is ~92% on MIC and
     * ~100% on VOICE_RECOGNITION, at the cost of candidates stage 2 then has to reject — which is exactly
     * the trade the cascade exists to make. Shipping openWakeWord alone at this value produced 0.5 FA/h
     * plus 3 meeting false accepts; behind stage 2 it produced none.
     *
     * This is the **default** for a wake word that doesn't declare its own. A plugin's declared
     * `com.portal.wake.min_confidence` becomes that phrase's [WakeWord.scoreThreshold], i.e. its
     * *stage-1* threshold — precision comes from stage 2, not from a per-phrase floor.
     */
    const val DEFAULT_STAGE1_THRESHOLD = 0.30

    /**
     * Sentinel for [BYPASS_SCORE]: no score in [0, 1] can reach it, so stage 2 is **always** consulted.
     */
    const val BYPASS_DISABLED = Float.POSITIVE_INFINITY

    /**
     * The bypass threshold that was shipped until 2026-07-24, kept only for a consumer capturing on
     * `AudioSource.MIC`, where it demonstrably rescued genuine utterances (see [BYPASS_SCORE]).
     * **Do not use it on `VOICE_RECOGNITION`.**
     */
    const val BYPASS_SCORE_MIC = 0.85f

    /**
     * Auto-accept without running stage 2 — **disabled**, and the reasoning matters.
     *
     * The idea was that a very confident stage 1 outranks stage 2, which is *less* noise-robust for the
     * phrase: on MIC, under running water, Vosk rejected two genuine utterances that openWakeWord scored
     * 0.94 and 0.98. A 0.85 bypass recovered them, and it was justified by the observation that **every
     * false accept ever recorded scored ≤ 0.48** — so the bypass sat far above the entire observed
     * false-accept band.
     *
     * **That premise is false.** In 45 min of TV heard through a wall, openWakeWord scored **0.998** on the
     * words *"just relax, it's too much, I got it"* — ordinary unrelated speech, at essentially maximum
     * confidence. Stage 2 rejected it (`hey [unk]`); the bypass would have fired. See `PHASE_B.md` §1g.
     *
     * Two consequences:
     *  1. **No stage-1 threshold is safe.** A bypass cannot be raised out of danger, because there is
     *     nothing above 0.998 to raise it to. The only sound design is to let stage 2 veto *everything*.
     *  2. On `VOICE_RECOGNITION` the bypass was barely earning anything anyway: measured over 80
     *     utterances it rescued exactly **one** (99% → 100% recall) while costing **one false accept** in
     *     3.7 h. For an always-on device that hands off the microphone on a fire, that is a bad trade —
     *     a missed wake is repeated in a second, a false handoff interrupts whatever is happening.
     *
     * Set to [BYPASS_SCORE_MIC] only if you capture on `AudioSource.MIC` and have re-measured.
     */
    const val BYPASS_SCORE = BYPASS_DISABLED

    /**
     * Single-stage threshold used **only while stage 2 is unusable** — the Vosk model is still loading
     * (~2.8 s on the Portal) or absent entirely. Degrading to openWakeWord-only at a *safe* threshold
     * keeps the detector deaf-proof: 0.50 is the value that rejects all three recorded meeting false
     * accepts, at a known cost in far-field recall. Never used once stage 2 is ready.
     */
    const val FALLBACK_SCORE = 0.50f

    /**
     * Audio stage 2 re-decodes, in milliseconds — the same 2 s that openWakeWord's classifier window
     * covers, so stage 2 sees exactly what stage 1 scored. Shorter would clip the phrase; longer would
     * admit unrelated speech into the decode and cost precision.
     */
    const val VERIFY_WINDOW_MS = 2_000

    /**
     * Wall-clock budget for one stage-2 decode. Stage 2 runs **synchronously on the capture thread**
     * (see `TwoStageWakeDetector`), so this is a real constraint, not a target:
     * `AudioRecordPcmDevice` sizes its `AudioRecord` buffer at `max(minBuf, FRAME_BYTES * 4)` = **≥400 ms**,
     * and `PcmCaptureSession` reads non-blocking. A decode that stays inside this budget is absorbed by
     * that buffer and the loop catches up on the following reads; one that overruns it drops audio.
     *
     * Measured cost is ~100–300 ms. Candidates are rare (2 in 46 min of kitchen negatives at stage-1
     * 0.30), so the amortised cost is negligible — but a single overrun is a real gap in capture, which is
     * why `TwoStageWakeDetector` times every decode and reports one that exceeds this.
     */
    const val VERIFY_BUDGET_MS = 400L
}
