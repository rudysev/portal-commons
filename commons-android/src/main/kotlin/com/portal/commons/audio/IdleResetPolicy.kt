package com.portal.commons.audio

/**
 * Decides **when** [VoskWakeDetector] should bound Vosk's native decode lattice with a [WakeRecognizer.reset].
 *
 * The lattice only grows while Vosk is *not* endpointing: with continuous audio the grammar recognizer can
 * go a long time without finalizing, and the never-flushed "current utterance" grows ~3.8 MB/min (measured
 * on-device). A [WakeRecognizer.reset] flushes it — but reset also discards the in-flight partial, so one
 * that lands mid "hey jarvis" throws away the already-decoded "hey" and the wake vanishes without even a
 * near-miss. This policy issues that reset as a **backstop**: only when it is both needed and safe.
 *
 * Two rules:
 *  1. **Reset only when the decoder is quiet.** Once the lattice has gone [quietResetMs] unflushed, the reset
 *     waits for a frame with no in-flight partial ([WakeRecognizer.isMidUtterance]) so it can't bisect an
 *     utterance. [forceResetMs] is the hard cap: past it the reset fires even mid-decode, so pathological
 *     continuous noise (a TV that never yields a quiet frame) still can't grow the lattice unbounded — worst
 *     case it holds ~[forceResetMs] of audio (< 4 MB).
 *  2. **Natural endpoints are the primary bound; this reset is only the fallback.** Every finalized decode
 *     ([noteFlushed]) means Kaldi already flushed the lattice, which restarts the clock — so the manual reset
 *     fires only after [quietResetMs] with *no* endpoint at all. In any room with ambient sound Vosk
 *     endpoints every few seconds, so the clock keeps restarting and this reset may **never** fire. That is
 *     correct, not a stall: those endpoints are what bound memory. The manual reset earns its keep only in
 *     the rare continuous-audio case where Vosk stops endpointing entirely.
 *
 * Pure and clock-free (the caller passes `nowMs`), so the rules are unit-tested. Single-threaded by
 * contract: owned and driven by the engine's capture thread only.
 */
class IdleResetPolicy(
    // Unflushed this long → reset at the next quiet frame (rule 1). A backstop, not a fixed cadence: rule 2
    // keeps restarting the clock on natural endpoints, so in most rooms this never trips.
    private val quietResetMs: Long = DEFAULT_QUIET_RESET_MS,
    // Hard cap: reset once unflushed this long even mid-decode, so memory stays bounded under continuous noise.
    private val forceResetMs: Long = DEFAULT_FORCE_RESET_MS,
) {
    init {
        require(quietResetMs in 1..forceResetMs) { "need quietResetMs >= 1 and quietResetMs <= forceResetMs" }
    }

    private var flushedAtMs = 0L // last instant the lattice was known bounded (reset, rebuild, or endpoint)

    /** What [decide] concluded — split so the engine can log a forced (mid-decode) reset distinctly. */
    enum class Decision {
        /** Lattice still bounded recently enough, or an utterance is in flight — don't reset. */
        KEEP,

        /** Unflushed past [quietResetMs] and the decoder is quiet — reset now; nothing in flight to lose. */
        RESET,

        /** Backstop: unflushed past [forceResetMs] while still mid-decode — reset anyway to bound memory. */
        FORCE_RESET,
    }

    /**
     * The lattice was just bounded — by a manual reset, a grammar rebuild (fresh recognizer), or a natural
     * Vosk endpoint (any finalized decode flushes it). Restarts the unflushed clock.
     */
    fun noteFlushed(nowMs: Long) {
        flushedAtMs = nowMs
    }

    /**
     * Should the engine reset now? [isMidUtterance] is a lambda (not a value) so the decoder's partial
     * result — a native call — is queried only once [quietResetMs] has actually elapsed, not per frame.
     */
    fun decide(nowMs: Long, isMidUtterance: () -> Boolean): Decision {
        val unflushedMs = nowMs - flushedAtMs
        if (unflushedMs < quietResetMs) return Decision.KEEP
        // Any non-empty partial defers — even a bare "[unk]": the decoder may hypothesize [unk] while a real
        // "hey" is still settling. forceResetMs caps how long that deferral can hold the reset off.
        if (!isMidUtterance()) return Decision.RESET
        return if (unflushedMs >= forceResetMs) Decision.FORCE_RESET else Decision.KEEP
    }

    companion object {
        /** Unflushed this long with no natural endpoint → reset at the next quiet frame (see rule 2: a
         *  backstop for the continuous-audio case, not a guaranteed cadence). */
        const val DEFAULT_QUIET_RESET_MS = 25_000L

        /** Hard cap on unflushed time — reset even mid-decode past here (~3.8 MB/min → < 4 MB held). */
        const val DEFAULT_FORCE_RESET_MS = 60_000L
    }
}
