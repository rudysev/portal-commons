package com.portal.commons.audio

/** Named [WakeDetector.Factory] entries for [WakeMicConfig.detectors]. */
object WakeDetectors {

    /**
     * openWakeWord neural KWS — reads bundled ONNX assets from `commons-android` (`assets/oww/…`).
     * Apps opt in via [WakeMicConfig.detectors].
     */
    fun oww(): WakeDetector.Factory = OpenWakeWordDetector.factory()

    /** openWakeWord with explicit per-phrase ONNX models (portal-wake plugin models). */
    fun oww(phraseConfigs: List<OpenWakeWordDetector.PhraseClassifierConfig>): WakeDetector.Factory =
        OpenWakeWordDetector.factory(phraseConfigs)
}
