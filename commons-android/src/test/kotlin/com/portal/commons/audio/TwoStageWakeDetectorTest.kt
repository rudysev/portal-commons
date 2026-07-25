package com.portal.commons.audio

import android.app.Application
import android.content.Context
import com.portal.commons.PcmCaptureFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring and composition of [TwoStageWakeDetector], with both stages faked: that stage-1 fires are
 * *intercepted* rather than forwarded, that stage 2 is handed the right audio and the right wake id, and
 * that the cascade presents itself to the engine as a single detector.
 *
 * The accuracy rules themselves live in `TwoStagePolicyTest` (pure JVM, no Android).
 */
class TwoStageWakeDetectorTest {

    private val appContext: Context = Application()

    // ---- fakes ---------------------------------------------------------------------------------------

    /** Stage 1 stand-in. Fires from inside [accept], exactly as [OpenWakeWordDetector] does. */
    private class FakeStage1(val host: WakeDetector.Host) : WakeDetector {
        override val id = ID
        var starts = 0
        var closes = 0
        val acceptedBytes = mutableListOf<Int>()
        val wakeSetUpdates = mutableListOf<List<WakeWord>>()

        /** Set to make the next [accept] report a candidate. */
        var fireOnAccept: WakeEvent? = null

        override fun start() {
            starts++
        }
        override fun accept(buf: ByteArray, n: Int) {
            acceptedBytes.add(n)
            fireOnAccept?.let { host.events.onWake(it) }
        }
        override fun updateWakeWords(words: List<WakeWord>) {
            wakeSetUpdates.add(words)
        }
        override fun close() {
            closes++
        }

        companion object {
            const val ID = "fake-stage1"
        }
    }

    private class FakeVerifier : WakePhraseVerifier {
        override var state: TwoStagePolicy.VerifierState = TwoStagePolicy.VerifierState.READY
        var confirms = true
        val windows = mutableListOf<ShortArray>()
        val wakeIds = mutableListOf<String>()
        val wakeSetUpdates = mutableListOf<List<WakeWord>>()
        var closes = 0

        override fun verify(window: ShortArray, wakeId: String): Boolean {
            windows.add(window)
            wakeIds.add(wakeId)
            return confirms
        }
        override fun updateWakeWords(words: List<WakeWord>) {
            wakeSetUpdates.add(words)
        }
        override fun close() {
            closes++
        }
    }

    /** Audit sink stand-in: records what would have been written, without touching a disk. */
    private class FakeAudit : WakeAudit {
        class Saved(val clip: WakeAudit.Clip, val wakeId: String, val score: Float, val pcm: ShortArray)

        val saved = mutableListOf<Saved>()
        override fun save(clip: WakeAudit.Clip, wakeId: String, score: Float, pcm: ShortArray) {
            saved.add(Saved(clip, wakeId, score, pcm))
        }
    }

    private class Recorder : WakeDetector.Events {
        val ready = mutableListOf<String>()
        val unavailable = mutableListOf<String>()
        val wakes = mutableListOf<WakeEvent>()
        val diagnostics = mutableListOf<String>()
        val nearMisses = mutableListOf<Triple<String, String, Float>>()
        override fun onNearMiss(detectorId: String, wakeId: String, score: Float) {
            nearMisses.add(Triple(detectorId, wakeId, score))
        }
        override fun onReady(detectorId: String) {
            ready.add(detectorId)
        }
        override fun onUnavailable(detectorId: String) {
            unavailable.add(detectorId)
        }
        override fun onWake(event: WakeEvent) {
            wakes.add(event)
        }
        override fun onDiagnostic(detectorId: String, message: String) {
            diagnostics.add("$detectorId: $message")
        }
    }

    // ---- harness -------------------------------------------------------------------------------------

    private val jarvis = WakeWord.fromPhrase("hey jarvis")!!
    private val alexa = WakeWord.fromPhrase("hey alexa", id = "alexa")!!

    private lateinit var stage1: FakeStage1
    private val verifier = FakeVerifier()
    private val events = Recorder()
    private val audit = FakeAudit()
    private var now = 0L

