package com.portal.commons.audio

import com.portal.commons.PcmDevice
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore

/**
 * A pause that lands while a real [WakeMicEngine] capture thread is replaying the Vosk pre-ready buffer, with frames
 * that cost what they cost on gen2. Found on-device on 2026-10-24: a 26-frame replay decoded in one callback outlived
 * the stop join, and the stop force-released the mic (`pcm stop timeout`).
 */
class VoskReplayStopTest {

    /** Serves one speech frame per permit, and no data otherwise. */
    private class PermitDevice : PcmDevice {
        val permits = Semaphore(0)

        @Volatile var served = 0

        override fun open() = true

        override fun read(buf: ByteArray, offset: Int, length: Int): Int {
            if (!permits.tryAcquire()) return 0
            buf.fill(SPEECH, offset, offset + length)
            served++
            return length
        }

        override fun stop() = Unit

        override fun release() = Unit
    }

    /** Takes [SPEECH_FRAME_MS] to decode each speech frame and never finalizes. Warm-up silence is free. */
    private class SlowDecoder : VoskDecoder {
        override fun acceptFrame(buf: ByteArray, n: Int): Boolean {
            if (buf[0] == SPEECH) Thread.sleep(SPEECH_FRAME_MS)
            return false
        }

        override fun finalResult() = """{"text" : ""}"""

        override fun flushResult() = finalResult()

        override fun partialResult() = """{"partial" : ""}"""

        override fun reset() = Unit

        override fun close() = Unit
    }

    private val logs = CopyOnWriteArrayList<String>()
    private val loader = FakeVoskLoader()
    private val device = PermitDevice()
    private val engine = WakeMicEngine(
        WakeMicConfig(
            wakeWords = listOf(JARVIS),
            model = WakeModel.Bundled,
            onWake = {},
            log = { logs.add(it) },
        ),
        newDevice = { device },
        postToMain = {},
        newLoader = { loader },
    )

    @Test(timeout = 10_000)
    fun pauseDuringTheReplayStopsCaptureWithinTheJoin() {
        engine.start()
        device.permits.release(PRE_READY_FRAMES)
        while (device.served < PRE_READY_FRAMES) Thread.sleep(1)
        loader.complete(
            object : VoskModel {
                override fun newDecoder(grammar: String?): VoskDecoder = SlowDecoder()

                override fun close() = Unit
            },
        )
        device.permits.release(1) // the frame that adopts the model and starts the replay
        while (logs.none { "pre-ready" in it }) Thread.sleep(1)

        engine.pause()
        engine.close()

        assertEquals(emptyList<String>(), logs.filter { "stop timeout" in it || "close deferred" in it })
    }

    private companion object {
        const val SPEECH: Byte = 7

        // As on the device run that timed out.
        const val PRE_READY_FRAMES = 26
        const val SPEECH_FRAME_MS = 38L
    }
}
