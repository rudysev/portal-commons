package com.portal.commons.audio

/**
 * Routes [WakeDetector.Events] to [WakeMicConfig] consumer hooks and [log] lines. Owns the per-wake-id
 * [FireCooldown] and exposes it as [WakeHandoffCooldown] for detectors only.
 */
internal class WakeMicEventHandler(
    handoffCooldownMs: Long,
    private val wakeConsumer: (WakeEvent) -> Unit,
    private val onDetectorReady: (String) -> Unit,
    private val onDetectorUnavailable: (String) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit,
    private val postToMain: (Runnable) -> Unit = WakeCallbackThreads.mainThreadPoster(),
    /** When true, scores still flow via detector scoreLogger but [onWake] is never delivered. */
    private val suppressWake: Boolean = false,
) : WakeDetector.Events, WakeHandoffCooldown {

    private val handoffCooldown = FireCooldown(handoffCooldownMs)

    fun reset() = handoffCooldown.reset()

    override fun onReady(detectorId: String) {
        log("wake detector ready ($detectorId)")
        postToMain { onDetectorReady(detectorId) }
    }

    override fun onUnavailable(detectorId: String) {
        log("wake unavailable ($detectorId) — detector idle")
        postToMain { onDetectorUnavailable(detectorId) }
    }

    override fun onWake(event: WakeEvent) {
        if (suppressWake) return
        if (!handoffCooldown.tryFire(event.wakeId, clock())) return
        log("wake detected (${event.detectorId}) → ${event.wakeId} [${event.transcript}]")
        wakeConsumer(event)
    }

    override fun onDiagnostic(detectorId: String, message: String) = log("($detectorId) $message")

    override fun isCoolingDown(wakeId: String): Boolean = handoffCooldown.isCoolingDown(wakeId, clock())

    override fun isAnyCoolingDown(): Boolean = handoffCooldown.isAnyCoolingDown(clock())
}
