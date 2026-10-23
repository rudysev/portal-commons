package com.portal.commons.audio

import com.portal.commons.PcmCaptureFormat
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal const val FRAME = PcmCaptureFormat.FRAME_BYTES

internal val JARVIS = WakeWord.fromPhrase("hey jarvis", id = "jarvis", minConf = WakeMatcher.STRICT_MIN_CONF)!!
internal val ALEXA = WakeWord.fromPhrase("hey alexa", id = "alexa", minConf = WakeMatcher.STRICT_MIN_CONF)!!

/** A [VoskModelLoader] the test completes, fails, or throws from on demand. */
internal class FakeVoskLoader(private val throwOnLoad: Boolean = false) : VoskModelLoader {
    private var onLoaded: ((VoskModel) -> Unit)? = null
    private var onFailed: (() -> Unit)? = null

    override fun load(onLoaded: (VoskModel) -> Unit, onFailed: () -> Unit) {
        check(!throwOnLoad) { "native init failed" }
        this.onLoaded = onLoaded
        this.onFailed = onFailed
    }

    fun complete(model: VoskModel) = checkNotNull(onLoaded) { "load() not started" }(model)

    fun fail() = checkNotNull(onFailed) { "load() not started" }()
}

/**
 * A [VoskModel] that hands out [ScriptedDecoder]s and records each grammar it was asked for. [finals] is the
 * script of final-result JSON, one per finalizing frame, shared by whichever decoder is live — so a test can
 * queue a decode before the decoder that will produce it exists. [rejectGrammar]
 * fails every grammar build (open vocabulary still works); [failBuilds] fails every build. [firstBuildGate]
 * parks the first build until the test releases it, to open a race window.
 */
internal class FakeVoskModel : VoskModel {
    val finals = ConcurrentLinkedQueue<String>()

    val decoders = CopyOnWriteArrayList<ScriptedDecoder>()

    @Volatile var rejectGrammar = false

    @Volatile var failBuilds = false

    @Volatile var firstBuildGate: Gate? = null

    @Volatile var closeCount = 0

    /** The decoder most recently built — the one a correct detector is feeding. */
    val live: ScriptedDecoder get() = decoders.last()

    override fun newDecoder(grammar: String?): VoskDecoder {
        firstBuildGate?.let {
            firstBuildGate = null
            it.park()
        }
        check(!failBuilds) { "decoder build failed" }
        check(grammar == null || !rejectGrammar) { "grammar rejected" }
        return ScriptedDecoder(grammar, finals).also { decoders.add(it) }
    }

    override fun close() {
        closeCount++
    }
}

/** Lets a test hold a fake call open: the call [park]s until [release]. */
internal class Gate {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    fun park() {
        entered.countDown()
        check(released.await(5, TimeUnit.SECONDS)) { "gate never released" }
    }

    /** Wait until the gated call is parked (true), or [timeoutMs] passes without one (false). */
    fun awaitEntered(timeoutMs: Long): Boolean = entered.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun release() = released.countDown()
}

/**
 * A scripted [VoskDecoder]. A frame built with `endpoint = true` (see [frame]) finalizes, returning the next
 * queued [finals] JSON (an empty decode when none is queued); every other frame does not. [partial] is the
 * partial-result JSON. [log] records what the detector did to it: `silence` (a warm-up frame), `frame:<tag>`,
 * `reset`, `close`.
 */
internal class ScriptedDecoder(val grammar: String?, private val finals: Queue<String>) : VoskDecoder {
    @Volatile var partial = """{"partial" : ""}"""

    var partialQueries = 0
        private set

    val log = CopyOnWriteArrayList<String>()

    /** Every non-silent frame fed, copied at the length the detector passed. */
    val fed = CopyOnWriteArrayList<ByteArray>()

    @Volatile var closed = false
        private set

    private var lastFinal = ""

    override fun acceptFrame(buf: ByteArray, n: Int): Boolean {
        val copy = buf.copyOf(n)
        if (copy.size == PcmCaptureFormat.FRAME_BYTES && copy.all { it == 0.toByte() }) {
            log.add("silence")
            return false
        }
        fed.add(copy)
        log.add("frame:${copy[0]}")
        if (copy.size < 2 || copy[1] != ENDPOINT) return false
        lastFinal = finals.poll() ?: """{"text" : ""}"""
        return true
    }

    override fun finalResult(): String = lastFinal

    override fun partialResult(): String {
        partialQueries++
        return partial
    }

    override fun reset() {
        log.add("reset")
    }

    override fun close() {
        closed = true
        log.add("close")
    }

    /** The tags of the frames fed, in order. */
    fun fedTags(): List<Int> = fed.map { it[0].toInt() }

    companion object {
        const val ENDPOINT: Byte = 1

        /** A capture frame tagged [tag] (> 0, so it is never warm-up silence); [endpoint] finalizes a decode. */
        fun frame(tag: Int, endpoint: Boolean = false): ByteArray = ByteArray(PcmCaptureFormat.FRAME_BYTES).also {
            require(tag in 1..127)
            it[0] = tag.toByte()
            if (endpoint) it[1] = ENDPOINT
        }
    }
}

/** Records every [WakeDetector.Events] callback as a line: `ready`, `unavailable`, `wake:<id> [<transcript>]`, or the diagnostic. */
internal class RecordingEvents(private val onWakeHook: (WakeEvent) -> Unit = {}) : WakeDetector.Events {
    val lines = CopyOnWriteArrayList<String>()

    override fun onReady(detectorId: String) {
        lines.add("ready")
    }

    override fun onUnavailable(detectorId: String) {
        lines.add("unavailable")
    }

    override fun onWake(event: WakeEvent) {
        lines.add("wake:${event.wakeId} [${event.transcript}]")
        onWakeHook(event)
    }

    override fun onDiagnostic(detectorId: String, message: String) {
        lines.add(message)
    }
}

/** A [WakeHandoffCooldown] the test switches by hand. */
internal class FakeCooldown : WakeHandoffCooldown {
    @Volatile var cooling = false

    override fun isCoolingDown(wakeId: String) = cooling

    override fun isAnyCoolingDown() = cooling
}