    private fun build(
        words: List<WakeWord> = listOf(jarvis),
        verifyBudgetMs: Long = TwoStageTuning.VERIFY_BUDGET_MS,
        stage2: WakePhraseVerifier = verifier,
        // Shipped default is BYPASS_DISABLED; bypass tests opt in explicitly.
        bypassScore: Float = TwoStageTuning.BYPASS_SCORE,
        // Default null: capture is opt-in, and every other test must exercise the no-audit path.
        audit: WakeAudit? = null,
    ): TwoStageWakeDetector {
        val host = object : WakeDetector.Host {
            override val context: Context = appContext
            override val wakeWords: List<WakeWord> = words
            override val events: WakeDetector.Events = this@TwoStageWakeDetectorTest.events
            override val handoffCooldown: WakeHandoffCooldown = object : WakeHandoffCooldown {
                override fun isCoolingDown(wakeId: String) = false
                override fun isAnyCoolingDown() = false
            }
        }
        return TwoStageWakeDetector(
            host = host,
            stage1Factory = { h -> FakeStage1(h).also { stage1 = it } },
            verifierFactory = { _, _ -> stage2 },
            bypassScore = bypassScore,
            verifyBudgetMs = verifyBudgetMs,
            clock = { now },
            audit = audit,
        )
    }

    /** One capture frame whose samples all equal [value], so window contents are identifiable. */
    private fun frame(value: Int): ByteArray {
        val out = ByteArray(PcmCaptureFormat.FRAME_BYTES)
        for (i in out.indices step 2) {
            out[i] = (value and 0xFF).toByte()
            out[i + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun candidate(score: Float, wakeId: String = "jarvis") = WakeEvent(FakeStage1.ID, wakeId, "score=$score", score = score)

    // ---- the AND ---------------------------------------------------------------------------------------

    @Test fun firesWhenStageTwoConfirms() {
        val d = build()
        verifier.confirms = true
        stage1.fireOnAccept = candidate(0.45f)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)

        assertEquals(1, events.wakes.size)
        assertEquals("jarvis", events.wakes[0].wakeId)
    }

    @Test fun swallowsTheFireWhenStageTwoRejects() {
        val d = build()
        verifier.confirms = false
        stage1.fireOnAccept = candidate(0.45f)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)

        assertTrue("stage-1 fire must not reach the engine", events.wakes.isEmpty())
        assertTrue(events.diagnostics.any { it.contains("near-miss") && it.contains("stage-2 rejected") })
    }

    @Test fun bypassSkipsStageTwoEntirely() {
        val d = build(bypassScore = TwoStageTuning.BYPASS_SCORE_MIC)
        verifier.confirms = false
        stage1.fireOnAccept = candidate(0.94f)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)

        assertEquals(1, events.wakes.size)
        assertTrue("the decode must be short-circuited", verifier.windows.isEmpty())
    }

