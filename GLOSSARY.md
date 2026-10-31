# portal-commons

Shared mic capture and on-device wake-word detection for the Portal wake/voice apps.

## Language

### Wake detection

**Wake model**:
The Vosk acoustic model the **Decoders** are built from: either bundled in the APK or already unpacked on disk.
_Avoid_: model dir (only one of the two sources is a directory the consumer supplies)

**Decoder**:
Vosk's streaming speech recognizer, built for one grammar, that turns audio frames into partial and final decodes.
_Avoid_: recognizer (overloaded with the Vosk class and the old wrapper)

**Wake detector**:
Turns captured frames into wakes once the **wake model** has loaded: it adopts the model when it arrives, holds the **pre-ready buffer** until then and replays it afterwards, and feeds every frame outside the **handoff cooldown** to the **wake decoder**.
_Avoid_: detector engine, recognizer

**Wake decoder**:
One **Decoder** at a time for the current wake words, and the judgement of each decode it finalizes as a wake, a near-miss or nothing. It replaces its Decoder when the wake words change, keeping the old one if the new one can't be built, and bounds the **lattice** with the **idle reset**.
_Avoid_: decoder session, recognizer

**Lattice**:
The decoder's in-memory hypothesis state for the current utterance; it grows until a natural endpoint or a reset bounds it.
_Avoid_: current utterance buffer

**Idle reset**:
A backstop reset of the decoder that bounds the lattice when no natural endpoint has done so for too long.
_Avoid_: periodic reset, lattice flush

**Handoff cooldown**:
The single window after any wake fires during which the **wake detector** ignores every frame, so no wake of any wake id arises, covering the gap until capture pauses for the handoff.
_Avoid_: fire cooldown, de-dupe window

**Pre-ready buffer**:
The most recent frames captured while the model is still loading, held so they can be replayed once it is ready.
_Avoid_: flush buffer (replaying it is a "replay", not a "flush")

**Hybrid wake detector**:
A second wake detector for one wake id ("jarvis"), run by the engine beside the **wake detector** on the same frames. Its **gate** opens a **candidate**; the candidate wakes when the keyword spotter heard the wake keyword and no rival, or when the **window check** accepts it and the spotter heard no rival. Chosen at runtime against Vosk alone (the wake mode).
_Avoid_: cascade, chain, two-stage detector

**Gate**:
The hybrid's always-on first check (openWakeWord), scoring every 80 ms; a score at the threshold opens a **candidate**.
_Avoid_: stage 1

**Candidate**:
One utterance the **gate** opened: the ~2 s window ending 400 ms after it opened, which the hybrid's two checks decode.
_Avoid_: trigger (a candidate that wakes is a wake)

**Window check**:
The production Vosk grammar and gates run on a **candidate**'s window alone, on a fresh **Decoder** of the shared **wake model**.
_Avoid_: Vosk re-decode

**Vosk shadow**:
The **wake detector** kept decoding every frame while the hybrid decides, its wakes logged but not delivered, so each utterance has both verdicts and their disagreements are recorded.
_Avoid_: parallel Vosk, A/B

### Capture

**Capture request**:
The span during which the consumer wants capture running: from a `start()` that begins it to the next `pause()`, `close()` or **capture loss**. A `start()` while a request is open joins it rather than beginning a new one, unless it brings a different listener, which ends the open request and begins a new one.
_Avoid_: generation, run (one capture thread, which may rebuild its device several times within a request)

**PcmCapture**:
The module every app captures the mic through, one **capture request** at a time: each request's callbacks reach only its own listener and never run at once, it raises at most one **capture loss**, and closing it is terminal and bounded.
_Avoid_: capture session (the single-use capture underneath, internal to it), mic capture

**Capture loss**:
Capture is not running, or is running but delivering no audio, during a **capture request** for a reason the consumer did not ask for. It ends the request. Reported at most once per capture request, with one of three causes: **not granted** (the mic could not be opened), **dropped** (capture was running and died, including a failed rebuild) or **silenced** (capture is running but delivers only digital silence, whatever the reason).
_Avoid_: capture stopped (a requested pause also stops capture), mic error
