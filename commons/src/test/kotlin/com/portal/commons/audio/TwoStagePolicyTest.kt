package com.portal.commons.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cascade's accuracy rules: accept-on-verify, accept-on-bypass, reject, and the single-stage
 * degradation while stage 2 is down. Pure — no Vosk, no ONNX, no Android.
 */
class TwoStagePolicyTest {

    private var verifyCalls = 0

    private fun decide(
        score: Float,
        state: TwoStagePolicy.VerifierState = TwoStagePolicy.VerifierState.READY,
        verified: Boolean = true,
        // The shipped default is BYPASS_DISABLED; tests that exercise the bypass opt in explicitly.
        bypass: Float = TwoStageTuning.BYPASS_SCORE,
    ) = TwoStagePolicy.decide(
        score = score,
        verifierState = state,
        verify = {
            verifyCalls++
            verified
        },
        bypassScore = bypass,
    )

    private val micBypass = TwoStageTuning.BYPASS_SCORE_MIC

    private fun fired(d: TwoStagePolicy.Decision) = d is TwoStagePolicy.Decision.Fire

    // ---- the AND: stage 2 has the final say ---------------------------------------------------------

    @Test fun firesWhenStageTwoConfirms() {
        assertTrue(fired(decide(score = 0.45f, verified = true)))
    }

    @Test fun blocksWhenStageTwoRejects() {
        // The load-bearing case: 0.45 is precisely the band the three recorded meeting false accepts sat
        // in (0.451-0.477), where no stage-1 threshold could separate them from genuine far-field speech.
        assertFalse(fired(decide(score = 0.45f, verified = false)))
    }

    @Test fun blocksEvenJustBelowTheBypass() {
        val d = decide(score = micBypass - 0.01f, verified = false, bypass = micBypass)
        assertFalse(fired(d))
        assertEquals(1, verifyCalls)
    }

    // ---- the bypass: a very confident stage 1 outranks stage 2 ---------------------------------------

    @Test fun bypassFiresWithoutConsultingStageTwo() {
        // Stage 2 is less noise-robust for the phrase than stage 1 — it rejected genuine utterances scoring
        // 0.94/0.98 under running water. Above the bypass, stage 1 wins and the decode is skipped entirely.
        assertTrue(fired(decide(score = 0.94f, verified = false, bypass = micBypass)))
        assertEquals(0, verifyCalls)
    }

    @Test fun bypassIsInclusiveAtItsThreshold() {
        assertTrue(fired(decide(score = micBypass, verified = false, bypass = micBypass)))
        assertEquals(0, verifyCalls)
    }

    @Test fun bypassAppliesEvenWhileStageTwoIsLoading() {
        val d = decide(score = 0.94f, state = TwoStagePolicy.VerifierState.LOADING, verified = false, bypass = micBypass)
        assertTrue(fired(d))
        assertTrue(d.reason.contains("bypass"))
    }

    @Test fun bypassIsDisabledByDefaultBecauseNoThresholdIsSafe() {
        // The bypass was justified by "every false accept ever recorded scored <= 0.48". That premise was
        // FALSIFIED on 2026-07-24: openWakeWord scored 0.998 on "just relax, it's too much, I got it" —
        // ordinary speech from a TV through a wall. There is nothing above 0.998 to raise a bypass to, so
        // the only sound design is to let stage 2 veto everything. See PHASE_B.md section 1g.
        val realFalseAccept = 0.998f
        assertFalse("no score may reach the shipped bypass", realFalseAccept >= TwoStageTuning.BYPASS_SCORE)
        assertTrue("the old MIC bypass would have fired on it", realFalseAccept >= TwoStageTuning.BYPASS_SCORE_MIC)
        // With the shipped default, that event is handed to stage 2, which rejected it.
        assertFalse(fired(decide(score = realFalseAccept, verified = false)))
    }

    // ---- degradation: stage 2 loading or absent -------------------------------------------------------

    @Test fun fallbackFiresAboveThresholdWhileLoading() {
        val d = decide(score = 0.60f, state = TwoStagePolicy.VerifierState.LOADING, verified = false)
        assertTrue(fired(d))
        assertEquals(0, verifyCalls) // never consulted while loading
        assertTrue(d.reason.contains("loading"))
    }

