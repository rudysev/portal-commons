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
        // Always log — including Vosk shadows that lose the FireCooldown race to oWW — so parallel
        // detectors remain visible in debug.txt for benchmarking.
        log("wake detected (${event.detectorId}) → ${event.wakeId} [${event.transcript}]")
        if (!handoffCooldown.tryFire(event.wakeId, clock())) return
        wakeConsumer(event)
    }

    override fun onDiagnostic(detectorId: String, message: String) = log("($detectorId) $message")

    override fun isCoolingDown(wakeId: String): Boolean = handoffCooldown.isCoolingDown(wakeId, clock())

    override fun isAnyCoolingDown(): Boolean = handoffCooldown.isAnyCoolingDown(clock())
}
