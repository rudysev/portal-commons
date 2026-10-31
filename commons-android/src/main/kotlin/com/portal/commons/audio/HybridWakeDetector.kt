package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The **hybrid wake detector** (see `GLOSSARY.md`), driven by [WakeMicEngine] beside its Vosk [WakeDetector] when
 * [WakeMicConfig.hybrid] is set. Its per-frame policy ([onFrame], on the capture thread) decides what the engine
 * delivers:
 *
 * - **[WakeMode.VOSK], or the hybrid unavailable** (its gate or spotter failed to build): the Vosk wake detector
 *   decides, exactly as without a hybrid. Its wakes are recorded as evidence.
 * - **[WakeMode.HYBRID]**: every frame goes to the gate thread, which runs the [CandidateGate]; each candidate goes to
 *   the verify thread, which runs the [KeywordSpotter] and the Vosk window check ([WakeDecoder.judgeWindow] on the
 *   model borrowed through [ModelLease]) and decides with [HybridRule]. A wake it decides is picked up by the next
 *   frame and returned for delivery, so wakes still reach the consumer on the capture thread. Vosk decodes live only
 *   for the **Vosk shadow** ([HybridWakeConfig.shadow]) or a wake word the hybrid doesn't decide (delivered as
 *   before); the shadow's own wakes are only recorded, and [Disagreements] reports where the two differ. Just before
 *   a hybrid wake is returned, the shadow's in-flight decode is finalized and judged, since the handoff would cut it
 *   off before Vosk endpoints.
 *
 * **Generations.** A capture (re)start or a mode switch begins a new generation: the gate forgets its audio, and a
 * verdict from an older generation never wakes. A sherpa-confirmed wake is posted before the Vosk window check runs,
 * so the slow check (~1.4 s on a Portal+) delays only the wakes that need it.
 *
 * **Evidence.** Every candidate, Vosk wake, disagreement, marked miss and minute of stats goes to
 * [HybridWakeConfig.onEvidence], and a line to [log]. The capture thread keeps the last
 * [HybridEvidence.MISS_AUDIO_MS] of audio for the Vosk-wake and marked-miss clips.
 *
 * **Threads.** The gate and verify threads start the first time hybrid mode runs and build their own gate and spotter,
 * which they alone touch and close. [close] stops both, waiting at most [CLOSE_JOIN_MS] each; a thread still inside a
 * native call then finishes it and exits on its own, and the Vosk model it may hold is freed by the [ModelLease].
 */
