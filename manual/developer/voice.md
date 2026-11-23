---
label: Voice in
icon: unmute
order: 715
covers:
  - joi-node/src/main/java/com/github/enerccio/joi/node/voice
  - joi-node/src/main/java/com/github/enerccio/joi/node/fx/VoicePanel.java
  - joi-node/src/test/java/com/github/enerccio/joi/node/voice
  - joi-sherpa/pom.xml
verified: 0834edb
---

# Voice in

The node hears the user and sends words. The design is in `DESIGN.md` → Voice: wake word and speech to text run on the node, the node says text (`say`), the
server never gets audio. What is built is **push-to-talk on the JavaFX node**; the wake word, barge-in by voice, the clip saved with the message, the choice of a
language and the terminal node are not. The iOS app has its own push-to-talk with Apple's on-device recognizer: see [The iOS app](ios-app.md).

## The pieces

All in `com.github.enerccio.joi.node.voice`; only `SileroGate` and `SherpaTranscriber` touch sherpa-onnx, so the rest runs and is tested without the natives.

| Class | What it does |
|---|---|
| `AudioInput` | a source of frames (512 samples, 16 kHz mono, floats): `read()` waits for the next, `close()` ends it and `read()` returns null |
| `Microphone` | the default microphone through Java Sound, 16 bit little endian; opened for each recording and closed at its end |
| `SpeechGate` | tells speech from silence for one frame, with state between frames (`SileroGate`: Silero through sherpa-onnx's `Vad.compute`) |
| `UtteranceDetector` | the end of what the user says, from the frames and the gate: a pause, `finish()`, no speech for too long, too long; cuts the silence around |
| `Transcriber` | speech to text for one utterance (`SherpaTranscriber`: a Moonshine model, `OfflineRecognizer`, whole utterance, CPU) |
| `VoiceInput` | one recording at a time: the thread, the models loaded once and kept, the listener's calls |
| `SpeechModel`, `VoiceModels` | what can be downloaded, where it lives (`<home>/voice`), the download |

## A recording

`VoiceInput.start(listener)` sets the state to `LISTENING` and starts a thread `joi-voice`. It loads the models if they are not (a failure is `failed`), resets
the gate, opens the microphone and calls `listening()`. Then it reads frames into an `UtteranceDetector` until the detector says it is over, the user calls `finish()`
(the detector gives what was said so far) or `cancel()` (nothing is used). The microphone is closed before the speech is turned into text, so the system's
indicator goes off. `transcribing()`, then `heard(text)`, `nothing(why)` or `failed(message)`. The state is `IDLE` again before the last call, so the listener may
start another recording from it. The listener is called on the voice thread; the app moves to the FX thread.

`UtteranceDetector` (one is for one utterance): a frame is speech from probability 0.5. The first speech frame starts the utterance; a pause of the length given ends
it (`PAUSE`); speech of fewer than 4 frames (about 130 ms) is a noise, forgotten when its pause comes, and the wait goes on; with no speech for `waitMs` (8 s) it ends
as `NO_SPEECH`, and an utterance is cut at `maxMs` (60 s, `TOO_LONG`). The samples it returns start up to 8 frames before the first speech and end 6 frames after
the last.

## The app

`JoiNodeApp.wireVoice` (called where her chat is wired) makes the `VoiceInput`, the Talk handler, the key (`Shortcut+Shift+Space`, a scene accelerator) and the Voice
section of the controls (`fx/VoicePanel`: status, the download with its progress, the pause). The button is in `ChatPane` (`talking(state)` shows what the voice input
is doing, `heard(text)` says the words, with the images that wait: `sendWithImages`, shared with a typed message). Pressing Talk first runs the same stop as the Stop
button (her turn and her voice end), then starts listening. Esc is a scene accelerator only while listening.

The pause is the node's setting `voicePauseMs` (`node.json`, null for 1200), read at the start of each recording.

## The models

`VoiceModels` keeps `voice/silero_vad.onnx` and `voice/<model id>/` in the node's home. `install` downloads what is missing, with the progress, from the URLs in
`SpeechModel` and `VoiceModels.SILERO_VAD`. A download goes to a temp file, is checked against its SHA-256 and deleted when it differs. The speech model is a
`.tar.bz2` (the project does not publish the files apart): commons-compress reads it and only the files the model lists are taken out, **by their names**; the entry's
folders are never used as a path. They are written to a `.part` folder that replaces the model's own once it is whole. Nothing is downloaded unless the user presses
the button.

To add a model (another language, a bigger one): a `SpeechModel` with the URL, the checksum, the size and the files, added to `SpeechModel.ALL`, and a
`Transcriber` that reads it if it is not a Moonshine one. Take the checksum from the release's own digest (`gh api repos/k2-fsa/sherpa-onnx/releases/tags/asr-models`).

## sherpa-onnx in the build

The module `joi-sherpa` packs sherpa-onnx's Java API and the natives of the machine into one jar; see [Building](building.md). The node depends on it. The native library
is loaded from the jar by the project's `LibraryLoader` when the first class is used; macOS may print a warning about native access, and sherpa-onnx prints its
configuration to the standard output when a model is created.

## Tests

`mvn test -pl joi-node` runs `UtteranceDetectorTest` (the end of an utterance with a fake gate), `VoiceInputTest` (the thread, the states, finish, cancel and the failures,
with a fake microphone, gate and transcriber) and `VoiceModelsTest` (the download against an HTTP server of the test: the checksum, the files taken out of an archive,
names that try to leave the folder, nothing downloaded twice). None needs a model or a microphone.

`SherpaVoiceTest` runs the real models on a recording and is skipped without them. With the models in a node's home and a 16 bit mono WAV of speech:

```bash
JOI_VOICE_TEST_HOME=~/.joi-dev-client JOI_VOICE_TEST_WAV=speech.wav mvn test -pl joi-node -Dtest=SherpaVoiceTest
```

It checks that the detector finds the speech in the recording and ends it at the pause, that the recognizer reads it, that the whole `VoiceInput` does it from a fake
microphone, and that silence is not speech. The microphone itself and the buttons are tried by hand: nothing in the tests opens the microphone.
