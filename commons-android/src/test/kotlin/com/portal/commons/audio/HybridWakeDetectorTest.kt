package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives a [HybridWakeDetector] from the test thread, standing in for the capture thread, over the real Vosk
 * [WakeDetector] on the Vosk fakes. The gate scores frames carrying [TONE] samples high; the spotter returns
 * [spotted]. Without the Vosk shadow the live Decoder is never fed, so a queued final belongs to the window check.
 */
class HybridWakeDetectorTest {

    private val lines = CopyOnWriteArrayList<String>()
    private val evidence = CopyOnWriteArrayList<HybridEvidence>()
    private val loader = FakeVoskLoader()
    private val model = FakeVoskModel()

    @Volatile private var mode = WakeMode.HYBRID

    @Volatile private var shadow = false

    @Volatile private var spotted = listOf("HEY_JARVIS")

    @Volatile private var gateFails = false

    // When set, the spotter parks on it, so a test can act while a verdict is pending.
    @Volatile private var spotGate: Gate? = null

    @Volatile private var gatesBuilt = 0

    private val hybrid = HybridWakeDetector(
        HybridWakeConfig(
            wakeId = "jarvis",
            newGate = {
                gatesBuilt++
                check(!gateFails) { "no model" }
                object : WakeGate {
                    override fun score(frame: ShortArray) = if (frame.any { it == TONE }) 0.9f else 0f

                    override fun close() = Unit
                }
            },
            newSpotter = {
                object : KeywordSpotter {
                    override fun spot(samples: FloatArray): List<String> {
                        spotGate?.park()
                        return spotted
                    }

                    override fun close() = Unit
                }
            },
            mode = { mode },
            shadow = { shadow },
            onEvidence = { evidence.add(it) },
        ),
        listOf(JARVIS),
        log = { lines.add(it) },
        clock = { 0L },
    )

    private val detector = WakeDetector(
        loader,
        listOf(JARVIS),
        onReady = {},
        onUnavailable = {},
        diagnostic = { lines.add(it) },
        handoffCooldownMs = 0,
        clock = { 0L },
        onModelAdopted = hybrid::adoptModel,
    )

    @After
    fun tearDown() {
        hybrid.close()
        detector.close()
    }

    private fun frame(value: Short = 0): ByteArray =
        HybridWakeDetector.toBytes(ShortArray(PcmCaptureFormat.FRAME_BYTES / 2) { value })

    /** Start capture, load the model, then speak: 1 s quiet, 200 ms of tone, then quiet. */
    private fun speak() {
        hybrid.onStarted()
        detector.start()
        loader.complete(model)
        repeat(10) { assertNull(hybrid.onFrame(frame(), FRAME, detector)) }
        repeat(2) { assertNull(hybrid.onFrame(frame(TONE), FRAME, detector)) }
    }

    /** Feed quiet frames until one returns a wake, or [maxFrames] pass (each frame ~10 ms apart). */
    private fun awaitWake(maxFrames: Int = 300): WakeEvent? {
        repeat(maxFrames) {
            hybrid.onFrame(frame(), FRAME, detector)?.let { wake -> return wake }
            Thread.sleep(10)
        }
        return null
    }

    private fun awaitCandidate(): HybridEvidence.Candidate {
        repeat(300) {
            evidence.filterIsInstance<HybridEvidence.Candidate>().firstOrNull()?.let { c -> return c }
            hybrid.onFrame(frame(), FRAME, detector)
            Thread.sleep(10)
        }
        throw AssertionError("no candidate; log: $lines")
    }

    @Test
    fun `a spotter confirmation wakes on a later frame, and the candidate records both checks`() {
        speak()
        val wake = awaitWake()
        assertNotNull(wake)
        assertEquals("jarvis", wake!!.wakeId)
        assertTrue(wake.transcript, wake.transcript.startsWith("hybrid by sherpa"))
        val c = awaitCandidate()
        assertEquals("sherpa", c.wokeBy)
        assertEquals("none", c.vosk) // nothing queued for the window check
        assertEquals(0.9f, c.gatePeak)
        assertTrue(model.decoders.size >= 2) // the live Decoder plus the window check's own
    }

    @Test
    fun `the Vosk window check wakes when the spotter heard nothing`() {
        spotted = emptyList()
        model.finals.add(HEY_JARVIS_CONFIDENT)
        speak()
        assertNotNull(awaitWake())
        val c = awaitCandidate()
        assertEquals("vosk-window", c.wokeBy)
        assertEquals("wake", c.vosk)
    }

