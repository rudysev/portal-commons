package com.portal.commons.audio

import android.content.Context
import org.vosk.Model
import java.io.File

/**
 * Where a Vosk [Model] comes from, and how it gets loaded off the caller's thread. Extracted so the two
 * consumers — [WakeRecognizer] (streaming, stage 1 of the Vosk-only detector) and [VoskPhraseVerifier]
 * (one-shot, stage 2 of the cascade) — share one copy of the source selection and the "is this actually a
 * model directory" check, rather than drifting apart.
 *
 * Two sources, unchanged from the shipped behaviour:
 *  - **bundled asset** (`modelDir == null`): unpack `assets/[MODEL_ASSET]` into filesDir — portal-wake,
 *    which ships the model in the APK.
 *  - **downloaded directory**: an already-unpacked model, loaded directly on a background thread (the
 *    native load is heavy) — portal-assistant on gen2, which fetches the model at runtime.
 *
 * Both paths call exactly one of [onLoaded] / [onUnavailable], on a **background thread**, never the
 * caller's. A partial or missing directory is "no model" ([onUnavailable]), the same as a missing asset —
 * it fails fast on the directory shape instead of after a slow native load.
 */
internal object VoskModelLoader {

    const val MODEL_ASSET = "model-en-us" // assets/model-en-us/
    const val MODEL_TARGET = "vosk-model" // unpacked into filesDir

    /** Every directory a complete Vosk model has — the cheap completeness check for a downloaded dir. */
    private val MODEL_DIRS = listOf("am", "conf", "graph", "ivector")

    fun load(context: Context, modelDir: File?, onLoaded: (Model) -> Unit, onUnavailable: () -> Unit) {
        try {
            if (modelDir != null) loadFromDir(modelDir, onLoaded, onUnavailable) else unpack(context, onLoaded, onUnavailable)
        } catch (
            @Suppress("SwallowedException")
            t: Throwable,
        ) {
            // Intentionally swallowed: a model-load / native-init failure must not crash the service. By
            // contract every failure is surfaced as onUnavailable(), which the owning detector logs.
            onUnavailable()
        }
    }

    private fun unpack(context: Context, onLoaded: (Model) -> Unit, onUnavailable: () -> Unit) {
        org.vosk.android.StorageService.unpack(context, MODEL_ASSET, MODEL_TARGET, onLoaded, { onUnavailable() })
    }

    private fun loadFromDir(dir: File, onLoaded: (Model) -> Unit, onUnavailable: () -> Unit) {
        if (MODEL_DIRS.any { !File(dir, it).isDirectory }) {
            onUnavailable()
            return
        }
        Thread {
            val m = runCatching { Model(dir.absolutePath) }.getOrNull()
            if (m != null) onLoaded(m) else onUnavailable()
        }.apply {
            isDaemon = true
            name = "wake-model-load"
        }.start()
    }
}
