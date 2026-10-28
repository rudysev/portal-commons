package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat
import java.util.concurrent.atomic.AtomicReference

/**
 * The **wake detector** (see `GLOSSARY.md`), Vosk (free, keyless, offline): model handoff, the wake-word handoff, the
 * handoff cooldown, and the pre-ready buffer + replay. Decoding — the Decoder, grammar, warm-up, result parsing, the
 * [WakeMatcher] call and the idle reset that bounds Vosk's decode lattice — lives in the [WakeDecoder]. Native libvosk
 * sits behind the [VoskModelLoader] / [VoskModel] / [VoskDecoder] seam; the mic lives in [WakeMicEngine], which holds
 * exactly one of these.
 *
 * **Threading contract.** [start] and [accept] run on the engine's capture thread at frame boundaries, never two at
 * once, even when a wedged capture session is rebuilt while the old capture thread is still inside a call (two threads
 * in one Decoder abort the process), so capture-thread state needs no locks. [close] runs at most once and never during
 * another call: from the thread closing the engine, or by the capture thread as it leaves a call the engine's bounded
 * close outlasted. [updateWakeWords] is safe from any thread.
 *
 * **The capture thread owns the wake decoder.** The loader only hands the model over ([delivered]); the next
 * frame adopts it, creating the wake decoder from the *current* wake words, then calls [onReady] (or
 * [onUnavailable] if its first Decoder can't be built). A model-*load* failure calls [onUnavailable] straight from
 * the loader. One thread touches decoders, so no locks. Readiness is frame-driven, so it never comes while capture
 * is paused.
 *
 * **The wake-word handoff mirrors it.** [updateWakeWords] only hands the new wake words over ([pendingWakeWords]); the
 * next [accept] takes them before it decodes anything, so they apply before the frame they landed ahead of, however
 * many frames the engine skipped in between. Before the wake decoder exists they become the current wake words, which
 * adoption builds its first Decoder from; after, the wake decoder swaps its Decoder, keeping the old one and the old
 * wake words if the new one can't be built. Only the latest update counts.
 *
 * **Per-frame policy** ([accept]): take a pending wake-word update; inside the handoff cooldown, stop there; until a
 * wake decoder exists, ring-buffer frames (so speech during init isn't lost); on adoption, replay them; then feed the
 * wake decoder. **On (re)start** ([start]) the wake decoder is reset — which re-warms it — so the first frame after a
 * resume isn't garbled.
 *
 * **Owns the handoff cooldown** (see `GLOSSARY.md`): returning a wake opens one window of [handoffCooldownMs] on
 * [clock]. Every frame inside it is ignored: not buffered, not queued behind a backlog, not decoded, so no wake of any
 * wake id arises. This covers the ~1 frame between a match and capture actually pausing for the handoff. A frame at
 * exactly the window's end is decoded again, and [start] closes the window. A pending wake-word update is still
 * applied inside it.
 *
 * **One clock.** [clock] is the engine's monotonic clock (a wall-clock jump could hold the window open and leave the
 * engine deaf, or force a mid-decode idle reset); it drives both the cooldown and the wake decoder's idle reset.
 *
 * **Replay is spread over frames.** The pre-ready buffer becomes a backlog that each [accept] decodes at most
 * [REPLAY_FRAMES_PER_ACCEPT] frames of, with every live frame queued behind it until it drains. Decoding a full
 * buffer in one call (~38 ms a frame on gen2, so 1–2 s) held the capture thread inside the callback past a
 * pause's stop join, and the stop force-released the mic. A [start] drops a backlog left by the previous capture.
 *
 * **Audio buffered before a wake is never decoded after it.** A backlog frame that wakes ends the call and drops the
 * rest of the backlog, the live frames queued behind it included.
 */
