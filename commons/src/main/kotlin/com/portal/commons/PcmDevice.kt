package com.portal.commons

/**
 * The microphone device a [PcmCaptureSession] drives — the thin, per-app Android seam (an `AudioRecord`
 * wrapper) kept out of this Android-free library so the session's concurrency contract stays unit-testable.
 *
 * A single instance is **reused across open/release cycles** so the session can rebuild it: [open] must
 * create a fresh capture, [release] must tear the current one down, and a later [open] must work again.
 * All methods are invoked from the session's capture thread, **except** [stop], which the session may also
 * call from the stopping thread to unblock a parked [read].
 */
interface PcmDevice {
    /** Open and start capturing. Return false if the mic couldn't be acquired (the session ends + onError). */
    fun open(): Boolean

    /** Blocking read of up to [buf].size bytes into [buf]; returns bytes read, or <= 0 on a transient error. */
    fun read(buf: ByteArray): Int

    /** Stop capturing. MUST unblock a thread parked in [read] (`AudioRecord.stop()` does). Idempotent. */
    fun stop()

    /** Release the device. Idempotent; a subsequent [open] re-acquires. */
    fun release()
}
