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
     * Auto-accept without running stage 2. Stage 2 is *less* noise-robust for the phrase than stage 1:
     * under running water it rejected two genuine utterances that openWakeWord scored 0.94 and 0.98. This
     * recovers them.
     *
     * Safe because **every false accept ever recorded scored ≤ 0.48** — the bypass sits far above the
     * entire observed false-accept band. It also short-circuits the decode, so the most confident wakes
     * are the *fastest* ones.
     *
     * ⚠️ Carried over from MIC measurement and **not yet exercised on VOICE_RECOGNITION**: the VR dataset
     * contains no session noisy enough to need a rescue, so every bypass setting scored identically there.
     * See `PHASE_B.md` §1.
     */
    const val BYPASS_SCORE = 0.85f

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
