package com.portal.commons.audio

/**
 * openWakeWord **detector/accuracy** tuning knobs, kept together and deliberately separate from the
 * Android/microphone-robustness constants (which live with the mic layer — `WakeMicEngine`'s read-failure
 * rebuild, `PcmCaptureSession`'s poll/idle-rebuild, `AudioRecordPcmDevice`'s source). Every value here is
 * justified by on-device measurement, not inherited from any earlier detector.
 */
object OwwTuning {
    /**
     * Fire threshold: the score (0..1) a wake must clear. Recall-first; on the Portal, real-speech
     * false-accepts stay ~0 across the whole threshold range, so this only trades recall vs adversarial
     * soundalikes (see `tools/wakeword-bench`).
     */
    const val DEFAULT_THRESHOLD = 0.3f

    /**
     * Consecutive frames a score must clear the threshold before firing — a debounce against single-frame
     * spikes. Trade-off measured on device: a very short score peak (~1 frame) can be missed by requiring 2.
     */
    const val DEBOUNCE_FRAMES = 2

    /**
     * Post-fire match suppression. Needed: a single "hey jarvis" scores above threshold for ~5–7 consecutive
     * frames (measured), which [DEBOUNCE_FRAMES] would otherwise re-fire on 2–3×; this makes one utterance
     * fire once. 1.5 s covers the measured run with margin for louder/closer speech. (Verified on device:
     * with no cooldown, the pause-on-fire only wins the race by luck.)
     */
    const val COOLDOWN_MS = 1_500L

    /**
     * Ring-buffer cap for frames held while the models load, so speech spoken during init isn't lost. The
     * openWakeWord models load in ~0.8 s on the Portal (measured), so ~2 s of headroom (20 × 100 ms) is ample.
     */
    const val PRE_READY_MAX_FRAMES = 20
}
