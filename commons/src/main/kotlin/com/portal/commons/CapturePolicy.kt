package com.portal.commons

/**
 * How a [PcmCapture] recovers from a failing mic and when it gives up on a silent one. Every rule is opt-in: the
 * default policy only captures. Each consumer builds its own value; the rules are held by the capture's frame loop.
 *
 * - [rebuildAfterReadFailures]: that many consecutive read errors rebuild the device.
 * - [idleRebuildMs]: no full frame delivered for that long rebuilds the device, catching a stolen / half-dead mic that
 *   returns no data or a sub-frame trickle, which no read error shows. [DEFAULT_IDLE_REBUILD_MS] suits an
 *   always-listening consumer.
 * - [silence]: a run of exact-zero samples as long as [Silence.windowMs] ends capture as silenced (see [Silence]).
 *
 * A rebuild is `stop` → `release` → `open` of the device; one that fails to reopen ends capture as dropped.
 */
data class CapturePolicy(
    val rebuildAfterReadFailures: Int? = null,
    val idleRebuildMs: Long? = null,
    val silence: Silence? = null,
) {
    /**
     * The silence window: [windowMs] of audio made only of exact-zero samples ends capture as silenced, with no
     * rebuild, since a denied app-op's silence survives a reopen. Real mics never produce exact zeros for that long,
     * even in a quiet room. With [rebuildFirst], a full window first rebuilds the device, once per silence: Portal+
     * gen1 silences a client's open input when its app leaves the foreground, and a reopen is live again. Only a
     * window that follows that rebuild with no sample heard in between then ends capture.
     */
    data class Silence(val windowMs: Long, val rebuildFirst: Boolean = false)

    companion object {
        /**
         * The idle rebuild window an always-listening consumer uses: ~20× a frame, so healthy continuous capture never
         * idles this long, only a stolen or half-dead mic does.
         */
        const val DEFAULT_IDLE_REBUILD_MS = 2_000L
    }
}