    @Test
    fun `a rival keyword vetoes a Vosk window acceptance`() {
        spotted = listOf("OK_JARVIS")
        model.finals.add(HEY_JARVIS_CONFIDENT)
        speak()
        val c = awaitCandidate()
        assertNull(c.wokeBy)
        assertEquals("OK_JARVIS", c.rival)
        assertEquals("wake", c.vosk)
        assertNull(awaitWake(maxFrames = 20))
    }

    @Test
    fun `a verdict from before a capture restart never wakes`() {
        val gate = Gate().also { spotGate = it }
        speak()
        repeat(100) {
            if (gate.awaitEntered(10)) return@repeat
            hybrid.onFrame(frame(), FRAME, detector)
        }
        assertTrue("spotter never ran", gate.awaitEntered(0))
        hybrid.onStarted() // the handoff's pause and reclaim
        gate.release()
        awaitCandidate()
        assertNull(awaitWake(maxFrames = 20))
    }

    @Test
    fun `Vosk mode delivers Vosk wakes and never builds the gate`() {
        mode = WakeMode.VOSK
        hybrid.onStarted()
        detector.start()
        loader.complete(model)
        model.finals.add(HEY_JARVIS_CONFIDENT)
        hybrid.onFrame(frame(), FRAME, detector) // adopts the model
        val wake = hybrid.onFrame(ScriptedDecoder.frame(5, endpoint = true), FRAME, detector)
        assertEquals("jarvis", wake?.wakeId)
        assertEquals(0, gatesBuilt)
        val v = evidence.filterIsInstance<HybridEvidence.VoskWake>().single()
        assertTrue(v.delivered)
    }

    @Test
    fun `without the shadow the live Decoder is fed nothing, but the window check still has the model`() {
        spotted = emptyList()
        model.finals.add(HEY_JARVIS_CONFIDENT)
        speak()
        assertNotNull(awaitWake())
        assertTrue(model.decoders.first().fed.isEmpty())
    }

    @Test
    fun `the Vosk shadow's wakes are recorded, not delivered`() {
        shadow = true
        spotted = listOf("OK_JARVIS") // the hybrid won't wake
        hybrid.onStarted()
        detector.start()
        loader.complete(model)
        hybrid.onFrame(frame(), FRAME, detector)
        model.finals.add(HEY_JARVIS_CONFIDENT)
        assertNull(hybrid.onFrame(ScriptedDecoder.frame(5, endpoint = true), FRAME, detector))
        val v = evidence.filterIsInstance<HybridEvidence.VoskWake>().single()
        assertEquals(false, v.delivered)
    }

    @Test
    fun `a gate that fails to build leaves Vosk deciding`() {
        gateFails = true
        hybrid.onStarted()
        detector.start()
        loader.complete(model)
        hybrid.onFrame(frame(), FRAME, detector)
        repeat(100) {
            if (lines.any { line -> line.startsWith("hybrid unavailable") }) return@repeat
            Thread.sleep(10)
        }
        hybrid.onFrame(frame(), FRAME, detector) // sees the hybrid unavailable: switches to Vosk
        model.finals.add(HEY_JARVIS_CONFIDENT)
        assertEquals("jarvis", hybrid.onFrame(ScriptedDecoder.frame(5, endpoint = true), FRAME, detector)?.wakeId)
        assertTrue(lines.toString(), lines.contains("wake mode → Vosk (hybrid unavailable)"))
    }

    @Test
    fun `a marked miss saves the recent audio at the next frame`() {
        hybrid.onStarted()
        repeat(3) { hybrid.onFrame(frame(), FRAME, detector) }
        hybrid.markMiss()
        hybrid.onFrame(frame(), FRAME, detector)
        val m = evidence.filterIsInstance<HybridEvidence.MissMark>().single()
        assertEquals(4 * FRAME, m.audio.size)
    }

    @Test
    fun `samples round-trip through little-endian bytes`() {
        val s = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 0x1234)
        val b = HybridWakeDetector.toBytes(s)
        assertEquals(s.toList(), HybridWakeDetector.toShorts(b, b.size).toList())
    }

    private companion object {
        const val TONE: Short = 0x2020
    }
}
