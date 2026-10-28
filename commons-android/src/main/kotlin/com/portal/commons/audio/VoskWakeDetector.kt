package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The **Vosk** [WakeDetector] (free, keyless, offline): model handoff, the wake words stored before it is ready, and
 * the pre-ready buffer + replay. Decoding — the Decoder, grammar, warm-up, result parsing, the [WakeMatcher] call and
 * the idle reset that bounds Vosk's decode lattice — lives in the [WakeDecoder]. Native libvosk sits behind the
 * [VoskModelLoader] / [VoskModel] / [VoskDecoder] seam; the mic lives in [WakeMicEngine].
 *
 * **The capture thread owns the wake decoder.** The loader only hands the model over ([delivered]); the next
 * frame adopts it, creating the wake decoder from the *current* wake words, then calls [onReady] (or
 * [onUnavailable] if its first Decoder can't be built). A model-*load* failure calls [onUnavailable] straight from
 * the loader. One thread touches decoders, so no locks.
 *
 * **Per-frame policy** ([accept]): until a wake decoder exists, ring-buffer frames (so speech during init isn't
 * lost); on adoption, replay them; then feed the wake decoder. **On (re)start** ([start]) the wake decoder is reset
 * — which re-warms it — so the first frame after a resume isn't garbled.
 *
 * **Replay is spread over frames.** The pre-ready buffer becomes a backlog that each [accept] decodes at most
 * [REPLAY_FRAMES_PER_ACCEPT] frames of, with every live frame queued behind it until it drains. Decoding a full
 * buffer in one call (~38 ms a frame on gen2, so 1–2 s) held the capture thread inside the callback past a
 * pause's stop join, and the stop force-released the mic. A [start] drops a backlog left by the previous capture.
 *
 * **Audio buffered before a wake is never decoded after it.** A backlog frame that wakes ends the call and drops the
 * rest of the backlog, the live frames queued behind it included.
 * A match is [accept]'s return value; the wake decoder's diagnostics go out through [diagnostic].
 *
 * Readiness, unavailability and diagnostics are plain lambdas, as in the [WakeDecoder]; [factory] adapts the
 * host's events onto them.
 */
internal class VoskWakeDetector(
    loader: VoskModelLoader,
    initialWakeWords: List<WakeWord>,
    private val onReady: () -> Unit,
    private val onUnavailable: () -> Unit,
    private val diagnostic: (String) -> Unit,
    // Monotonic milliseconds, for the idle-reset clock (a wall-clock jump could force a mid-decode reset).
    // Injected so tests drive time.
    private val clock: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
) : WakeDetector {

    override val id: String = ID

    // The loader → capture-thread handoff: set by the loader callback, taken by the next frame (or freed by
    // close()). Atomic so a delivery racing close() frees the model exactly once.
    private val delivered = AtomicReference<VoskModel?>(null)

    @Volatile private var closed = false

    // Capture-thread state. close() also touches it, but only after capture stops, under the engine's lock.
    private var wakeDecoder: WakeDecoder? = null // non-null ⇔ ready

    // The wake set stored until the wake decoder exists; adoption builds its first Decoder from it.
    private var wakeWords: List<WakeWord> = initialWakeWords

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

    override fun start() {
        backlog.clear() // audio from before the pause
        wakeDecoder?.reset()
    }

    override fun accept(buf: ByteArray, n: Int): WakeEvent? {
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

    /**
     * Swap to a new wake set **without reloading the model** ([WakeDecoder.updateWakeWords]). Runs on the capture
     * thread at a frame boundary, so a Decoder is never closed mid-[accept]. Before the wake decoder exists, just
     * store the set — adoption builds from it.
     */
    override fun updateWakeWords(words: List<WakeWord>) {
        val wd = wakeDecoder
        if (wd == null) wakeWords = words else wd.updateWakeWords(words)
    }

    override fun close() {
        closed = true
        delivered.getAndSet(null)?.let { runCatching { it.close() } }
        backlog.clear()
        wakeDecoder?.close()
        wakeDecoder = null
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
        const val ID = "vosk"

        private val PRE_READY_MAX_FRAMES = 8_000 / PcmCaptureFormat.FRAME_MS

        /**
         * Backlog frames one [accept] decodes. About 115 ms of decoding on gen2, well inside a stop's join, and the
         * backlog shrinks by two frames for each live one, so a full pre-ready buffer catches up in about 4 s.
         */
        const val REPLAY_FRAMES_PER_ACCEPT = 3

        fun factory(modelDir: File? = null): WakeDetector.Factory = WakeDetector.Factory { host ->
            VoskWakeDetector(
                LibVoskModelLoader(host.context, modelDir),
                host.wakeWords,
                onReady = { host.events.onReady(ID) },
                onUnavailable = { host.events.onUnavailable(ID) },
                diagnostic = { host.events.onDiagnostic(ID, it) },
            )
        }
    }
}
