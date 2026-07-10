package com.portal.commons.audio

/**
 * One wake phrase the detector listens for.
 *
 * This is intentionally decoupled from *what happens* on a match — routing/handoff lives in the consuming
 * app (e.g. portal-wake's WakeTarget), which pairs a [WakeWord] with the app that should be launched.
 *
 * A wake word is a [keyword] (the salient word) optionally preceded by a [lead] word (e.g. "hey", "hi").
 * The lead is **declared by the plugin** (the leading word of the phrase it registers), not hardcoded here.
 * openWakeWord scores the full spoken phrase acoustically; [lead]/[keyword] are still used for id/phrase
 * bookkeeping and plugin discovery.
 *
 * @param id              stable key reported back on a match (e.g. "jarvis", "alexa").
 * @param keyword         the salient word (e.g. "jarvis").
 * @param lead            the word that precedes [keyword] in the registered phrase (e.g. "hey"), or null.
 * @param minConf         retained for plugin API compatibility; openWakeWord uses [scoreThreshold].
 * @param scoreThreshold  openWakeWord classifier score in [0, 1] required to fire.
 *   Maps to `com.portal.wake.min_confidence` in plugin manifests when only one value is supplied.
 */
data class WakeWord(
    val id: String,
    val keyword: String,
    val lead: String?,
    val minConf: Double,
    val scoreThreshold: Double = DEFAULT_SCORE_THRESHOLD,
) {
    /** The full spoken phrase, derived from [lead] + [keyword] (e.g. "hey jarvis"). */
    val phrase: String get() = lead?.let { "$it $keyword" } ?: keyword

    companion object {
        /** Default openWakeWord classifier threshold when a plugin omits `com.portal.wake.min_confidence`. */
        const val DEFAULT_SCORE_THRESHOLD = 0.5

        /** Default [minConf] when a caller does not supply one (unused by openWakeWord scoring). */
        const val DEFAULT_MIN_CONF = 0.5

        private val WHITESPACE = Regex("\\s+")

        fun tokenize(phrase: String): List<String> = phrase.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }

        fun fromPhrase(
            phrase: String,
            id: String? = null,
            minConf: Double = DEFAULT_MIN_CONF,
            scoreThreshold: Double = DEFAULT_SCORE_THRESHOLD,
        ): WakeWord? {
            val words = tokenize(phrase)
            val keyword = words.lastOrNull() ?: return null
            val lead = if (words.size >= 2) words[words.size - 2] else null
            return WakeWord(
                id = id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: keyword,
                keyword = keyword,
                lead = lead,
                minConf = minConf,
                scoreThreshold = scoreThreshold,
            )
        }
    }
}
