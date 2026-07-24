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
    ) = TwoStagePolicy.decide(
        score = score,
        verifierState = state,
        verify = {
            verifyCalls++
            verified
        },
    )

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
        val d = decide(score = TwoStageTuning.BYPASS_SCORE - 0.01f, verified = false)
        assertFalse(fired(d))
        assertEquals(1, verifyCalls)
    }

    // ---- the bypass: a very confident stage 1 outranks stage 2 ---------------------------------------

    @Test fun bypassFiresWithoutConsultingStageTwo() {
        // Stage 2 is less noise-robust for the phrase than stage 1 — it rejected genuine utterances scoring
        // 0.94/0.98 under running water. Above the bypass, stage 1 wins and the decode is skipped entirely.
        assertTrue(fired(decide(score = 0.94f, verified = false)))
        assertEquals(0, verifyCalls)
    }

    @Test fun bypassIsInclusiveAtItsThreshold() {
        assertTrue(fired(decide(score = TwoStageTuning.BYPASS_SCORE, verified = false)))
        assertEquals(0, verifyCalls)
    }

    @Test fun bypassAppliesEvenWhileStageTwoIsLoading() {
        val d = decide(score = 0.94f, state = TwoStagePolicy.VerifierState.LOADING, verified = false)
        assertTrue(fired(d))
        assertTrue(d.reason.contains("bypass"))
    }

    @Test fun bypassSitsAboveEveryRecordedFalseAccept() {
        // Every false accept ever captured scored <= 0.48; the bypass is only safe while that holds.
        listOf(0.451f, 0.459f, 0.477f).forEach { fa ->
            assertFalse("$fa must not reach the bypass", fa >= TwoStageTuning.BYPASS_SCORE)
        }
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

    @Test fun fallbackRejectsTheRecordedMeetingFalseAccepts() {
        // The fallback threshold has to be safe on its own — it is the only gate during the ~2.8 s the Vosk
        // model takes to load, and permanently when no model is installed.
        listOf(0.451f, 0.459f, 0.477f).forEach { fa ->
            assertFalse(fired(decide(score = fa, state = TwoStagePolicy.VerifierState.LOADING)))
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
