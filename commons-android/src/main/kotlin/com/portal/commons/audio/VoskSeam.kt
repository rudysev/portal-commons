package com.portal.commons.audio

/*
 * The seam under the wake detector: the only place native libvosk is touched. Everything above it — grammar
 * building, warm-up, JSON parsing, [WakeMatcher], idle reset, replay, grammar hot-swap — runs in JVM tests
 * against fakes. The libvosk adapters ([LibVoskModelLoader], [LibVoskModel], [LibVoskDecoder]) are thin
 * pass-throughs.
 */

/** Starts an asynchronous model load. Delivers exactly one of [onLoaded] / [onFailed], on any thread. */
internal fun interface VoskModelLoader {
    fun load(onLoaded: (VoskModel) -> Unit, onFailed: () -> Unit)
}

/** A loaded Vosk model: builds [VoskDecoder]s. Closing it frees the native model. */
internal interface VoskModel {
    /**
     * Build a decoder biased to [grammar] (a Vosk JSON phrase list), or open vocabulary when null. Per-word
     * confidence is on. Throws if the native build fails (e.g. the model rejects the grammar).
     */
    fun newDecoder(grammar: String?): VoskDecoder

    fun close()
}

/** One streaming decoder (a libvosk `Recognizer`). Not thread-safe: one thread at a time. */
internal interface VoskDecoder {
    /** Feed [n] bytes of 16 kHz mono 16-bit PCM. True when this frame finalized a decode (Vosk endpointed). */
    fun acceptFrame(buf: ByteArray, n: Int): Boolean

    /** The final-result JSON of the decode the last [acceptFrame] finalized. */
    fun finalResult(): String

    /**
     * Finalize the in-flight decode now, without waiting for Vosk to endpoint, and return its final-result JSON. The
     * Decoder starts a fresh utterance after it.
     */
    fun flushResult(): String

    /** The in-flight partial-result JSON (`{"partial" : "hey jar"}`). */
    fun partialResult(): String

    /** Drop the in-flight decode (bounds the lattice). */
    fun reset()

    fun close()
}
