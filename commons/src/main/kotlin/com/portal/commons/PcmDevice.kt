package com.portal.commons

/**
 * The microphone device a [PcmCaptureSession] drives — the thin, per-app Android seam (an `AudioRecord`
 * wrapper) kept out of this Android-free library so the session's concurrency contract stays unit-testable.
 *
 * A single instance is **reused across open/release cycles** so the session can rebuild it: [open] must
 * create a fresh capture, [release] must tear the current one down, and a later [open] must work again.
 * Methods are invoked on the session's capture thread; the one exception is a last-resort [stop] + [release]
 * the session may call from the stopping thread if the capture thread fails to exit within the join timeout
 * (a stuck callback or a hung native call) — a rare safety net so the mic slot can't stay held. On the normal
 * path the device is touched only on the capture thread: [read] is non-blocking.
 */
interface PcmDevice {
    /** Open and start capturing. Return false if the mic couldn't be acquired (the session ends + onError). */
    fun open(): Boolean

    /**
     * **Non-blocking** read of up to [length] bytes into [buf] starting at [offset]. Returns:
     *  - `> 0` — bytes read right now (may be fewer than [length]: a partial read the session reassembles);
     *  - `= 0` — no data available at this instant. This is **normal** for a non-blocking read, **not** an
     *    error; the session simply polls again.
     *  - `< 0` — a real device error (e.g. the mic slot was lost / dead object).
     */
    fun read(buf: ByteArray, offset: Int, length: Int): Int

    /**
     * Stop capturing (e.g. `AudioRecord.stop()`). **Idempotent.** Normally called on the capture thread (loop
     * teardown / rebuild), but the session may also call it (with [release]) from the stopping thread as a
     * last resort if the capture thread overran its join — so it must tolerate that rare concurrent call.
     */
    fun stop()

    /** Release the device. Idempotent; a subsequent [open] re-acquires. */
    fun release()
}
