package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class WakeClipRecorderTest {

    private val dir = Files.createTempDirectory("wake-clips").toFile()
    private val logs = CopyOnWriteArrayList<String>()
    private var now = 1_793_330_671_218L

    private fun recorder(cap: Int = WakeClipRecorder.DEFAULT_CAP_PER_KIND) =
        WakeClipRecorder(dir, cap, log = { logs.add(it) }, wallClock = { now })

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test fun clipIsAPlayableWavNamedByKindWordAndWallClock() {
        val pcm = ByteArray(PcmCaptureFormat.FRAME_BYTES) { it.toByte() }
        val r = recorder()
        r.save(WakeAudit.Clip.WAKE, "jarvis", pcm)
        r.awaitIdle()

        assertEquals(listOf("wake_jarvis_1793330671218.wav"), names())
        val bytes = File(dir, "wake_jarvis_1793330671218.wav").readBytes()
        val header = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + pcm.size, header.getInt(4))
        assertEquals("WAVEfmt ", String(bytes, 8, 8, Charsets.US_ASCII))
        assertEquals(PcmCaptureFormat.SAMPLE_RATE, header.getInt(24))
        assertEquals(16.toShort(), header.getShort(34)) // bits per sample
        assertEquals(pcm.size, header.getInt(40))
        assertArrayEquals(pcm, bytes.copyOfRange(44, bytes.size))
    }

    @Test fun eachKindHasItsOwnCap() {
        val r = recorder(cap = 2)
        repeat(3) {
            now++
            r.save(WakeAudit.Clip.NEAR, "jarvis", ByteArray(2))
        }
        now++
        r.save(WakeAudit.Clip.WAKE, "jarvis", ByteArray(2))
        r.awaitIdle()

        assertEquals(2, names().count { it.startsWith("near_") })
        assertEquals(1, names().count { it.startsWith("wake_") })
        assertEquals(listOf("wake clips: cap 2 reached for 'near', skipping"), logs)
    }

    @Test fun capIsSeededFromClipsAlreadyOnDisk() {
        File(dir, "near_jarvis_1.wav").writeBytes(ByteArray(0))
        File(dir, "near_jarvis_2.wav").writeBytes(ByteArray(0))
        val r = recorder(cap = 2)
        r.save(WakeAudit.Clip.NEAR, "jarvis", ByteArray(2))
        r.awaitIdle()

        assertEquals(listOf("near_jarvis_1.wav", "near_jarvis_2.wav"), names())
    }

    @Test fun clipsPulledOffTheDeviceFreeTheirRoom() {
        val r = recorder(cap = 1)
        r.save(WakeAudit.Clip.NEAR, "jarvis", ByteArray(2))
        r.awaitIdle()
        File(dir, "near_jarvis_1793330671218.wav").delete() // what a pull does
        now++
        r.save(WakeAudit.Clip.NEAR, "jarvis", ByteArray(2))
        r.awaitIdle()

        assertEquals(listOf("near_jarvis_1793330671219.wav"), names())
    }

    private fun names() = dir.list().orEmpty().sorted()
}
