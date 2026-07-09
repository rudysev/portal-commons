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
}
