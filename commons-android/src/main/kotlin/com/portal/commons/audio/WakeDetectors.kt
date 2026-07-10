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
     * Not yet wired into either app; enabling it is a follow-up commit.
     */
    fun oww(): WakeDetector.Factory = OpenWakeWordDetector.factory()

    /** openWakeWord with explicit per-phrase ONNX models (portal-wake plugin models). */
    fun oww(phraseConfigs: List<OpenWakeWordDetector.PhraseClassifierConfig>): WakeDetector.Factory =
        OpenWakeWordDetector.factory(phraseConfigs)
}
