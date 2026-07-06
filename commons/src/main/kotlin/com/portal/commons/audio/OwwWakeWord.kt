package com.portal.commons.audio

/**
 * One wake phrase the openWakeWord detector listens for. openWakeWord maps a phrase to a **trained
 * model** and a single score threshold — there is no grammar and no per-word confidence, so the
 * accuracy policy is just
 * "score ≥ [threshold] for a couple of consecutive frames" (see OwwRecognizer). openWakeWord is
 * NOT open-vocabulary: a phrase is only detectable if a trained model exists for it.
 *
 * @param id         stable key reported on a match (e.g. "jarvis").
 * @param modelAsset assets-relative path to the wake-word ONNX (e.g. "oww/hey_jarvis_v0.1.onnx").
 * @param threshold  fire when the model score clears this. Lower = more sensitive (higher recall).
 */
data class OwwWakeWord(
    val id: String,
    val modelAsset: String,
    val threshold: Float = OwwTuning.DEFAULT_THRESHOLD,
)
