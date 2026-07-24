package com.portal.commons.audio

import java.io.File

/** Named [WakeDetector.Factory] entries for [WakeMicConfig.detectors]. */
object WakeDetectors {

    /**
     * Vosk on-device wake-word detector.
     *
     * @param modelDir null = bundled `assets/model-en-us` (portal-wake); a directory = an already-unpacked,
     *   downloaded model (portal-assistant on gen2).
     */
    fun vosk(modelDir: File? = null): WakeDetector.Factory = VoskWakeDetector.factory(modelDir)

    /**
     * openWakeWord neural KWS — reads bundled ONNX assets from `commons-android` (`assets/oww/…`).
     * Use alongside or instead of [vosk]; apps opt in via [WakeMicConfig.detectors].
     */
    fun oww(): WakeDetector.Factory = OpenWakeWordDetector.factory()

    /** openWakeWord with explicit per-phrase ONNX models (portal-wake plugin models). */
    fun oww(phraseConfigs: List<OpenWakeWordDetector.PhraseClassifierConfig>): WakeDetector.Factory = OpenWakeWordDetector.factory(phraseConfigs)

    /**
     * **The cascade**: openWakeWord proposes at a loose threshold, a phrase-constrained Vosk decode of the
     * same 2 s window disposes. See [TwoStageWakeDetector] for the measurements behind it.
     *
     * Prefer this to configuring [oww] and [vosk] together in [WakeMicConfig.detectors] — that runs them in
     * *parallel*, where either can fire, which unions their false accepts instead of intersecting them.
     *
     * @param modelDir stage 2's Vosk model: null = bundled `assets/model-en-us` (portal-wake); a directory
     *   = an already-unpacked, downloaded model (portal-assistant on gen2).
     */
    fun twoStage(modelDir: File? = null): WakeDetector.Factory = TwoStageWakeDetector.factory(modelDir)

    /** The cascade with explicit per-phrase stage-1 ONNX models (portal-wake plugin models). */
    fun twoStage(
        phraseConfigs: List<OpenWakeWordDetector.PhraseClassifierConfig>,
        modelDir: File? = null,
    ): WakeDetector.Factory = TwoStageWakeDetector.factory(phraseConfigs, modelDir)
}