    @Test fun fallbackBlocksBelowThresholdWhileLoading() {
        assertFalse(fired(decide(score = 0.45f, state = TwoStagePolicy.VerifierState.LOADING)))
    }

    @Test fun fallbackRejectsTheOriginalMeetingFalseAccepts() {
        // The three false accepts FALLBACK_SCORE was chosen against.
        listOf(0.451f, 0.459f, 0.477f).forEach { fa ->
            assertFalse(fired(decide(score = fa, state = TwoStagePolicy.VerifierState.LOADING)))
        }
    }

    @Test fun fallbackIsDamageLimitationNotSafety() {
        // Two CONFIRMED false accepts on ordinary conversation sit ABOVE the fallback (PHASE_B.md 1g/1h),
        // so single-stage operation fires on them. Locked in as a test because it bounds what the
        // degraded mode is worth: it is a brief-exposure compromise, not a safe operating point. There is
        // no better number — no stage-1 threshold separates wakes from speech.
        listOf(0.775f, 0.998f).forEach { fa ->
            assertTrue(
                "single-stage fallback fires on a confirmed false accept at $fa",
                fired(decide(score = fa, state = TwoStagePolicy.VerifierState.LOADING, verified = false)),
            )
            // ...but with stage 2 up, the same event is correctly blocked.
            assertFalse(fired(decide(score = fa, verified = false)))
        }
    }

    @Test fun unavailableStageTwoDegradesToSingleStageRatherThanGoingDeaf() {
        val d = decide(score = 0.60f, state = TwoStagePolicy.VerifierState.UNAVAILABLE, verified = false)
        assertTrue(fired(d))
        assertTrue(d.reason.contains("unavailable"))
    }

    @Test fun fallbackIsInclusiveAtItsThreshold() {
        val d = decide(score = TwoStageTuning.FALLBACK_SCORE, state = TwoStagePolicy.VerifierState.UNAVAILABLE)
        assertTrue(fired(d))
    }

    // ---- the verify lambda is called at most once, and only when needed -------------------------------

    @Test fun verifyRunsExactlyOnceOnTheVerifyPath() {
        decide(score = 0.45f, verified = true)
        assertEquals(1, verifyCalls)
    }

    // ---- reasons are legible: they are what debug.txt shows after the fact ----------------------------

    @Test fun reasonsCarryTheScore() {
        assertTrue(decide(score = 0.45f, verified = true).reason.contains("0.45"))
        assertTrue(decide(score = 0.45f, verified = false).reason.contains("0.45"))
    }

    @Test fun scoreFormattingIsTwoDecimalsAndLocaleIndependent() {
        // Rendered by hand rather than String.format so a device locale using ',' as the decimal separator
        // can't change what lands in the log.
        assertTrue(decide(score = 0.5f, state = TwoStagePolicy.VerifierState.UNAVAILABLE).reason.contains("0.50"))
        assertTrue(decide(score = 1.0f, verified = false).reason.contains("1.00"))
    }

    @Test fun blockedReasonNamesStageTwo() {
        assertTrue(decide(score = 0.45f, verified = false).reason.contains("stage-2 rejected"))
    }

    // ---- the tuning constants hold the relationship the policy assumes --------------------------------

    @Test fun bypassSitsAboveFallbackSoTheFallbackPathIsReachable() {
        assertTrue(TwoStageTuning.BYPASS_SCORE > TwoStageTuning.FALLBACK_SCORE)
        assertTrue(TwoStageTuning.BYPASS_SCORE_MIC > TwoStageTuning.FALLBACK_SCORE)
    }

    @Test fun stageOneThresholdIsLooserThanTheFallback() {
        // Stage 1 runs loose *because* stage 2 backs it. If it were >= the fallback, the cascade would
        // never see the low-scoring far-field utterances it exists to recover.
        assertTrue(TwoStageTuning.DEFAULT_STAGE1_THRESHOLD < TwoStageTuning.FALLBACK_SCORE.toDouble())
    }

    @Test fun verifyWindowMatchesTheClassifierWindow() {
        assertEquals(2_000, TwoStageTuning.VERIFY_WINDOW_MS)
    }
}
