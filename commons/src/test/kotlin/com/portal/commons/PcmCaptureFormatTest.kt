package com.portal.commons

import org.junit.Assert.assertEquals
import org.junit.Test

/** Shared mic capture constants — one frame = 100 ms mono 16-bit @ 16 kHz. */
class PcmCaptureFormatTest {

    @Test fun sampleRateIs16Khz() {
        assertEquals(16_000, PcmCaptureFormat.SAMPLE_RATE)
    }

    @Test fun frameBytesIsOneHundredMsMono16Bit() {
        assertEquals(3_200, PcmCaptureFormat.FRAME_BYTES)
        assertEquals(
            PcmCaptureFormat.SAMPLE_RATE * PcmCaptureFormat.FRAME_MS / 1000 * 2,
            PcmCaptureFormat.FRAME_BYTES,
        )
    }

    @Test fun frameBytesIsDerivedFromChannelsAndSampleWidth() {
        assertEquals(1, PcmCaptureFormat.CHANNELS)
        assertEquals(2, PcmCaptureFormat.BYTES_PER_SAMPLE)
        assertEquals(
            PcmCaptureFormat.SAMPLE_RATE * PcmCaptureFormat.FRAME_MS / 1000 *
                PcmCaptureFormat.CHANNELS * PcmCaptureFormat.BYTES_PER_SAMPLE,
            PcmCaptureFormat.FRAME_BYTES,
        )
    }
}
