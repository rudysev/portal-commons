package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [WakeClipRecorder]: the naming that makes a directory listing readable, the caps that keep a multi-day
 * run from filling the device, and the WAV actually being playable.
 *
 * The writer thread is drained with `awaitIdle()` before every assertion — the production path is
 * deliberately asynchronous (see the class doc), so a test that read the directory straight after `save()`
 * would be racing it.
 */
class WakeClipRecorderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val logs = mutableListOf<String>()
    private var recorder: WakeClipRecorder? = null

    @After fun tearDown() {
        recorder?.close()
    }

    private fun build(dir: File = tmp.root, cap: Int = 100): WakeClipRecorder = WakeClipRecorder(dir, cap) { logs.add(it) }.also { recorder = it }

    /** A window of [n] samples all equal to [value], so the bytes on disk are identifiable. */
    private fun pcm(n: Int = 8, value: Int = 7) = ShortArray(n) { value.toShort() }

    private fun names(dir: File = tmp.root) = (dir.list() ?: emptyArray()).sorted()

    // ---- naming ----------------------------------------------------------------------------------------

    @Test fun theNameCarriesKindPhraseAndScore() {
        val r = build()
        r.save(WakeAudit.Clip.WAKE, "jarvis", 0.47f, pcm())
        r.awaitIdle()

        val name = names().single()
        assertTrue(name, name.startsWith("wake_jarvis_"))
        assertTrue("the on-device score must travel with the audio: $name", name.endsWith("_p047.wav"))
    }

    @Test fun eachKindGetsItsOwnPrefix() {
        val r = build()
        r.save(WakeAudit.Clip.WAKE, "jarvis", 0.9f, pcm())
        r.save(WakeAudit.Clip.REJECTED, "jarvis", 0.46f, pcm())
        r.save(WakeAudit.Clip.NEAR, "jarvis", 0.28f, pcm())
        r.awaitIdle()

        assertEquals(setOf("wake", "rej", "near"), names().map { it.substringBefore('_') }.toSet())
    }

    @Test fun clipsInTheSameSecondDoNotOverwriteEachOther() {
        // Near-misses arrive up to twice a second and the stamp is second-resolution; the second clip is as
        // much evidence as the first.
        val r = build()
        repeat(3) { r.save(WakeAudit.Clip.NEAR, "jarvis", 0.28f, pcm()) }
        r.awaitIdle()

        assertEquals(3, names().size)
    }

    @Test fun anOutOfRangeScoreStillProducesAParseableName() {
        val r = build()
        r.save(WakeAudit.Clip.WAKE, "jarvis", -1f, pcm())
        r.awaitIdle()
        assertTrue(names().single(), names().single().endsWith("_p000.wav"))
    }

    // ---- the file itself -------------------------------------------------------------------------------

    @Test fun theClipIsAPlayableWavHoldingTheWindow() {
        val r = build()
        r.save(WakeAudit.Clip.WAKE, "jarvis", 0.5f, pcm(n = 4, value = 7))
        r.awaitIdle()

        val bytes = File(tmp.root, names().single()).readBytes()
        assertEquals(44 + 4 * 2, bytes.size)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals(bytes.size - 8, b.getInt(4)) // RIFF size, back-patched on close
        assertEquals(PcmCaptureFormat.SAMPLE_RATE, b.getInt(24))
        assertEquals(4 * 2, b.getInt(40)) // data size
        assertEquals(7, b.getShort(44).toInt())
    }

    @Test fun theDirectoryIsCreatedIfItIsMissing() {
        val dir = File(tmp.root, "clips")
        val r = build(dir = dir)
        r.save(WakeAudit.Clip.WAKE, "jarvis", 0.5f, pcm())
        r.awaitIdle()

        assertEquals(1, names(dir).size)
    }

    // ---- the caps --------------------------------------------------------------------------------------

    @Test fun aKindStopsAtItsCapAndSaysSoOnce() {
        val r = build(cap = 2)
        repeat(5) { r.save(WakeAudit.Clip.NEAR, "jarvis", 0.28f, pcm()) }
        r.awaitIdle()

        assertEquals(2, names().size)
        assertEquals(1, logs.count { it.contains("cap 2 reached") })
    }

    @Test fun oneFloodedKindCannotCrowdOutTheOthers() {
        // The whole reason the cap is per-kind: near-misses are two-a-second, wakes are rare, and a shared
        // budget would let a talkative afternoon spend the one that settles a question.
        val r = build(cap = 2)
        repeat(10) { r.save(WakeAudit.Clip.NEAR, "jarvis", 0.28f, pcm()) }
        r.save(WakeAudit.Clip.WAKE, "jarvis", 0.9f, pcm())
        r.awaitIdle()

        assertEquals(1, names().count { it.startsWith("wake_") })
        assertEquals(2, names().count { it.startsWith("near_") })
    }

    @Test fun theCapResumesFromWhatAPreviousRunLeftBehind() {
        // A day-long run survives several service restarts; a cap that reset each time would not bound the
        // directory at all.
        build(cap = 3).let { first ->
            repeat(3) { first.save(WakeAudit.Clip.NEAR, "jarvis", 0.28f, pcm()) }
            first.awaitIdle()
            first.close()
        }
        assertEquals(3, names().size)

        val second = build(cap = 3)
        second.save(WakeAudit.Clip.NEAR, "jarvis", 0.28f, pcm())
        second.awaitIdle()

        assertEquals("the restart must not reopen the budget", 3, names().size)
    }

    @Test fun aCapOnOneKindDoesNotConsumeTheBudgetOfAnother() {
        build(cap = 1).let { first ->
            first.save(WakeAudit.Clip.NEAR, "jarvis", 0.28f, pcm())
            first.awaitIdle()
            first.close()
        }
        val second = build(cap = 1)
        second.save(WakeAudit.Clip.WAKE, "jarvis", 0.9f, pcm())
        second.awaitIdle()

        assertEquals(2, names().size)
    }

    // ---- failure is never fatal ------------------------------------------------------------------------

    @Test fun anUnwritableDirectoryIsLoggedRatherThanThrown() {
        // Diagnostics must not be able to take down capture. A plain file where the directory should be is
        // the simplest unwritable target that behaves the same on every filesystem.
        val blocked = File(tmp.root, "clips").apply { writeText("not a directory") }
        val r = build(dir = blocked)
        r.save(WakeAudit.Clip.WAKE, "jarvis", 0.5f, pcm())
        r.awaitIdle()

        assertTrue("the failure must be reported: $logs", logs.any { it.contains("audit clips") })
    }
}
