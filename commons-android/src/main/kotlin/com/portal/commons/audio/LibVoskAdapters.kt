package com.portal.commons.audio

import android.content.Context
import com.portal.commons.PcmCaptureFormat
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * The libvosk [VoskModelLoader]. Two sources: null [modelDir] = the bundled `assets/[MODEL_ASSET]`, unpacked
 * to filesDir (portal-wake); a non-null dir = an already-unpacked model on disk, loaded directly — for a
 * consumer that **downloads** the model at runtime instead of shipping it in the APK (portal-assistant on
 * gen2). A dir that isn't a complete model fails, same as a missing asset.
 */
internal class LibVoskModelLoader(private val context: Context, private val modelDir: File?) : VoskModelLoader {

    override fun load(onLoaded: (VoskModel) -> Unit, onFailed: () -> Unit) {
        if (modelDir != null) loadFromDir(modelDir, onLoaded, onFailed) else unpackFromAssets(onLoaded, onFailed)
    }

    /** Bundled-asset source (portal-wake): unpack `assets/[MODEL_ASSET]` into filesDir. */
    private fun unpackFromAssets(onLoaded: (VoskModel) -> Unit, onFailed: () -> Unit) {
        org.vosk.android.StorageService.unpack(
            context,
            MODEL_ASSET,
            MODEL_TARGET,
            { m -> onLoaded(LibVoskModel(m)) },
            { onFailed() },
        )
    }

    /**
     * Downloaded-model source (portal-assistant on gen2): the model is already unpacked on disk, so load the
     * [Model] straight from [dir] on a background thread (the native load is heavy).
     */
    private fun loadFromDir(dir: File, onLoaded: (VoskModel) -> Unit, onFailed: () -> Unit) {
        // A complete Vosk model has all of [MODEL_DIRS]; a partial dir (e.g. a truncated download) fails fast
        // here instead of after a slow native Model() load — defense in depth for any caller passing modelDir.
        if (MODEL_DIRS.any { !File(dir, it).isDirectory }) {
            onFailed()
            return
        }
        Thread {
            val m = runCatching { Model(dir.absolutePath) }.getOrNull()
            if (m != null) onLoaded(LibVoskModel(m)) else onFailed()
        }.apply {
            isDaemon = true
            name = "wake-model-load"
        }.start()
    }

    companion object {
        const val MODEL_ASSET = "model-en-us" // assets/model-en-us/
        const val MODEL_TARGET = "vosk-model" // unpacked into filesDir
        private val MODEL_DIRS = listOf("am", "conf", "graph", "ivector") // every dir a complete Vosk model has
    }
}

/** The libvosk [VoskModel]: a pass-through over [Model]. */
internal class LibVoskModel(private val model: Model) : VoskModel {

    override fun newDecoder(grammar: String?): VoskDecoder {
        val rate = PcmCaptureFormat.SAMPLE_RATE.toFloat()
        val rec = if (grammar != null) Recognizer(model, rate, grammar) else Recognizer(model, rate)
        runCatching { rec.setWords(true) } // per-word confidence, which the matcher gates on
        return LibVoskDecoder(rec)
    }

    override fun close() = model.close()
}

/** The libvosk [VoskDecoder]: a pass-through over [Recognizer]. */
internal class LibVoskDecoder(private val rec: Recognizer) : VoskDecoder {
    override fun acceptFrame(buf: ByteArray, n: Int): Boolean = rec.acceptWaveForm(buf, n)

    override fun finalResult(): String = rec.result

    override fun partialResult(): String = rec.partialResult

    override fun reset() = rec.reset()

    override fun close() = rec.close()
}
