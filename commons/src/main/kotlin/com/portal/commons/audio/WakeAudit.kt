package com.portal.commons.audio

/**
 * Where the wake decoder sends the **audio behind a decision**, so it can be checked by ear afterwards and kept for
 * training and regression replay.
 *
 * A `debug.txt` line can't tell a false wake from someone genuinely saying the phrase: a background-audio false fire
 * decodes as a bare `[hey(92) jarvis(97)]`, the same as a real wake, and passes every [WakeMatcher] gate. Only the
 * audio settles it. Null (the default on `WakeMicConfig`) turns capture off and costs nothing on the capture thread.
 *
 * **Threading.** [save] is called on the **capture thread**, inside a frame, and must not block: writing there would
 * hold the capture thread past a pause's stop join. [WakeClipRecorder] hands the write to its own thread.
 */
fun interface WakeAudit {

    /**
     * Persist [pcm] (16 kHz mono 16-bit LE, oldest sample first, up to [CLIP_MS] long and ending with the frame that
     * finalized the decode) as evidence of a [clip] for [word]: the wake id of a wake, the decoded keyword of a
     * near-miss. The array is a snapshot the caller never touches again.
     */
    fun save(clip: Clip, word: String, pcm: ByteArray)

    /** What a clip is evidence *of*. [prefix] leads the filename so a pull can be triaged by name alone. */
    enum class Clip(val prefix: String) {
        /** A wake fired and the mic was handed off: a real wake, or a false one to label. */
        WAKE("wake"),

        /** A wake keyword decoded but a gate rejected it: a missed wake, or a hard negative. */
        NEAR("near"),
    }

    companion object {
        /**
         * The audio kept behind a decision: the finalized utterance (Vosk endpoints on trailing silence, so a
         * "hey jarvis" ends a second or so before it) plus lead-in, so a replay starts the decoder before the phrase.
         */
        const val CLIP_MS = 5_000
    }
}
