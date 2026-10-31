package com.portal.commons.audio

/**
 * The hybrid wake detector's decision on one candidate, as a pure function: it wakes when the spotter confirms the
 * wake keyword as the only keyword, or when the Vosk window check accepts and the spotter heard no rival. A failed
 * spotter (`spotted == null`) heard no rival, so the Vosk path still decides.
 */
internal object HybridRule {

    /** [wokeBy] is `sherpa`, `vosk-window`, or null for no wake; [rival] is the first rival keyword spotted. */
    class Decision(val wokeBy: String?, val rival: String?)

    const val BY_SPOTTER = "sherpa"
    const val BY_VOSK_WINDOW = "vosk-window"

    fun decide(spotted: List<String>?, confirmKeyword: String, voskAccepted: Boolean): Decision {
        val rival = spotted?.firstOrNull { it != confirmKeyword }
        val wokeBy = when {
            rival != null -> null
            spotted != null && confirmKeyword in spotted -> BY_SPOTTER
            voskAccepted -> BY_VOSK_WINDOW
            else -> null
        }
        return Decision(wokeBy, rival)
    }
}
