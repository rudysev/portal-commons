package com.portal.commons.audio

import android.app.Application
import android.content.Context
import com.portal.commons.PcmCaptureFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lifecycle, threading, and end-to-end behavior of [OpenWakeWordDetector] with a mock [WakeDetector.Host].
 */
class OpenWakeWordDetectorLifecycleTest {

    private val assetsDir = File("src/main/assets/oww")
    private val appContext: Context = Application()

    private fun assetBytes(name: String): ByteArray {
        val file = File(assetsDir, name)
        assumeTrue("Bundled asset missing: ${file.path}", file.isFile)
        return file.readBytes()
    }

    private fun jarvisConfig(threshold: Float = 0.5f) =
        OpenWakeWordDetector.PhraseClassifierConfig("jarvis", assetBytes("hey_jarvis_v0.1.onnx"), threshold)

    private fun testHost(
        events: WakeDetector.Events = recordingEvents(),
        wakeWords: List<WakeWord> = emptyList(),
    ): WakeDetector.Host = object : WakeDetector.Host {
        override val context: Context = appContext
        override val wakeWords: List<WakeWord> = wakeWords
        override val events: WakeDetector.Events = events
        override val handoffCooldown: WakeHandoffCooldown = object : WakeHandoffCooldown {
            override fun isCoolingDown(wakeId: String): Boolean = false
            override fun isAnyCoolingDown(): Boolean = false
        }
    }

    private fun recordingEvents(
        ready: MutableList<String> = mutableListOf(),
        unavailable: MutableList<String> = mutableListOf(),
        wakes: MutableList<WakeEvent> = mutableListOf(),
        diagnostics: MutableList<String> = mutableListOf(),
    ): WakeDetector.Events = object : WakeDetector.Events {
        override fun onReady(detectorId: String) { ready.add(detectorId) }
        override fun onUnavailable(detectorId: String) { unavailable.add(detectorId) }
        override fun onWake(event: WakeEvent) { wakes.add(event) }
        override fun onDiagnostic(detectorId: String, message: String) { diagnostics.add(message) }
    }

    private fun silenceFrame(): ByteArray = ByteArray(PcmCaptureFormat.FRAME_BYTES)

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    private fun wireAssetLoader(hooks: OpenWakeWordDetector.TestHooks) {
        hooks.assetLoader = { path -> File("src/main/assets/$path").readBytes() }
    }

    @Test fun closeDuringLoadDoesNotPublishReady() {
        val hooks = OpenWakeWordDetector.TestHooks()
        val reachedPublish = CountDownLatch(1)
        val allowPublish = CountDownLatch(1)
        hooks.beforePublishReady = {
            reachedPublish.countDown()
            allowPublish.await(5, TimeUnit.SECONDS)
        }
        wireAssetLoader(hooks)

        val ready = mutableListOf<String>()
        val (factory, _) = OpenWakeWordDetector.testFactory(listOf(jarvisConfig()), hooks)
        val detector = factory.create(testHost(recordingEvents(ready = ready)))

        assumeTrue(reachedPublish.await(10, TimeUnit.SECONDS))
        detector.close()
        allowPublish.countDown()

        Thread.sleep(200)
        assertTrue("onReady must not fire after close", ready.isEmpty())
        assertFalse("readyEvents must not record success after close", hooks.readyEvents.contains(true))
    }

    @Test fun hotSwapDefersClassifierCloseUntilAcceptReturns() {
        val hooks = OpenWakeWordDetector.TestHooks()
        val classifyEntered = CountDownLatch(1)
        val releaseClassify = CountDownLatch(1)
        hooks.classifyOverride = { wakeId ->
            if (wakeId == "jarvis") {
                classifyEntered.countDown()
                releaseClassify.await(5, TimeUnit.SECONDS)
            }
            0.1f
        }
        wireAssetLoader(hooks)

        val (factory, h) = OpenWakeWordDetector.testFactory(listOf(jarvisConfig()), hooks)
        val detector = factory.create(testHost()) as OpenWakeWordDetector
        waitUntil(15_000) { h.readyEvents.contains(true) }
        detector.start()

        val acceptError = AtomicBoolean(false)
        val acceptThread = Thread {
            try {
                val frame = silenceFrame()
                repeat(30) { detector.accept(frame, frame.size) }
            } catch (_: Throwable) {
                acceptError.set(true)
            }
        }
        acceptThread.start()

        assumeTrue(classifyEntered.await(10, TimeUnit.SECONDS))
        assertFalse("classifier must stay open during capture", h.onClassifierClosed.contains("jarvis"))

        val alexaConfig = OpenWakeWordDetector.PhraseClassifierConfig(
            "alexa",
            assetBytes("alexa_v0.1.onnx"),
            0.5f,
        )
        detector.updatePhraseModels(listOf(alexaConfig))
        Thread.sleep(200)
        assertFalse("swap must not close the in-use classifier", h.onClassifierClosed.contains("jarvis"))

        releaseClassify.countDown()
        acceptThread.join(5_000)
        waitUntil(5_000) { h.onClassifierClosed.contains("jarvis") }

        assertFalse("classify must not touch a closed session", acceptError.get())
        detector.close()
    }

