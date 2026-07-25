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
     * (~2.8 s on the Portal) or absent entirely. Never consulted once stage 2 is ready.
     *
     * ⚠️ **This is a damage-limitation value, not a safe one.** It was chosen because 0.50 rejects the
     * three original meeting false accepts (0.451–0.477). Two *confirmed* false accepts have since been
     * measured **above** it — **0.775** and **0.998**, both on ordinary conversation (`PHASE_B.md` §1g/§1h)
     * — so single-stage operation at this threshold measures **2 false accepts in 4.78 h (~0.42/h)**.
     *
     * There is no better number available: §1g established that **no** stage-1 threshold separates wakes
     * from ordinary speech, so any single-stage fallback is a compromise between false accepts and going
     * deaf. It is retained because the exposure is normally trivial — a ~2.8 s window at startup, during
     * which the odds of encountering such an event are negligible.
     *
     * The **permanent** case is the one to watch: a consumer whose Vosk model is genuinely absent runs at
     * this rate forever. portal-wake bundles the model in its APK, so that path should be unreachable
     * there; a consumer that *downloads* the model (portal-assistant on gen2) should treat a persistent
     * [TwoStagePolicy.VerifierState.UNAVAILABLE] as a fault to surface, not a steady state to live in.
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
     * (see `TwoStageWakeDetector`), so this is a real constraint, not a target: `AudioRecordPcmDevice`
     * sizes its `AudioRecord` buffer at `max(minBuf, FRAME_BYTES * 16)` = **≥1.6 s**, and
     * `PcmCaptureSession` reads non-blocking. A decode that stays inside that buffer is absorbed and the
     * loop catches up on the following reads; one that outruns it drops audio.
     *
     * **Measured on the Portal, 2026-07-25 — the earlier "~100–300 ms" claim was wrong by ~3×.**
     * Over 17 decodes on the real capture thread:
     *
     * | min | median | mean | max |
     * |---|---|---|---|
     * | 755 ms | 877 ms | 922 ms | 1079 ms |
     *
     * Not warm-up: the first was 1053 ms and the distribution stayed flat at ~900 ms all session. All
     * 17 overran the 400 ms budget this constant used to hold, i.e. the report had stopped carrying
     * information — it fired on *every* candidate rather than flagging an anomaly.
     *
     * 1200 ms sits above the worst decode seen with a little room, so an overrun is once again a genuine
     * signal worth reading.
     *
     * ⚠️ **Raising this number is not, by itself, a fix.** The budget only controls *reporting*; what
     * protects capture is the buffer in `AudioRecordPcmDevice`. Both were changed together (400 ms → 1.6 s
     * of buffer, 400 ms → 1200 ms of budget). Raising the budget alone would silence the warning and leave
     * the dropped audio exactly where it was — do not "fix" a future overrun by tuning this constant on
     * its own. If decodes get slower, enlarge the buffer first and re-measure.
     *
     * Why the dropped audio mattered, precisely — the harm is narrower than "17 of 17" sounds. After a
     * **confirmed** fire it is harmless: capture pauses for the microphone handoff anyway. After a
     * **stage-2 rejection** it bites, because capture continues and a fast retry can land in the dropped
     * window (the one observed water retry took 4.5 s, so it survived; a ~1 s retry might not have).
     * A sub-threshold near-miss never runs a decode at all.
     *
     * Candidates are rare (2 in 46 min of kitchen negatives at stage-1 0.30), so the amortised cost stays
     * negligible either way. See `../hey-jarvis/HANDOFF_PHASE_E.md` §4a and `HANDOFF_MEASUREMENT.md` §1.
     */
    const val VERIFY_BUDGET_MS = 1_200L
}
