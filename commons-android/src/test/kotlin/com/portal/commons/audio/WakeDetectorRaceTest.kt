package com.portal.commons.audio

import com.portal.commons.audio.ScriptedDecoder.Companion.frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Races between the model load (its own thread) and the capture / controlling threads (spec decision 8). The
 * test thread plays the capture thread; [Gate] holds the first decoder build to open the window. The build happens
 * on the capture thread, at the first frame after delivery, so the load thread normally only hands the model over and
 * the gate opens no window. Each test asserts only the end state — which decoder is live, and that everything else was
 * freed exactly once — so it holds however the detector hands the model over.
 */
class WakeDetectorRaceTest {

    private val loader = FakeVoskLoader()
    private val model = FakeVoskModel()
    private val callbacks = RecordingCallbacks()

    private fun detector() = callbacks.detector(loader, listOf(JARVIS))

    /**
     * Deliver the model on a `wake-model-load` thread; [during] runs while that delivery is (or would be) mid-build.
     */
    private fun loadRacing(during: () -> Unit) {
        val gate = Gate()
        model.firstBuildGate = gate
        val load = thread(name = "wake-model-load") { loader.complete(model) }
        gate.awaitEntered(timeoutMs = 500) // false when delivery builds nothing on the load thread
        during()
        gate.release()
        load.join(5_000)
    }

    @Test(timeout = 10_000)
    fun grammarSwapDuringLoadLeavesOneLiveDecoderOnTheNewWords() {
        val d = detector()
        loadRacing { d.updateWakeWords(listOf(ALEXA)) }
        d.accept(frame(2), FRAME)

        val live = model.decoders.single { it.fed.isNotEmpty() }
        assertEquals("""["hey alexa", "alexa", "hey", "[unk]"]""", live.grammar)
        assertTrue("every other decoder freed", (model.decoders - live).all { it.closed })
    }

    @Test(timeout = 10_000)
    fun closeDuringLoadFreesEverythingOnce() {
        val d = detector()
        loadRacing { d.close() }

        assertEquals(1, model.closeCount)
        assertTrue(model.decoders.all { it.closed })
        assertTrue("no ready after close", "ready" !in callbacks.lines)
    }

    @Test fun closeAfterDeliveryBeforeAnyFrameFreesEverythingOnce() {
        val d = detector()
        loader.complete(model)
        d.close()

        assertEquals(1, model.closeCount)
        assertTrue(model.decoders.all { it.closed })
    }

    @Test fun modelDeliveredAfterCloseIsFreed() {
        val d = detector()
        d.close()
        loader.complete(model)

        assertEquals(1, model.closeCount)
        assertTrue(model.decoders.isEmpty())
        assertEquals(emptyList<String>(), callbacks.lines)
    }
}
