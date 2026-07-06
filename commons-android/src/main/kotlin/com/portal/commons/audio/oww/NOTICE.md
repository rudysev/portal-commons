# Third-party attribution — openWakeWord inference (Apache-2.0)

The classes in this package (`MelSpectrogram`, `EmbeddingModel`, `WakeWordModelRunner`,
`AudioFeatures`) are adapted from:

- **openWakeWord** — https://github.com/dscripka/openWakeWord (Apache-2.0). The streaming
  melspectrogram → embedding → wake-model pipeline and the `mel/10 + 2` transform, buffer sizes
  (1280-sample step, 76-frame window, stride 8, 16-embedding score window) originate here.
- **openwakeword-android-kt** — https://github.com/Re-MENTIA/openwakeword-android-kt (Apache-2.0).
  The Kotlin/ONNX-Runtime port these classes are adapted from.

Local changes: ONNX `OrtSession`s are created once and reused (the reference recreated them per
call); feature extraction is split from wake scoring so one `AudioFeatures` feeds multiple wake
models; asset paths are parameterised (models live under `assets/oww/`).

The bundled models (`melspectrogram.onnx`, `embedding_model.onnx`, `hey_jarvis_v0.1.onnx`, and, in
portal-wake, `alexa_v0.1.onnx`) are the pretrained openWakeWord models (Apache-2.0 / CC-BY per the
openWakeWord model licenses).
