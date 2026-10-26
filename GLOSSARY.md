# portal-commons

Shared mic capture and on-device wake-word detection for the Portal wake/voice apps.

## Language

### Wake detection

**Decoder**:
Vosk's streaming speech recognizer, built for one grammar, that turns audio frames into partial and final decodes.
_Avoid_: recognizer (overloaded with the Vosk class and the old wrapper)

**Lattice**:
The decoder's in-memory hypothesis state for the current utterance; it grows until a natural endpoint or a reset bounds it.
_Avoid_: current utterance buffer

**Idle reset**:
A backstop reset of the decoder that bounds the lattice when no natural endpoint has done so for too long.
_Avoid_: periodic reset, lattice flush

**Handoff cooldown**:
The single window after any wake fires during which the engine feeds no frames to any detector and drops every further wake, whatever its wake id, covering the gap until capture pauses for the handoff.
_Avoid_: fire cooldown, de-dupe window

**Pre-ready buffer**:
The most recent frames captured while the model is still loading, held so they can be replayed once it is ready.
_Avoid_: flush buffer (replaying it is a "replay", not a "flush")

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
