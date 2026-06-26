package com.portal.commons

/**
 * Shared microphone capture format for Portal apps: 16 kHz mono 16-bit PCM in ~100 ms frames.
 *
 * This is the **single source of truth** for the capture shape. [CHANNELS] and [BYTES_PER_SAMPLE] pin
 * the layout that every other component depends on — [FRAME_BYTES] is *derived* from them,
 * [PcmLevel] strides by [BYTES_PER_SAMPLE], and the Android seam
 * ([com.portal.commons.audio.AudioRecordPcmDevice])
 * *maps* these to `AudioFormat` constants and fails loudly if it can't. The library stays Android-free:
 * the `AudioFormat` values live at the seam, but the *truth* about channels and width lives here, so the
 * format can no longer drift silently across modules.
 */
object PcmCaptureFormat {

    /** Sample rate (Hz) for handset mic capture. */
    const val SAMPLE_RATE = 16_000

    /** Duration of one capture read buffer (milliseconds). */
    const val FRAME_MS = 100

    /** Channels in the capture stream. Mono today; the Android seam maps this to `CHANNEL_IN_*`. */
    const val CHANNELS = 1

    /** Bytes per PCM sample. 16-bit today; the Android seam maps this to `ENCODING_PCM_*`. */
    const val BYTES_PER_SAMPLE = 2

    /**
     * Bytes in one [FRAME_MS] frame, derived from [CHANNELS] and [BYTES_PER_SAMPLE] rather than hard-coded.
     * Change the layout in one place ([CHANNELS]/[BYTES_PER_SAMPLE]) and the frame size, the level math,
     * and the Android encoding all follow — or fail loudly at the seam.
     */
    const val FRAME_BYTES = SAMPLE_RATE * FRAME_MS / 1000 * CHANNELS * BYTES_PER_SAMPLE
}