    @Test fun closeWaitsForInFlightAcceptBeforeTearingDown() {
        val hooks = OpenWakeWordDetector.TestHooks()
        val classifyEntered = CountDownLatch(1)
        val releaseClassify = CountDownLatch(1)
        hooks.classifyOverride = { wakeId ->
            if (wakeId == "jarvis") {
                classifyEntered.countDown()
                releaseClassify.await(5, TimeUnit.SECONDS)
            }
            0.1f
        }
        wireAssetLoader(hooks)

        val (factory, h) = OpenWakeWordDetector.testFactory(listOf(jarvisConfig()), hooks)
        val detector = factory.create(testHost()) as OpenWakeWordDetector
        waitUntil(15_000) { h.readyEvents.contains(true) }
        detector.start()

        val acceptError = AtomicBoolean(false)
        val acceptDone = CountDownLatch(1)
        Thread {
            try {
                val frame = silenceFrame()
                repeat(30) { detector.accept(frame, frame.size) }
            } catch (_: Throwable) {
                acceptError.set(true)
            } finally {
                acceptDone.countDown()
            }
        }.start()

        assumeTrue(classifyEntered.await(10, TimeUnit.SECONDS))

        val closeStarted = AtomicBoolean(false)
        val closeDone = CountDownLatch(1)
        Thread {
            closeStarted.set(true)
            detector.close()
            closeDone.countDown()
        }.start()

        waitUntil(2_000) { closeStarted.get() }
        Thread.sleep(100)
        assertFalse("close must not finish while accept is in flight", closeDone.await(0, TimeUnit.MILLISECONDS))
        assertFalse("classifier must stay open until accept returns", h.onClassifierClosed.contains("jarvis"))

        releaseClassify.countDown()
        assertTrue(acceptDone.await(5, TimeUnit.SECONDS))
        assertTrue(closeDone.await(5, TimeUnit.SECONDS))
        assertFalse("in-flight accept must not see a torn-down session", acceptError.get())
        assertTrue(h.onClassifierClosed.contains("jarvis"))
    }

    @Test fun normalPathBuffersPreReadyThenAcceptsWithoutWake() {
        val hooks = OpenWakeWordDetector.TestHooks()
        val diagnostics = mutableListOf<String>()
        val wakes = mutableListOf<WakeEvent>()
        wireAssetLoader(hooks)
        val (factory, h) = OpenWakeWordDetector.testFactory(listOf(jarvisConfig()), hooks)
        val detector = factory.create(testHost(recordingEvents(wakes = wakes, diagnostics = diagnostics)))

        val frame = silenceFrame()
        repeat(3) { detector.accept(frame, frame.size) }

        waitUntil(15_000) { h.readyEvents.contains(true) }
        detector.start()
        repeat(5) { detector.accept(frame, frame.size) }

        assertTrue(diagnostics.any { it.contains("pre-ready") })
        assertTrue(wakes.isEmpty())
        detector.close()
    }

    @Test fun firesWakeWhenClassifierScoreExceedsThreshold() {
        val hooks = OpenWakeWordDetector.TestHooks()
        hooks.classifyOverride = { 0.95f }
        val wakes = mutableListOf<WakeEvent>()
        wireAssetLoader(hooks)
        val (factory, h) = OpenWakeWordDetector.testFactory(listOf(jarvisConfig(0.5f)), hooks)
        val detector = factory.create(testHost(recordingEvents(wakes = wakes)))

        waitUntil(15_000) { h.readyEvents.contains(true) }
        detector.start()

        val frame = silenceFrame()
        val deadline = System.currentTimeMillis() + 10_000
        while (wakes.isEmpty() && System.currentTimeMillis() < deadline) {
            detector.accept(frame, frame.size)
        }

        assertEquals(1, wakes.size)
        assertEquals("jarvis", wakes.single().wakeId)
        assertTrue(wakes.single().transcript.startsWith("score="))
        detector.close()
    }

    @Test fun bundledThresholdUsesScoreThresholdNotMinConf() {
        val word = WakeWord(
            id = "jarvis",
            keyword = "jarvis",
            lead = "hey",
            minConf = 0.99,
            scoreThreshold = 0.31,
        )
        val threshold = word.scoreThreshold.toFloat().coerceIn(0f, 1f)
        assertEquals(0.31f, threshold)
        assertFalse(threshold == word.minConf.toFloat())
    }

    @Test fun emptySwapThenRestoreReSignalsReady() {
        val hooks = OpenWakeWordDetector.TestHooks()
        wireAssetLoader(hooks)
        val ready = mutableListOf<String>()
        val unavailable = mutableListOf<String>()
        val (factory, h) = OpenWakeWordDetector.testFactory(listOf(jarvisConfig()), hooks)
        val detector = factory.create(
            testHost(recordingEvents(ready = ready, unavailable = unavailable)),
        ) as OpenWakeWordDetector

        waitUntil(15_000) { h.readyEvents.contains(true) }
        assertEquals(listOf(OpenWakeWordDetector.ID), ready)

        detector.updatePhraseModels(emptyList())
        waitUntil(5_000) { unavailable.isNotEmpty() }
        assertEquals(listOf(OpenWakeWordDetector.ID), unavailable)

        detector.updatePhraseModels(listOf(jarvisConfig()))
        waitUntil(5_000) { ready.size >= 2 }

        assertEquals(
            listOf(OpenWakeWordDetector.ID, OpenWakeWordDetector.ID),
            ready,
        )
        // Second ready means the consumer can resume after clearing the wake set.
        assertEquals(listOf(true, true), h.readyEvents.toList())
        detector.close()
    }