// The config, the starting wake words, the log sink and the two clocks the engine wires in.
@Suppress("TooManyFunctions")
internal class HybridWakeDetector(
    private val config: HybridWakeConfig,
    initialWakeWords: List<WakeWord>,
    private val log: (String) -> Unit,
    // Monotonic milliseconds, for the stats interval.
    private val clock: () -> Long,
    // Wall-clock epoch milliseconds, for evidence timestamps.
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private class Chunk(val generation: Long, val samples: ShortArray, val atMs: Long)

    private class PendingWake(val generation: Long, val event: WakeEvent, val candidateAtMs: Long)

    private class Work(val generation: Long, val candidate: CandidateGate.Candidate)

    @Volatile private var wakeWords = initialWakeWords

    @Volatile private var lease: ModelLease? = null

    @Volatile private var unavailable = false

    @Volatile private var closed = false

    // Whether verdicts feed the disagreement tracker: hybrid mode with the Vosk shadow on.
    @Volatile private var tracking = false

    private val generation = AtomicLong(0)
    private val pendingWake = AtomicReference<PendingWake?>(null)
    private val missMarked = AtomicBoolean(false)
    private val disagreements = Disagreements()

    private val frames = ArrayBlockingQueue<Chunk>(FRAME_QUEUE)
    private val work = ArrayBlockingQueue<Work>(WORK_QUEUE)

    // Gate-thread cost, read and reset by the capture thread's stats line.
    private val gateNanos = AtomicLong(0)
    private val gateMaxNanos = AtomicLong(0)
    private val gateRuns = AtomicInteger(0)

    // Capture-thread state.
    private var gateThread: Thread? = null
    private var verifyThread: Thread? = null
    private val recent = ShortRing(CandidateGate.samplesFor(HybridEvidence.MISS_AUDIO_MS.toLong()))
    private var wasActive: Boolean? = null
    private var wasShadow: Boolean? = null
    private var statsFrom = clock()
    private var statFrames = 0
    private var voskNanos = 0L
    private var voskMaxNanos = 0L
    private var droppedFrames = 0
    private val droppedCandidates = AtomicInteger(0)

    /** Capture thread, on every capture (re)start: a new generation, with no audio from before it. */
    fun onStarted() {
        generation.incrementAndGet()
        pendingWake.set(null)
        recent.clear()
    }

    /** Capture thread, once the Vosk wake detector has adopted its model: the model the window check borrows. */
    fun adoptModel(model: ModelLease) {
        lease = model
    }

    /** Any thread: the wake words now in force, for the window check's grammar. */
    fun updateWakeWords(words: List<WakeWord>) {
        wakeWords = words
    }

    /** Any thread: save the last [HybridEvidence.MISS_AUDIO_MS] of audio as a marked miss at the next frame. */
    fun markMiss() {
        missMarked.set(true)
    }

    /** Capture thread, every frame: run the per-frame policy (class KDoc) and return the wake to deliver, if any. */
    fun onFrame(buf: ByteArray, n: Int, detector: WakeDetector): WakeEvent? {
        val now = wallClock()
        val samples = toShorts(buf, n)
        recent.write(samples)
        if (missMarked.getAndSet(false)) {
            log("miss marked (${HybridEvidence.MISS_AUDIO_MS / MS_PER_S} s saved)")
            emit(HybridEvidence.MissMark(now, toBytes(recent.latest(recent.capacity))))
        }
        val active = config.mode() == WakeMode.HYBRID && !unavailable
        val shadow = active && config.shadow()
        if (active != wasActive || shadow != wasShadow) switchTo(active, shadow, detector)
        val decodeVosk = !active || shadow || wakeWords.any { it.id != config.wakeId }
        val started = System.nanoTime()
        val vosk = if (decodeVosk) detector.accept(buf, n) else null.also { detector.skipFrame() }
        countVosk(System.nanoTime() - started)
        val wake = if (active) hybridFrame(samples, vosk, shadow, detector, now) else vosk?.also { voskWake(it, now) }
        disagreements.settle(now).forEach(::report)
        maybeStats(active, shadow)
        return wake
    }

    /** Stop the gate and verify threads (bounded, see the class KDoc). Idempotent. */
    fun close() {
        closed = true
        listOfNotNull(gateThread, verifyThread).forEach {
            it.interrupt()
            it.join(CLOSE_JOIN_MS)
        }
    }

    private fun hybridFrame(
        samples: ShortArray,
        vosk: WakeEvent?,
        shadow: Boolean,
        detector: WakeDetector,
        now: Long,
    ): WakeEvent? {
        startThreads()
        if (!frames.offer(Chunk(generation.get(), samples, now))) droppedFrames++
        // A Vosk wake of a word the hybrid doesn't decide is delivered as before.
        if (vosk != null && vosk.wakeId != config.wakeId) return vosk.also { voskWake(it, now) }
        if (vosk != null) {
            voskWake(vosk, now, delivered = false)
            if (shadow) disagreements.voskWake(now)
        }
        val p = pendingWake.getAndSet(null)?.takeIf { it.generation == generation.get() }
        if (p != null && shadow) finishShadow(detector, p, now)
        return p?.event
    }

    /** Judge the Vosk shadow's in-flight decode as a hybrid wake hands off, which would cut the shadow off. */
    private fun finishShadow(detector: WakeDetector, p: PendingWake, now: Long) {
        val j = detector.finishShadow() ?: return
        val o = j.outcome
        if (o !is WakeMatcher.Outcome.Match || o.id != config.wakeId) return
        voskWake(WakeEvent(o.id, j.transcript), now, delivered = false, atHandoff = true)
        disagreements.voskWake(p.candidateAtMs) // the same utterance the hybrid woke on
    }

    /** Apply a mode or shadow switch: reset Vosk if it was skipping, begin a new generation and log it. */
    private fun switchTo(active: Boolean, shadow: Boolean, detector: WakeDetector) {
        val first = wasActive == null
        val modeChanged = !first && active != wasActive
        val shadowResumed = shadow && wasShadow == false
        if (modeChanged || shadowResumed) detector.start()
        if (modeChanged) {
            generation.incrementAndGet()
            pendingWake.set(null)
        }
        if (shadow != tracking) disagreements.clear()
        tracking = shadow
        wasActive = active
        wasShadow = shadow
        val mode = when {
            active -> "hybrid (Vosk shadow ${if (shadow) "on" else "off"})"
            unavailable -> "Vosk (hybrid unavailable)"
            else -> "Vosk"
        }
        log("wake mode → $mode")
    }

    private fun startThreads() {
        if (gateThread != null) return
        gateThread = daemon("wake-hybrid-gate", ::runGate)
        verifyThread = daemon("wake-hybrid-verify", ::runVerify)
    }

    // ---- gate thread ----------------------------------------------------------------------------

    private fun runGate() {
        val gate = build("gate", config.newGate) ?: return
        gate.use { _ ->
            val candidates = CandidateGate(gate) { config.gateThreshold }
            var gen = -1L
            while (!closed) {
                val chunk = takeOrNull(frames) ?: continue
                if (chunk.generation != gen) {
                    candidates.reset()
                    gen = chunk.generation
                }
                val c = candidates.feed(chunk.samples, chunk.atMs)
                countGate(candidates.lastGateNanos)
                if (c != null && !work.offer(Work(gen, c))) {
                    droppedCandidates.incrementAndGet()
                    log("hybrid candidate dropped (verify thread busy)")
                }
            }
        }
    }

    // ---- verify thread --------------------------------------------------------------------------

    private fun runVerify() {
        val spotter = build("spotter", config.newSpotter) ?: return
        log("hybrid ready (gate ≥ ${config.gateThreshold})")
        spotter.use { _ ->
            while (!closed) {
                val w = takeOrNull(work) ?: continue
                runCatching { verify(spotter, w) }.onFailure { log("hybrid verify failed: $it") }
            }
        }
    }

    private fun verify(spotter: KeywordSpotter, w: Work) {
        val c = w.candidate
        val t0 = System.nanoTime()
        val spotted = runCatching { spotter.spot(toFloats(c.window, SPOTTER_TAIL_MS)) }
            .onFailure { log("hybrid spotter failed: $it") }
            .getOrNull()
        val spotterMs = msSince(t0)
        val early = HybridRule.decide(spotted, config.confirmKeyword, voskAccepted = false)
        early.wokeBy?.let { postWake(w.generation, c, it, spotted) }
        val t1 = System.nanoTime()
        val window = voskWindow(c.window)
        val voskMs = msSince(t1)
        val accepted = (window?.outcome as? WakeMatcher.Outcome.Match)?.id == config.wakeId
        val decision = HybridRule.decide(spotted, config.confirmKeyword, accepted)
        if (early.wokeBy == null) decision.wokeBy?.let { postWake(w.generation, c, it, spotted) }
        val vosk = voskVerdict(window)
        val verdict = "sherpa=${spotted ?: "error"} vosk=$vosk"
        log(
            "hybrid candidate gate=${fmt(c.peak)} $verdict [${window?.transcript.orEmpty()}] → " +
                (decision.wokeBy?.let { "WAKE ($it)" } ?: "no wake") + " ($spotterMs+$voskMs ms)",
        )
        if (tracking) disagreements.candidate(c.openedAtMs, decision.wokeBy != null, verdict)
        emit(
            HybridEvidence.Candidate(
                atMs = c.openedAtMs,
                gatePeak = c.peak,
                spotted = spotted ?: listOf(HybridEvidence.SPOT_ERROR),
                rival = decision.rival,
                vosk = vosk,
                voskTranscript = window?.transcript.orEmpty(),
                wokeBy = decision.wokeBy,
                spotterMs = spotterMs,
                voskMs = voskMs,
                audio = toBytes(c.audio),
            ),
        )
    }

    // A window check that couldn't run: no model yet (or already closed), or no Decoder could be built.
    private val noModel = VoskJudgement(WakeMatcher.Outcome.None, UNAVAILABLE)
    private val noDecoder = VoskJudgement(WakeMatcher.Outcome.None, ERROR)

    /** The Vosk window check on [window]; null when it threw. */
    private fun voskWindow(window: ShortArray): VoskJudgement? = runCatching {
        val bytes = toBytes(window)
        lease?.borrow { WakeDecoder.judgeWindow(it, wakeWords, bytes) ?: noDecoder } ?: noModel
    }.onFailure { log("hybrid Vosk window check failed: $it") }.getOrNull()

    private fun voskVerdict(j: VoskJudgement?): String = when {
        j == null || j === noDecoder -> ERROR

        j === noModel -> UNAVAILABLE

        else -> when (val o = j.outcome) {
            is WakeMatcher.Outcome.Match -> if (o.id == config.wakeId) "wake" else "wake:${o.id}"
            is WakeMatcher.Outcome.NearMiss -> "near-miss: ${o.reason}"
            WakeMatcher.Outcome.None -> "none"
        }
    }

    /** Hand a decided wake to the capture thread, unless a newer generation has begun since its candidate opened. */
    private fun postWake(gen: Long, c: CandidateGate.Candidate, by: String, spotted: List<String>?) {
        if (closed || gen != generation.get()) return
        val event = WakeEvent(config.wakeId, "hybrid by $by: gate=${fmt(c.peak)} sherpa=${spotted ?: "error"}")
        pendingWake.set(PendingWake(gen, event, c.openedAtMs))
    }

    // ---- shared helpers -------------------------------------------------------------------------

    /** Build a gate or spotter on its own thread; null, with the hybrid unavailable, when it throws. */
    private fun <T> build(what: String, factory: () -> T): T? = try {
        factory()
    } catch (
        // Throwable, not Exception: a missing native library or model fails with Errors (UnsatisfiedLinkError).
        @Suppress("TooGenericExceptionCaught")
        t: Throwable,
    ) {
        unavailable = true
        log("hybrid unavailable: $what failed to build ($t) → Vosk decides")
        null
    }

    /** The next item, or null after [POLL_MS] or on an interrupt (close, which the loop condition then sees). */
    private fun <T : Any> takeOrNull(queue: ArrayBlockingQueue<T>): T? = try {
        queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
    } catch (
        @Suppress("SwallowedException")
        _: InterruptedException,
    ) {
        null
    }

    private fun voskWake(event: WakeEvent, now: Long, delivered: Boolean = true, atHandoff: Boolean = false) {
        val role = if (delivered) {
            "decided"
        } else if (atHandoff) {
            "shadow at handoff"
        } else {
            "shadow"
        }
        if (!delivered) log("Vosk $role wake → ${event.wakeId} [${event.transcript}]")
        val audio = recent.latest(CandidateGate.samplesFor(HybridEvidence.VOSK_AUDIO_MS.toLong()))
        emit(HybridEvidence.VoskWake(now, event.wakeId, event.transcript, delivered, atHandoff, toBytes(audio)))
    }

    private fun report(d: HybridEvidence.Disagreement) {
        val detail = when (d.kind) {
            HybridEvidence.Disagreement.Kind.VOSK_ONLY ->
                "Vosk shadow woke, hybrid didn't (candidate: ${d.candidateVerdict ?: "none, gate stayed shut"})"

            HybridEvidence.Disagreement.Kind.HYBRID_ONLY -> "hybrid woke, Vosk shadow didn't (${d.candidateVerdict})"
        }
        log("disagreement: $detail")
        emit(d)
    }

    private fun emit(e: HybridEvidence) {
        runCatching { config.onEvidence(e) }.onFailure { log("hybrid evidence dropped: $it") }
    }

    private fun countVosk(nanos: Long) {
        statFrames++
        voskNanos += nanos
        voskMaxNanos = maxOf(voskMaxNanos, nanos)
    }

    private fun countGate(nanos: Long) {
        if (nanos == 0L) return
        gateRuns.incrementAndGet()
        gateNanos.addAndGet(nanos)
        gateMaxNanos.accumulateAndGet(nanos, ::maxOf)
    }

    private fun maybeStats(active: Boolean, shadow: Boolean) {
        val nowMono = clock()
        if (nowMono - statsFrom < STATS_EVERY_MS) return
        statsFrom = nowMono
        val runs = gateRuns.getAndSet(0)
        val stats = HybridEvidence.Stats(
            atMs = wallClock(),
            mode = if (active) WakeMode.HYBRID else WakeMode.VOSK,
            shadow = shadow,
            frames = statFrames,
            voskMsMean = if (statFrames == 0) 0.0 else voskNanos / NANOS_PER_MS / statFrames,
            voskMsMax = voskMaxNanos / NANOS_PER_MS.toLong(),
            gateMsMean = if (runs == 0) 0.0 else gateNanos.getAndSet(0) / NANOS_PER_MS / runs,
            gateMsMax = gateMaxNanos.getAndSet(0) / NANOS_PER_MS.toLong(),
            droppedFrames = droppedFrames,
            droppedCandidates = droppedCandidates.getAndSet(0),
        )
        log(
            "hybrid stats: ${stats.frames} frames, vosk ${fmt(stats.voskMsMean)}/${stats.voskMsMax} ms, " +
                "gate ${fmt(stats.gateMsMean)}/${stats.gateMsMax} ms (mean/max per frame), " +
                "dropped ${stats.droppedFrames} frames ${stats.droppedCandidates} candidates",
        )
        emit(stats)
        statFrames = 0
        voskNanos = 0
        voskMaxNanos = 0
        droppedFrames = 0
    }

    companion object {
        /** Capture frames queued for the gate thread before they are dropped: 5 s. */
        private const val FRAME_QUEUE = 50

        /** Candidates queued for the verify thread before they are dropped. */
        private const val WORK_QUEUE = 4

        private const val POLL_MS = 200L
        const val CLOSE_JOIN_MS = 500L
        private const val STATS_EVERY_MS = 60_000L

        /**
         * Silence after the spotter's window: the streaming spotter flushes its last chunks only when silence follows,
         * so without it a "jarvis" at the window's end goes unspotted.
         */
        private const val SPOTTER_TAIL_MS = 600L

        private const val UNAVAILABLE = "unavailable"
        private const val ERROR = "error"
        private const val MS_PER_S = 1_000
        private const val NANOS_PER_MS = 1_000_000.0
        private const val PCM_SCALE = 32_768f
        private const val BYTE_MASK = 0xFF
        private const val BYTE_BITS = 8

        private fun daemon(name: String, body: () -> Unit): Thread = Thread(body, name).apply {
            isDaemon = true
            start()
        }

        private fun msSince(startNanos: Long): Long = ((System.nanoTime() - startNanos) / NANOS_PER_MS).toLong()

        private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)

        private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)

        /** The first [n] bytes of 16-bit LE PCM as samples. */
        fun toShorts(buf: ByteArray, n: Int): ShortArray = ShortArray(n / PcmCaptureFormat.BYTES_PER_SAMPLE) {
            ((buf[2 * it].toInt() and BYTE_MASK) or (buf[2 * it + 1].toInt() shl BYTE_BITS)).toShort()
        }

        fun toBytes(samples: ShortArray): ByteArray {
            val out = ByteArray(samples.size * PcmCaptureFormat.BYTES_PER_SAMPLE)
            samples.forEachIndexed { i, s ->
                out[2 * i] = s.toByte()
                out[2 * i + 1] = (s.toInt() shr BYTE_BITS).toByte()
            }
            return out
        }

        /** Samples scaled to [-1, 1], followed by [tailMs] of silence. */
        fun toFloats(samples: ShortArray, tailMs: Long): FloatArray {
            val out = FloatArray(samples.size + CandidateGate.samplesFor(tailMs))
            samples.forEachIndexed { i, s -> out[i] = s / PCM_SCALE }
            return out
        }
    }
}