    @Test fun byDefaultEvenAMaximalScoreIsVerified() {
        // The bypass is off by default: openWakeWord has been measured at 0.998 on ordinary speech, so no
        // score may skip stage 2. See TwoStageTuning.BYPASS_SCORE.
        val d = build()
        verifier.confirms = false
        stage1.fireOnAccept = candidate(0.998f)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)
        assertTrue("a 0.998 false accept must still be vetoed", events.wakes.isEmpty())
        assertEquals(1, verifier.windows.size)
    }

    @Test fun fallsBackToSingleStageWhileStageTwoLoads() {
        val d = build()
        verifier.state = TwoStagePolicy.VerifierState.LOADING
        stage1.fireOnAccept = candidate(0.60f)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)

        assertEquals(1, events.wakes.size)
        assertTrue(verifier.windows.isEmpty())
    }

    // ---- what stage 2 is handed ------------------------------------------------------------------------

    @Test fun stageTwoVerifiesAgainstTheCandidatesOwnWakeId() {
        // Confirming a "jarvis" candidate with a decode of some other registered phrase would be a
        // cross-phrase false accept, so the id has to travel with the window.
        val d = build(words = listOf(jarvis, alexa))
        stage1.fireOnAccept = candidate(0.45f, wakeId = "alexa")
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)

        assertEquals(listOf("alexa"), verifier.wakeIds)
    }

    @Test fun stageTwoSeesTheFrameThatTriggeredTheFire() {
        // The window must be updated BEFORE stage 1 runs: stage 1 fires synchronously from inside accept(),
        // and the frame carrying the end of the phrase is the one that triggers it.
        val d = build()
        stage1.fireOnAccept = candidate(0.45f)
        d.accept(frame(7), PcmCaptureFormat.FRAME_BYTES)

        val window = verifier.windows.single()
        assertEquals(PcmCaptureFormat.FRAME_BYTES / 2, window.size)
        assertTrue("window must hold the triggering frame", window.all { it.toInt() == 7 })
    }

    @Test fun stageTwoSeesTheNewestAudioOldestFirst() {
        val d = build()
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)
        stage1.fireOnAccept = candidate(0.45f)
        d.accept(frame(2), PcmCaptureFormat.FRAME_BYTES)

        val window = verifier.windows.single()
        val samplesPerFrame = PcmCaptureFormat.FRAME_BYTES / 2
        assertEquals(2 * samplesPerFrame, window.size)
        assertEquals(1, window.first().toInt())
        assertEquals(2, window.last().toInt())
    }

    @Test fun windowSpansTheFullVerifyWindow() {
        val d = build()
        val frames = TwoStageTuning.VERIFY_WINDOW_MS / PcmCaptureFormat.FRAME_MS
        repeat(frames + 5) { d.accept(frame(3), PcmCaptureFormat.FRAME_BYTES) }
        stage1.fireOnAccept = candidate(0.45f)
        d.accept(frame(4), PcmCaptureFormat.FRAME_BYTES)

        val expected = PcmCaptureFormat.SAMPLE_RATE * TwoStageTuning.VERIFY_WINDOW_MS / 1000
        assertEquals(expected, verifier.windows.single().size)
    }

    @Test fun startClearsTheWindowSoPrePauseAudioIsNotVerified() {
        val d = build()
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)
        d.start()
        stage1.fireOnAccept = candidate(0.45f)
        d.accept(frame(2), PcmCaptureFormat.FRAME_BYTES)

        val window = verifier.windows.single()
        assertEquals(PcmCaptureFormat.FRAME_BYTES / 2, window.size)
        assertTrue(window.all { it.toInt() == 2 })
    }

    // ---- the cascade presents as one detector ----------------------------------------------------------

    @Test fun reportsReadinessUnderTheCascadeId() {
        val d = build()
        stage1.host.events.onReady(FakeStage1.ID)
        assertEquals(listOf(TwoStageWakeDetector.ID), events.ready)
        assertEquals(TwoStageWakeDetector.ID, d.id)
    }

    @Test fun missingStageOneIsFatalForTheCascade() {
        build()
        stage1.host.events.onUnavailable(FakeStage1.ID)
        assertEquals(listOf(TwoStageWakeDetector.ID), events.unavailable)
    }

    @Test fun firedEventsCarryTheCascadeId() {
        val d = build()
        stage1.fireOnAccept = candidate(0.45f)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)
        assertEquals(TwoStageWakeDetector.ID, events.wakes.single().detectorId)
    }

    @Test fun firedEventsExplainWhichRuleFired() {
        val d = build(bypassScore = TwoStageTuning.BYPASS_SCORE_MIC)
        stage1.fireOnAccept = candidate(0.94f)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)
        assertTrue(events.wakes.single().transcript.contains("bypass"))
    }

    @Test fun stageOneDiagnosticsAreAttributed() {
        build()
        stage1.host.events.onDiagnostic(FakeStage1.ID, "flushing 3 pre-ready frame(s)")
        assertTrue(events.diagnostics.single().contains("stage-1 (${FakeStage1.ID})"))
    }

    // ---- lifecycle passthrough -------------------------------------------------------------------------

    @Test fun startStartsStageOne() {
        val d = build()
        d.start()
        assertEquals(1, stage1.starts)
    }

    @Test fun everyFrameReachesStageOne() {
        val d = build()
        repeat(3) { d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES) }
        assertEquals(listOf(PcmCaptureFormat.FRAME_BYTES, PcmCaptureFormat.FRAME_BYTES, PcmCaptureFormat.FRAME_BYTES), stage1.acceptedBytes)
    }

    @Test fun wakeSetChangesReachBothStages() {
        // Stage 2 builds its grammar from the wake set; a plugin's new phrase could never be confirmed if
        // the update stopped at stage 1.
        val d = build()
        val words = listOf(jarvis, alexa)
        d.updateWakeWords(words)
        // Stage 1 gets the loosened view (see stageOneIsLoosenedFromTheStandaloneDefault); stage 2 gets the
        // set verbatim, since it only needs the phrases.
        assertEquals(listOf(TwoStageWakeDetector.stage1WakeWords(words)), stage1.wakeSetUpdates)
        assertEquals(listOf(words), verifier.wakeSetUpdates)
    }

    @Test fun closeTearsDownBothStages() {
        val d = build()
        d.close()
        assertEquals(1, stage1.closes)
        assertEquals(1, verifier.closes)
    }

    // ---- the capture-thread budget ---------------------------------------------------------------------

    @Test fun reportsADecodeThatOverrunsItsBudget() {
        // Stage 2 runs on the capture thread; an overrun means AudioRecord's buffer lost ground and audio
        // may have been dropped, which is invisible without this line.
        val slow = object : WakePhraseVerifier by verifier {
            override fun verify(window: ShortArray, wakeId: String): Boolean {
                now += 250
                return verifier.verify(window, wakeId)
            }
        }
        val d = build(verifyBudgetMs = 100, stage2 = slow)
        stage1.fireOnAccept = candidate(0.45f)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)

        assertTrue(events.diagnostics.any { it.contains("250ms") && it.contains("budget 100ms") })
    }

    @Test fun staysSilentWhenTheDecodeIsInsideBudget() {
        val d = build(verifyBudgetMs = 400)
        stage1.fireOnAccept = candidate(0.45f)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)
        assertFalse(events.diagnostics.any { it.contains("budget") })
    }

    // ---- stage 1 must run LOOSE, or the cascade is pointless -------------------------------------------

    @Test fun stageOneIsLoosenedFromTheStandaloneDefault() {
        // openWakeWord's 0.50 default is calibrated for running *without* a second stage; at 0.50 it
        // already rejects the far-field utterances the cascade exists to recover (73% vs 92% recall).
        val loosened = TwoStageWakeDetector.stage1WakeWords(listOf(jarvis)).single()
        assertEquals(WakeWord.DEFAULT_SCORE_THRESHOLD, jarvis.scoreThreshold, 1e-9)
        assertEquals(TwoStageTuning.DEFAULT_STAGE1_THRESHOLD, loosened.scoreThreshold, 1e-9)
    }

    @Test fun aDeclaredThresholdSurvivesLoosening() {
        // A plugin's com.portal.wake.min_confidence IS its stage-1 threshold.
        val declared = jarvis.copy(scoreThreshold = 0.7)
        assertEquals(0.7, TwoStageWakeDetector.stage1WakeWords(listOf(declared)).single().scoreThreshold, 1e-9)
    }

    @Test fun looseningLeavesEverythingElseAboutTheWordAlone() {
        val loosened = TwoStageWakeDetector.stage1WakeWords(listOf(jarvis)).single()
        assertEquals(jarvis.copy(scoreThreshold = TwoStageTuning.DEFAULT_STAGE1_THRESHOLD), loosened)
    }

    @Test fun stageOneSeesTheLoosenedWakeSet() {
        val d = build(words = listOf(jarvis))
        assertEquals(TwoStageTuning.DEFAULT_STAGE1_THRESHOLD, stage1.host.wakeWords.single().scoreThreshold, 1e-9)
        d.updateWakeWords(listOf(jarvis))
        assertEquals(TwoStageTuning.DEFAULT_STAGE1_THRESHOLD, stage1.wakeSetUpdates.single().single().scoreThreshold, 1e-9)
    }

    @Test fun stageTwoSeesTheWakeSetVerbatim() {
        // Thresholds are a stage-1 concept; stage 2's answer is categorical.
        val d = build()
        d.updateWakeWords(listOf(jarvis))
        assertEquals(WakeWord.DEFAULT_SCORE_THRESHOLD, verifier.wakeSetUpdates.single().single().scoreThreshold, 1e-9)
    }

    @Test fun explicitPhraseConfigsAreLoosenedToo() {
        val bytes = ByteArray(1)
        val default = OpenWakeWordDetector.PhraseClassifierConfig("jarvis", bytes, WakeWord.DEFAULT_SCORE_THRESHOLD.toFloat())
        val declared = OpenWakeWordDetector.PhraseClassifierConfig("bob", bytes, 0.7f)
        val out = TwoStageWakeDetector.stage1PhraseConfigs(listOf(default, declared))
        assertEquals(TwoStageTuning.DEFAULT_STAGE1_THRESHOLD.toFloat(), out[0].scoreThreshold, 1e-6f)
        assertEquals(0.7f, out[1].scoreThreshold, 1e-6f)
    }

    // ---- a stage 1 that reports no score ---------------------------------------------------------------

    @Test fun aScorelessCandidateStillGoesThroughStageTwo() {
        val d = build()
        verifier.confirms = true
        stage1.fireOnAccept = WakeEvent(FakeStage1.ID, "jarvis", "no score", score = null)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)
        assertEquals(1, events.wakes.size)
        assertEquals(1, verifier.windows.size)
    }

    @Test fun aScorelessCandidateIsBlockedWhenStageTwoIsDown() {
        // With no score there is nothing for the bypass or the fallback to evaluate, so the safe reading is
        // "no evidence" rather than "fire anyway".
        val d = build()
        verifier.state = TwoStagePolicy.VerifierState.UNAVAILABLE
        stage1.fireOnAccept = WakeEvent(FakeStage1.ID, "jarvis", "no score", score = null)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)
        assertTrue(events.wakes.isEmpty())
    }

    // ---- audit clips -----------------------------------------------------------------------------------
    //
    // A live run without these produces scores nobody can interpret: a debug.txt line cannot distinguish a
    // false accept from someone genuinely saying the phrase. See WakeAudit.

    @Test fun aFireIsCaptured() {
        val d = build(audit = audit)
        verifier.confirms = true
        stage1.fireOnAccept = candidate(0.45f)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)

        val saved = audit.saved.single()
        assertEquals(WakeAudit.Clip.WAKE, saved.clip)
        assertEquals("jarvis", saved.wakeId)
        assertEquals(0.45f, saved.score, 1e-6f)
    }

    @Test fun aStageTwoRejectionIsCaptured() {
        // The most valuable clip of the three: this is the cascade earning its keep, and the only way to
        // confirm afterwards that what it threw out was not a real wake.
        val d = build(audit = audit)
        verifier.confirms = false
        stage1.fireOnAccept = candidate(0.46f)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)

        assertEquals(WakeAudit.Clip.REJECTED, audit.saved.single().clip)
    }

    @Test fun aStageOneNearMissIsCaptured() {
        // Below stage 1's threshold, so no candidate is ever raised and the policy never runs — yet this is
        // where a MISSED wake shows up.
        val d = build(audit = audit)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)
        stage1.host.events.onNearMiss(FakeStage1.ID, "jarvis", 0.28f)

        val saved = audit.saved.single()
        assertEquals(WakeAudit.Clip.NEAR, saved.clip)
        assertEquals(0.28f, saved.score, 1e-6f)
        assertTrue("no candidate may be raised by a near-miss", events.wakes.isEmpty())
    }

    @Test fun aNearMissIsAlsoReportedUnderTheCascadeId() {
        build(audit = audit)
        stage1.host.events.onNearMiss(FakeStage1.ID, "jarvis", 0.28f)
        assertEquals(Triple(TwoStageWakeDetector.ID, "jarvis", 0.28f), events.nearMisses.single())
    }

    @Test fun theCapturedClipIsTheAudioTheDecisionWasMadeOn() {
        // A clip that isn't the window stage 2 decoded would reproduce a different score offline, which is
        // exactly what score_clips.py exists to check.
        val d = build(audit = audit)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)
        stage1.fireOnAccept = candidate(0.45f)
        d.accept(frame(2), PcmCaptureFormat.FRAME_BYTES)

        assertArrayEquals(verifier.windows.single(), audit.saved.single().pcm)
    }

    @Test fun aFallbackFireIsCapturedWithoutStageTwoRunning() {
        val d = build(audit = audit)
        verifier.state = TwoStagePolicy.VerifierState.LOADING
        stage1.fireOnAccept = candidate(0.60f)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)

        assertTrue("stage 2 must not have run", verifier.windows.isEmpty())
        assertEquals(WakeAudit.Clip.WAKE, audit.saved.single().clip)
    }

    @Test fun aScorelessCandidateIsCapturedAtZero() {
        val d = build(audit = audit)
        stage1.fireOnAccept = WakeEvent(FakeStage1.ID, "jarvis", "no score", score = null)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)
        assertEquals(0f, audit.saved.single().score, 1e-6f)
    }

    @Test fun nothingIsCapturedWithoutASink() {
        // Capture is opt-in and must cost nothing when off — the detector ships this way to consumers that
        // don't configure a recorder.
        val d = build()
        stage1.fireOnAccept = candidate(0.45f)
        d.accept(frame(1), PcmCaptureFormat.FRAME_BYTES)
        stage1.host.events.onNearMiss(FakeStage1.ID, "jarvis", 0.28f)
        assertTrue(audit.saved.isEmpty())
        assertEquals(1, events.wakes.size)
    }
}
