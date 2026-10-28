package com.portal.commons.audio

import java.io.File

/** The **wake model** (see `GLOSSARY.md`): the Vosk model the wake detector loads, named in [WakeMicConfig.model]. */
sealed interface WakeModel {
    /** The model shipped in the APK's assets (portal-wake), unpacked to filesDir on first load. */
    data object Bundled : WakeModel

    /** An already-unpacked model on disk, e.g. one downloaded at runtime (portal-assistant on gen2). */
    data class Unpacked(val dir: File) : WakeModel
}