// The seven inputs the engine wires in (the loader, the wake words, three callbacks, the cooldown length and the
// clock); only the loader is a seam (ADR 0003), and bundling the callbacks would recreate the Events type it removed.
@Suppress("LongParameterList")
internal class WakeDetector(
    loader: VoskModelLoader,
    initialWakeWords: List<WakeWord>,
    private val onReady: () -> Unit,
    private val onUnavailable: () -> Unit,
    private val diagnostic: (String) -> Unit,
    private val handoffCooldownMs: Long,
    // Monotonic milliseconds, injected by the engine (and by tests, to drive time).
    private val clock: () -> Long,
) {

    // The loader → capture-thread handoff: set by the loader callback, taken by the next frame (or freed by
    // close()). Atomic so a delivery racing close() frees the model exactly once.
    private val delivered = AtomicReference<VoskModel?>(null)

    // The any-thread → capture-thread wake-word handoff, taken by the next frame. Taken in one step, so an update that
    // lands while a frame takes the previous one is never lost.
    private val pendingWakeWords = AtomicReference<List<WakeWord>?>(null)

    @Volatile private var closed = false

    // Capture-thread state. close() also touches it, but only after capture stops, under the engine's lock.
    private var wakeDecoder: WakeDecoder? = null // non-null ⇔ ready

    // The wake set until the wake decoder exists (which then owns it); adoption builds its first Decoder from it.
    private var wakeWords: List<WakeWord> = initialWakeWords

    // The handoff cooldown is open while clock() is before this instant.
    private var handoffCooldownUntilMs = Long.MIN_VALUE

    private val preReadyBuffer = PcmRingBuffer(PRE_READY_MAX_FRAMES)

    // Replay frames not yet decoded, then the live frames queued behind them. Empty once replay has caught up.
    private val backlog = ArrayDeque<ByteArray>()

    init {
        try {
            loader.load(::onModelDelivered, onUnavailable)
        } catch (
            // Throwable, not Exception: native init fails with Errors too (UnsatisfiedLinkError from libvosk).
            @Suppress("SwallowedException", "TooGenericExceptionCaught")
            t: Throwable,
        ) {
            // The throwable is intentionally swallowed: don't let a model-load / native-init failure crash the
            // service. By contract any init failure is surfaced as onUnavailable, which the engine's consumer logs.
            onUnavailable()
        }
    }

    /** Loader callback (any thread): hand the model to the capture thread, or free it if we're already closed. */
    private fun onModelDelivered(m: VoskModel) {
        if (closed) {
            runCatching { m.close() }
            return
        }
        delivered.set(m)
        // close() landed between the check and the set: whichever of us takes it back frees it.
        if (closed && delivered.compareAndSet(m, null)) runCatching { m.close() }
    }

    /** (Re)entering capture: close the handoff cooldown, drop the old backlog and reset the wake decoder. */
    fun start() {
        handoffCooldownUntilMs = Long.MIN_VALUE
        backlog.clear() // audio from before the pause
        wakeDecoder?.reset()
    }

    /**
     * Feed one frame of 16 kHz mono 16-bit PCM ([n] bytes of [buf]) and return the wake it completed, or null for
     * none. At most one wake per call, and a wake opens the handoff cooldown. The engine may skip frames, so this never
     * assumes it hears every one.
     */
    fun accept(buf: ByteArray, n: Int): WakeEvent? {
        applyPendingWakeWords()
        if (clock() < handoffCooldownUntilMs) return null // inside the handoff cooldown: ignored
        return decode(buf, n)?.also { handoffCooldownUntilMs = clock() + handoffCooldownMs }
    }

    /**
     * Swap to a new wake set **without reloading the model** ([WakeDecoder.updateWakeWords]), from any thread. It is
     * applied on the capture thread at the next [accept], before that frame is decoded, so a Decoder is never closed
     * mid-[accept]. Before the wake decoder exists it becomes the set adoption builds from.
     */
    fun updateWakeWords(words: List<WakeWord>) {
        pendingWakeWords.set(words)
    }

    /** Full teardown: frees the wake decoder, and the model if it was delivered but never adopted. */
    fun close() {
        closed = true
        delivered.getAndSet(null)?.let { runCatching { it.close() } }
        backlog.clear()
        wakeDecoder?.close()
        wakeDecoder = null
    }

    /** Buffer, queue or decode one frame outside the handoff cooldown; the wake it completed, if any. */
    private fun decode(buf: ByteArray, n: Int): WakeEvent? {
        val wd = wakeDecoder ?: adoptDeliveredModel()
        if (wd == null) {
            preReadyBuffer.add(buf, n)
            return null
        }
        return if (backlog.isEmpty()) {
            wd.accept(buf, n)
        } else {
            backlog.addLast(buf.copyOf(n))
            decodeBacklog(wd)
        }
    }

    /** Take the latest wake-word update, if any, and apply it (capture thread, at a frame boundary). */
    private fun applyPendingWakeWords() {
        val words = pendingWakeWords.getAndSet(null) ?: return
        diagnostic("wake set changed → rebuilding grammar (${words.size} word(s))")
        val wd = wakeDecoder
        if (wd == null) wakeWords = words else wd.updateWakeWords(words)
    }

    /**
     * Take a model the loader delivered: create the wake decoder, signal readiness and move the pre-ready buffer into
     * the backlog. Null when there is no model yet, or its first Decoder build failed (unavailable).
     */
    private fun adoptDeliveredModel(): WakeDecoder? {
        val m = delivered.getAndSet(null) ?: return null
        val wd = WakeDecoder.create(m, wakeWords, diagnostic, clock = clock)
        if (wd == null) {
            onUnavailable()
        } else {
            wakeDecoder = wd
            onReady()
            val buffered = preReadyBuffer.drain()
            if (buffered.isNotEmpty()) diagnostic("flushing ${buffered.size} pre-ready frame(s)")
            backlog.addAll(buffered)
        }
        return wd
    }

    /**
     * Decode up to [REPLAY_FRAMES_PER_ACCEPT] backlog frames in order, returning the first wake. A wake drops the rest:
     * it was buffered before the wake, so it is never decoded after it.
     */
    private fun decodeBacklog(wd: WakeDecoder): WakeEvent? {
        val wake = generateSequence { backlog.removeFirstOrNull() }
            .take(REPLAY_FRAMES_PER_ACCEPT)
            .firstNotNullOfOrNull { wd.accept(it, it.size) }
        if (wake != null) backlog.clear()
        return wake
    }

    companion object {
        private val PRE_READY_MAX_FRAMES = 8_000 / PcmCaptureFormat.FRAME_MS

        /**
         * Backlog frames one [accept] decodes. About 115 ms of decoding on gen2, well inside a stop's join, and the
         * backlog shrinks by two frames for each live one, so a full pre-ready buffer catches up in about 4 s.
         */
        const val REPLAY_FRAMES_PER_ACCEPT = 3
    }
}
