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
        val r = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                PcmCaptureFormat.SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                maxOf(minBuf, PcmCaptureFormat.FRAME_BYTES * 4),
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
