package com.portal.commons.audio

/** Which detector decides a wake (see **Hybrid wake detector** in `GLOSSARY.md`). Read live, at every frame. */
enum class WakeMode {
    /** The hybrid wake detector decides the [HybridWakeConfig.wakeId] wake; Vosk can run beside it as the shadow. */
    HYBRID,

    /** The Vosk wake detector alone decides, exactly as without a hybrid; the hybrid's checks don't run. */
    VOSK,
}

/**
 * The hybrid's **gate**, its always-on first check (openWakeWord `hey_jarvis` in portal-wake). Built and driven on the
 * hybrid's gate thread only, so it needs no locks.
 */
interface WakeGate : AutoCloseable {
    /** The wake score in [0, 1] after one [FRAME_SAMPLES]-sample frame of 16 kHz mono PCM. */
    fun score(frame: ShortArray): Float

    companion object {
        /** One gate frame: 80 ms, the step openWakeWord scores on. */
        const val FRAME_SAMPLES = 1_280
    }
}

/**
 * The hybrid's keyword spotter (sherpa-onnx with competing keywords in portal-wake): the wake keyword, plus rivals that
 * sound like it ("OK Jarvis", "Hey Jervis", ...). Built and driven on the hybrid's verify thread only.
 */
interface KeywordSpotter : AutoCloseable {
    /** The keyword names spotted in [samples] (16 kHz, in [-1, 1]) decoded as one finished stream, in order. */
    fun spot(samples: FloatArray): List<String>
}

/**
 * Turns on the **hybrid wake detector** for [WakeMicConfig.hybrid]. Each 80 ms frame goes to the [WakeGate]; a score at
 * or above [gateThreshold] opens a **candidate**, and the ~2 s window around it is checked twice: the [KeywordSpotter]
 * and the Vosk window check (the production grammar and [WakeMatcher] gates on that window alone). The candidate wakes
 * [wakeId] when the spotter heard [confirmKeyword] and no rival, or when the Vosk window check accepts and the spotter
 * heard no rival.
 *
 * [mode] and [shadow] are read at every frame, so a switch applies at once. [onEvidence] receives the evidence records
 * from the capture, gate and verify threads and must not block.
 */
data class HybridWakeConfig(
    /** The wake id the hybrid decides (the gate and spotter are trained on its phrase); others stay with Vosk. */
    val wakeId: String,
    /** Builds the gate, on the gate thread, when hybrid mode first runs. A throw makes the hybrid unavailable. */
    val newGate: () -> WakeGate,
    /** Builds the spotter, on the verify thread, ahead of the first candidate. A throw makes the hybrid unavailable. */
    val newSpotter: () -> KeywordSpotter,
    val mode: () -> WakeMode,
    /**
     * In [WakeMode.HYBRID], also run the Vosk wake detector on every frame as the **Vosk shadow**, logging what Vosk
     * alone would have decided and where the two disagree. Off saves ~45% of a Portal+ core.
     */
    val shadow: () -> Boolean,
    val onEvidence: (HybridEvidence) -> Unit,
    val gateThreshold: Float = DEFAULT_GATE_THRESHOLD,
    /** The spotter's keyword name for the wake phrase; any other keyword it spots is a rival. */
    val confirmKeyword: String = DEFAULT_CONFIRM_KEYWORD,
) {
    companion object {
        /** The openWakeWord gate threshold the hybrid was measured with on the Portal+. */
        const val DEFAULT_GATE_THRESHOLD = 0.3f

        const val DEFAULT_CONFIRM_KEYWORD = "HEY_JARVIS"
    }
}

/**
 * An evidence record from the hybrid wake detector, for reviewing false and missed wakes later. [atMs] is wall-clock
 * epoch milliseconds. Audio is 16 kHz mono 16-bit LE PCM, oldest sample first.
 */
sealed interface HybridEvidence {
    val atMs: Long

    /**
     * One candidate and what both checks made of it. [atMs] is when the gate opened it. [spotted] is what the spotter
     * heard ([SPOT_ERROR] on its own line when it failed); [rival] the first rival among them. [vosk] is the window
     * check's verdict: `wake`, `near-miss: <reason>`, `none`, `unavailable` (no model yet) or `error`. [wokeBy] says
     * which path woke it: `sherpa`, `vosk-window`, or null when it didn't. [audio] is the
     * [CANDIDATE_AUDIO_MS] ending where the checked window ends.
     */
    // A record: one parameter per logged field.
    @Suppress("LongParameterList")
    class Candidate(
        override val atMs: Long,
        val gatePeak: Float,
        val spotted: List<String>,
        val rival: String?,
        val vosk: String,
        val voskTranscript: String,
        val wokeBy: String?,
        val spotterMs: Long,
        val voskMs: Long,
        val audio: ByteArray,
    ) : HybridEvidence {
        val woke: Boolean get() = wokeBy != null
    }

    /**
     * A Vosk wake: the decision itself in [WakeMode.VOSK] or for a word the hybrid doesn't decide ([delivered]), the
     * Vosk shadow's otherwise. [atHandoff] marks a shadow decode forced to finalize as a hybrid wake handed off.
     * [audio] is the last [VOSK_AUDIO_MS].
     */
    class VoskWake(
        override val atMs: Long,
        val wakeId: String,
        val transcript: String,
        val delivered: Boolean,
        val atHandoff: Boolean,
        val audio: ByteArray,
    ) : HybridEvidence

    /**
     * The hybrid and the Vosk shadow disagreed on one utterance: a Vosk shadow wake with no hybrid wake within
     * [PAIR_MS] ([Kind.VOSK_ONLY], with the nearest candidate's verdict if the gate opened one), or a hybrid wake with
     * no Vosk shadow wake ([Kind.HYBRID_ONLY]). Settled [SETTLE_MS] after the later event could have arrived.
     */
    class Disagreement(
        override val atMs: Long,
        val kind: Kind,
        val voskAtMs: Long?,
        val candidateAtMs: Long?,
        val candidateVerdict: String?,
    ) : HybridEvidence {
        enum class Kind { VOSK_ONLY, HYBRID_ONLY }
    }

    /** Someone marked a wake that didn't fire ([WakeMicEngine.markMiss]): the last [MISS_AUDIO_MS] of audio. */
    class MissMark(override val atMs: Long, val audio: ByteArray) : HybridEvidence

    /** Once a minute while capturing: what the checks cost on the capture and gate threads, and what was dropped. */
    // A record: one parameter per logged field.
    @Suppress("LongParameterList")
    class Stats(
        override val atMs: Long,
        val mode: WakeMode,
        val shadow: Boolean,
        val frames: Int,
        val voskMsMean: Double,
        val voskMsMax: Long,
        val gateMsMean: Double,
        val gateMsMax: Long,
        val droppedFrames: Int,
        val droppedCandidates: Int,
    ) : HybridEvidence

    companion object {
        const val CANDIDATE_AUDIO_MS = 4_000
        const val VOSK_AUDIO_MS = 5_000
        const val MISS_AUDIO_MS = 30_000
        const val PAIR_MS = 2_500L
        const val SETTLE_MS = 8_000L

        /** Stands in [Candidate.spotted] when the spotter failed on that window. */
        const val SPOT_ERROR = "<error>"
    }
}
