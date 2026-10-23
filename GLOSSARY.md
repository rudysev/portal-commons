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

**Capture loss**:
Capture is not running while the consumer wants it running (between a `start()` and the next `pause()`/`close()`) for a reason the consumer did not ask for. Reported at most once per `start()`, with one of two causes: **not granted** (the mic could not be opened) or **dropped** (capture was running and died, including a failed rebuild).
_Avoid_: capture stopped (a requested pause also stops capture), mic error
