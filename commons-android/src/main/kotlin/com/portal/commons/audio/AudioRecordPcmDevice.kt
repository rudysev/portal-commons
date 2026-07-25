package com.portal.commons.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.portal.commons.PcmCaptureFormat
import com.portal.commons.PcmDevice

/**
 * The shared Android microphone behind the [PcmDevice] seam: one `AudioRecord` (VOICE_RECOGNITION,
 * 16 kHz mono 16-bit, **no audio effects** — AGC/NoiseSuppressor deliberately off, the proven Portal
 * config). Recreated on each [open] so [com.portal.commons.PcmCaptureSession] can rebuild it after a run of
 * read errors; [read] is **non-blocking** (`AudioRecord.READ_NON_BLOCKING`). Used by consuming apps'
 * capture sessions.
 *
 * Caller must hold RECORD_AUDIO.
 */
class AudioRecordPcmDevice : PcmDevice {
    @Volatile private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    override fun open(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(PcmCaptureFormat.SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) return false // ERROR / ERROR_BAD_VALUE: this rate/channel/encoding isn't supported

        // 16 frames = 1.6 s, not the 4 frames (400 ms) shipped until 2026-07-25. The two-stage cascade runs
        // stage 2 *synchronously on the capture thread*, and that decode measured ~900 ms on the Portal (min
        // 755, median 877, max 1079 over 17 decodes) — so a 400 ms buffer dropped ~500 ms of audio after
        // every candidate that reached a decode. 1.6 s gives ~1.5x headroom over the worst decode seen.
        //
        // Costs ~51 KB and **no latency**: reads are READ_NON_BLOCKING, so a bigger buffer never makes a
        // read wait. It only raises the ceiling on how far the consuming loop may fall behind before
        // AudioRecord overwrites unread samples.
        //
        // Sized here rather than per consumer because the constraint belongs to the device — every
        // PcmDevice consumer inherits it, and portal-assistant shares this class.
        val bufBytes = maxOf(minBuf, PcmCaptureFormat.FRAME_BYTES * 16)

        val r = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                PcmCaptureFormat.SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufBytes,
            )
        }.getOrNull() ?: return false
        runCatching { r.startRecording() }
        if (!captureReady(r.state, r.recordingState)) {
            // Not initialized, or startRecording() failed (slot busy / threw) — report a clean failed open
            // so the capture session retries/rebuilds, instead of returning a "ready" device that only
            // yields read errors.
            runCatching { r.release() }
            return false
        }
        record = r
        return true
    }

    // Non-blocking: returns bytes read, 0 if nothing buffered, or a negative value AudioRecord error code
    // (ERROR/ERROR_BAD_VALUE/ERROR_INVALID_OPERATION/ERROR_DEAD_OBJECT) - maps 1-1 onto PcmDevice's read().
    override fun read(buf: ByteArray, offset: Int, length: Int): Int = record?.read(buf, offset, length, AudioRecord.READ_NON_BLOCKING) ?: -1

    override fun stop() {
        runCatching { record?.stop() }
    }

    override fun release() {
        runCatching { record?.release() }
        record = null
    }

    private companion object {
        // Mechanical translation of the Android-free PcmCaptureFormat into AudioRecord's AudioFormat
        // constants. This is the *only* place the two representations meet: if the shared format ever moves
        // to stereo or a different sample width, fail loudly here at first use rather than silently
        // mis-sizing frames (PcmCaptureFormat.FRAME_BYTES) or mis-reading levels (PcmLevel) elsewhere.
        val CHANNEL = when (PcmCaptureFormat.CHANNELS) {
            1 -> AudioFormat.CHANNEL_IN_MONO
            2 -> AudioFormat.CHANNEL_IN_STEREO
            else -> error("Unsupported PcmCaptureFormat.CHANNELS=${PcmCaptureFormat.CHANNELS}")
        }
        val ENCODING = when (PcmCaptureFormat.BYTES_PER_SAMPLE) {
            2 -> AudioFormat.ENCODING_PCM_16BIT
            else -> error("Unsupported PcmCaptureFormat.BYTES_PER_SAMPLE=${PcmCaptureFormat.BYTES_PER_SAMPLE}")
        }
    }
}

/**
 * The capture-open success test, factored out so it's unit-testable on the plain JVM (the `STATE_*` /
 * `RECORDSTATE_*` values are inlined compile-time constants). A device is ready only when it both
 * initialized and actually entered the recording state after `startRecording()`.
 */
internal fun captureReady(recordState: Int, recordingState: Int): Boolean = recordState == AudioRecord.STATE_INITIALIZED && recordingState == AudioRecord.RECORDSTATE_RECORDING
