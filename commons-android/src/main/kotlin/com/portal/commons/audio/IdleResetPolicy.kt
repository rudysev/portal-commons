package com.portal.commons.audio

/**
 * Decides **when** [WakeMicEngine] may bound Vosk's native decode lattice with a [WakeRecognizer.reset].
 *
 * The lattice must be bounded: with continuous ambient audio the grammar recognizer can go a long time
 * without endpointing, and the never-finalized "current utterance" grows ~3.8 MB/min (observed on-device).
 * But a reset discards the decoder's in-flight partial hypothesis, so a timer-only reset that lands mid
 * "hey jarvis" throws away the already-decoded lead — the utterance vanishes without even a near-miss
 * (observed on-device as a wake attempt with no log trace, bracketed by `idle reset` lines).
 *
 * Two rules close that window:
 *  1. **Reset only in decoder silence.** When the idle period elapses, the reset is deferred while the
 *     decoder holds a non-empty partial hypothesis ([WakeRecognizer.isMidUtterance]) and fires at the next
 *     quiet frame — so it can no longer bisect an utterance. A hard backstop ([forceAfterMs]) still forces
 *     the reset under pathological continuous noise (e.g. a TV) that never yields a quiet frame, keeping
 *     the memory bound unconditional: worst case it holds ~[forceAfterMs] of lattice (< 4 MB), not 25 s.
 *  2. **Natural endpoints restart the clock.** Every finalized decode ([noteFlushed]) means Kaldi already
 *     flushed the lattice and started a fresh utterance, so the manual reset is only needed when the
 *     decoder has gone [idleAfterMs] with *no* endpoint at all — in a normal room, where silence endpoints
 *     fire every few seconds, the manual reset (and its re-warm-up) rarely runs.
 *
 * Pure and clock-free (the caller passes `nowMs`), so the deferral rules are unit-tested. Single-threaded
 * by contract: owned and driven by the engine's capture thread only.
 */
class IdleResetPolicy(
    private val idleAfterMs: Long = DEFAULT_IDLE_AFTER_MS,
    private val forceAfterMs: Long = DEFAULT_FORCE_AFTER_MS,
) {
    init {
        require(idleAfterMs in 1..forceAfterMs) { "need 0 < idleAfterMs <= forceAfterMs" }
    }

    private var flushedAtMs = 0L // last instant the lattice was known bounded (reset, rebuild, or endpoint)

    /** What [decide] concluded — split so the engine can log a forced (mid-decode) reset distinctly. */
    enum class Decision {
        /** Lattice still bounded recently enough, or an utterance is in flight — don't reset. */
        KEEP,

        /** Idle period elapsed and the decoder is quiet — reset now; nothing in flight to lose. */
        RESET,

        /** Backstop: the decoder has been mid-decode past [forceAfterMs] — reset anyway to bound memory. */
        FORCE_RESET,
    }

    /**
     * The lattice was just bounded — by a manual reset, a grammar rebuild (fresh recognizer), or a natural
     * Vosk endpoint (any finalized decode flushes it). Restarts the idle clock.
     */
    fun noteFlushed(nowMs: Long) {
        flushedAtMs = nowMs
    }

    /**
     * Should the engine reset now? [isMidUtterance] is a lambda (not a value) so the decoder's partial
     * result — a native call — is queried only once the idle period has actually elapsed, not per frame.
     */
    fun decide(nowMs: Long, isMidUtterance: () -> Boolean): Decision {
        val unflushedMs = nowMs - flushedAtMs
        if (unflushedMs < idleAfterMs) return Decision.KEEP
        // Any non-empty partial defers — even a bare "[unk]": the decoder may hypothesize [unk] while a
        // real "hey" is still settling, and the backstop caps how long noise can hold the reset off.
        if (!isMidUtterance()) return Decision.RESET
        return if (unflushedMs >= forceAfterMs) Decision.FORCE_RESET else Decision.KEEP
    }

    companion object {
        /** Reset this long after the last known flush (natural endpoints keep pushing it back). */
        const val DEFAULT_IDLE_AFTER_MS = 25_000L

        /** Backstop: never let the lattice grow past this, even mid-decode (~3.8 MB/min → < 4 MB held). */
        const val DEFAULT_FORCE_AFTER_MS = 60_000L
    }
}