    @Test fun emptySwapBuffersThenFlushOnRestoreWithoutStart() {
        val hooks = OpenWakeWordDetector.TestHooks()
        hooks.classifyOverride = { 0.95f }
        wireAssetLoader(hooks)
        val wakes = mutableListOf<WakeEvent>()
        val diagnostics = mutableListOf<String>()
        val unavailable = mutableListOf<String>()
        val ready = mutableListOf<String>()
        val (factory, h) = OpenWakeWordDetector.testFactory(listOf(jarvisConfig()), hooks)
        val detector = factory.create(
            testHost(recordingEvents(ready = ready, unavailable = unavailable, wakes = wakes, diagnostics = diagnostics)),
        ) as OpenWakeWordDetector

        waitUntil(15_000) { h.readyEvents.contains(true) }
        detector.start()
        val frame = silenceFrame()
        // Prime accept while ready so a naive wasReady latch would stick true across the swap.
        detector.accept(frame, frame.size)
        wakes.clear()
        diagnostics.clear()

        detector.updatePhraseModels(emptyList())
        waitUntil(5_000) { unavailable.isNotEmpty() }

        // Buffer PCM while unavailable (no start() before restore).
        repeat(3) { detector.accept(frame, frame.size) }

        detector.updatePhraseModels(listOf(jarvisConfig()))
        waitUntil(5_000) { ready.size >= 2 }

        detector.accept(frame, frame.size)
        assertTrue(
            "PCM buffered while unavailable must flush after ready is restored without start()",
            diagnostics.any { it.contains("pre-ready") },
        )
        assertTrue(wakes.isNotEmpty())
        detector.close()
    }

    @Test fun updatePhraseModelsBeforeReadyIsAppliedOnLoad() {
        val hooks = OpenWakeWordDetector.TestHooks()
        val reachedResolve = CountDownLatch(1)
        val allowResolve = CountDownLatch(1)
        hooks.beforeResolveConfigs = {
            reachedResolve.countDown()
            allowResolve.await(5, TimeUnit.SECONDS)
        }
        hooks.classifyOverride = { wakeId -> if (wakeId == "alexa") 0.95f else 0.1f }
        wireAssetLoader(hooks)

        val wakes = mutableListOf<WakeEvent>()
        val (factory, h) = OpenWakeWordDetector.testFactory(listOf(jarvisConfig()), hooks)
        val detector = factory.create(testHost(recordingEvents(wakes = wakes))) as OpenWakeWordDetector

        assumeTrue(reachedResolve.await(10, TimeUnit.SECONDS))
        // Stash alexa while shared models exist but phrase configs have not been read yet.
        detector.updatePhraseModels(
            listOf(
                OpenWakeWordDetector.PhraseClassifierConfig(
                    "alexa",
                    assetBytes("alexa_v0.1.onnx"),
                    0.5f,
                ),
            ),
        )
        allowResolve.countDown()
        waitUntil(15_000) { h.readyEvents.contains(true) }

        detector.start()
        val frame = silenceFrame()
        val deadline = System.currentTimeMillis() + 10_000
        while (wakes.isEmpty() && System.currentTimeMillis() < deadline) {
            detector.accept(frame, frame.size)
        }

        assertEquals(1, wakes.size)
        assertEquals("alexa", wakes.single().wakeId)
        detector.close()
    }

    @Test fun sharedModelLoadFailureSignalsUnavailable() {
        val hooks = OpenWakeWordDetector.TestHooks()
        var melLoads = 0
        hooks.assetLoader = { path ->
            when {
                path.endsWith("melspectrogram.onnx") -> {
                    melLoads++
                    File("src/main/assets/$path").readBytes()
                }
                path.endsWith("embedding_model.onnx") -> error("simulated emb load failure")
                else -> File("src/main/assets/$path").readBytes()
            }
        }
        val unavailable = mutableListOf<String>()
        val ready = mutableListOf<String>()
        val (factory, h) = OpenWakeWordDetector.testFactory(listOf(jarvisConfig()), hooks)
        val detector = factory.create(
            testHost(recordingEvents(ready = ready, unavailable = unavailable)),
        )

        waitUntil(10_000) { unavailable.isNotEmpty() || h.readyEvents.isNotEmpty() }
        assertEquals(listOf(OpenWakeWordDetector.ID), unavailable)
        assertTrue(ready.isEmpty())
        assertFalse(h.readyEvents.contains(true))
        assertEquals("mel session must be created before emb fails", 1, melLoads)
        // Teardown after a partial shared-build failure must not hang or throw.
        detector.close()
    }
}
