package com.portal.commons.audio

/**
 * One wake phrase the recognizer listens for.
 *
 * This is intentionally decoupled from *what happens* on a match — routing/handoff lives in the consuming
 * app (e.g. portal-wake's WakeTarget), which pairs a [WakeWord] with the app that should be launched.
 *
 * A wake word is a [keyword] (the salient word the model listens for) optionally preceded by a [lead] word
 * (e.g. "hey", "hi"). The lead is **declared by the plugin** (it's the leading word of the phrase it
 * registers), not hardcoded here.
 *
 * @param id              stable key reported back on a match (e.g. "jarvis", "alexa").
 * @param keyword         the salient word (e.g. "jarvis").
 * @param lead            the word that must precede [keyword] (e.g. "hey"), or null for a bare keyword.
 * @param scoreThreshold  openWakeWord **classifier score** in [0, 1] required to fire (not an ASR
 *   confidence). Maps to `com.portal.wake.min_confidence` in plugin manifests. Defaults to
 *   [DEFAULT_SCORE_THRESHOLD] when a plugin omits that meta-data.
 */
data class WakeWord(
    val id: String,
    val keyword: String,
    val lead: String?,
    val scoreThreshold: Double,
) {
    /** The full spoken phrase, derived from [lead] + [keyword] (e.g. "hey jarvis"). */
    val phrase: String get() = lead?.let { "$it $keyword" } ?: keyword

    companion object {
        /** Default classifier score threshold when a plugin omits `com.portal.wake.min_confidence`. */
        const val DEFAULT_SCORE_THRESHOLD = 0.5

        private val WHITESPACE = Regex("\\s+")

        fun tokenize(phrase: String): List<String> = phrase.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }

        fun fromPhrase(phrase: String, id: String? = null, scoreThreshold: Double): WakeWord? {
            val words = tokenize(phrase)
            val keyword = words.lastOrNull() ?: return null
            val lead = if (words.size >= 2) words[words.size - 2] else null
            return WakeWord(
                id = id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: keyword,
                keyword = keyword,
                lead = lead,
                scoreThreshold = scoreThreshold,
            )
        }
    }
}
