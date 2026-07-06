package com.portal.commons.audio

import android.content.Context
import com.portal.commons.DebugLog
import com.portal.commons.audio.oww.AudioFeatures
import com.portal.commons.audio.oww.WakeWordModelRunner

/**
 * On-device wake detection using **openWakeWord** (ONNX Runtime, keyless, offline, no GMS). Scope is
 * deliberately narrow: load the shared melspectrogram + embedding models and one model per wake word,
 * then per audio frame compute the [1,16,96] feature window once and score every wake model over it.
 *
 * **Accuracy policy (simple, no gates):** a wake fires when its score clears [OwwWakeWord.threshold]
 * for [OwwTuning.DEBOUNCE_FRAMES] consecutive frames (kills single-frame noise spikes). No grammar/keyword/
 * confidence gates — openWakeWord emits a single trained score, not a transcript. The post-fire cooldown lives
 * in [WakeMicEngine].
 *
 * Models load off the caller's thread; [onReady] fires when scoring can begin, [onUnavailable] if a
 * model asset is missing/unloadable. [accept] is a no-op until ready. Drive [accept]/[reset]/[close]
 * from a single capture thread (WakeMicEngine does).
 */
class OwwRecognizer(
    context: Context,
    initialWakeWords: List<OwwWakeWord>,
    private val onReady: () -> Unit,
    private val onUnavailable: () -> Unit,
) {
    /** A wake fired: [id] with the [score] that tripped it (for the fire log). */
    data class Match(val id: String, val score: Float)

    private val assets = context.applicationContext.assets

    @Volatile private var features: AudioFeatures? = null
    @Volatile private var runners: List<Pair<OwwWakeWord, WakeWordModelRunner>> = emptyList()
    @Volatile private var closed = false
    private val consecutive = HashMap<String, Int>() // capture-thread-only
    private var loggedInferenceError = false          // capture-thread-only; log a scoring failure once

    /**
     * Optional per-frame score sink for on-device tuning/benchmarking: invoked with (wake id, score) for
     * every scored frame. Null in production (no overhead beyond the null check). Set by [WakeMicEngine]'s
     * bench mode. Called on the capture thread.
     */
    @Volatile var scoreLogger: ((String, Float) -> Unit)? = null

    /**
     * Wake-only input gain (diagnostic/tuning): the frame samples are multiplied by this before the mel
     * model, since openWakeWord's melspectrogram is amplitude-sensitive (int16-magnitude input) — a quiet
     * capture scores low. 1.0 = no change (production). Applied only to the recognizer's copy in
     * [toInt16Floats]; the raw stream [PcmCaptureSession] sees for dead-mic detection is untouched. Set by
     * [WakeMicEngine] from the on-device tuning marker. Clip-aware (saturates at ±32k).
     */
    @Volatile var gain: Float = 1.0f

    init {
        Thread {
            try {
                val feats = AudioFeatures(assets, MEL_MODEL, EMBEDDING_MODEL)
                val built = initialWakeWords.map { it to WakeWordModelRunner(assets, it.modelAsset) }
                if (closed) {
                    feats.close(); built.forEach { it.second.close() }
                    return@Thread
                }
                features = feats
                runners = built
                onReady()
            } catch (t: Throwable) {
                DebugLog.log("oww model load failed: ${t.message}")
                onUnavailable()
            }
        }.apply { isDaemon = true; name = "oww-model-load" }.start()
    }

    /** Feed one frame of 16 kHz mono 16-bit PCM; returns a [Match] when a wake fires, else null. */
    fun accept(buf: ByteArray, n: Int): Match? {
        val feats = features ?: return null
        // Guard the ONNX inference: an unexpected model output (rank/type/dtype — likely once plugins ship
        // their own trained models) must not throw out of the capture loop and silently kill detection.
        // Skip the frame and keep listening; log the first failure so it's diagnosable.
        return try {
            val window = feats.accept(toInt16Floats(buf, n))
            val log = scoreLogger
            for ((wake, runner) in runners) {
                val score = runner.score(window)
                log?.invoke(wake.id, score)
                if (score >= wake.threshold) {
                    val c = (consecutive[wake.id] ?: 0) + 1
                    if (c >= OwwTuning.DEBOUNCE_FRAMES) {
                        consecutive[wake.id] = 0
                        return Match(wake.id, score)
                    }
                    consecutive[wake.id] = c
                } else {
                    consecutive[wake.id] = 0
                }
            }
            null
        } catch (t: Throwable) {
            if (!loggedInferenceError) {
                loggedInferenceError = true
                DebugLog.log("oww inference error (frame skipped): ${t.message}")
            }
            null
        }
    }

    /** Drop buffered audio + debounce state on each (re)start so pre-pause audio can't linger. */
    fun reset() {
        features?.reset()
        consecutive.clear()
    }

    fun close() {
        closed = true
        runCatching { features?.close() }
        runners.forEach { runCatching { it.second.close() } }
        features = null
        runners = emptyList()
    }

    private fun toInt16Floats(buf: ByteArray, n: Int): FloatArray {
        val count = n / 2
        val out = FloatArray(count)
        val g = gain
        var j = 0
        for (i in 0 until count) {
            // little-endian 16-bit → int16-magnitude float (NOT normalised — the mel model wants int16 values)
            val lo = buf[j].toInt() and 0xFF
            val hi = buf[j + 1].toInt()
            val s = ((hi shl 8) or lo).toShort().toFloat()
            out[i] = if (g == 1.0f) s else (s * g).coerceIn(-32768f, 32767f)
            j += 2
        }
        return out
    }

    companion object {
        // Bundled model asset paths (shared melspec + embedding); detector tuning lives in [OwwTuning].
        const val MEL_MODEL = "oww/melspectrogram.onnx"
        const val EMBEDDING_MODEL = "oww/embedding_model.onnx"
    }
}
