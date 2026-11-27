---
label: The iOS app
icon: device-mobile
order: 765
covers:
  - joi-ios
verified: 197abef
---

# The iOS app

The iOS app is a native Swift node (`joi-ios/`), planned in `DESIGN.md` → *The iOS app*. It is a client of the public protocol like any other node: the
server does not change for it. It is three Swift packages and a thin Xcode project around them; the packages are plain SwiftPM, run by `swift test` on the
Mac (Swift 6, so Xcode 16 or later). What the app does so far (stage 1): it asks for the server, pairs, shows her history, shows her body in the upper half of the screen, and chats with
her answer written as it streams and her voice played.

## `JoiProtocol`

The types of the protocol, **generated from the JSON Schemas** and checked in: see [The protocol](protocol.md#the-swift-package). By hand in it: `JSONValue`
and the coders (`JoiJSON.makeDecoder()` / `makeEncoder()`, ISO 8601 dates with or without fractions).

## `JoiClient`

The client of a server, on `JoiProtocol`. It has no UI and uses only Foundation (and the Keychain for the credentials), and it does not know
`URLSession` where the logic is: the network is behind `HTTPTransport` and `FrameChannel`, so the tests have a fake server and no network.

- **`JoiClient`** (an actor): the HTTP API (`info`, `me`, `jois`, `joi`, `history`, `body`, `chat`, `uploadImage`, `tasks`, `approveTask`, `denyTask`) and the pairing, the OAuth
  device grant (`startDeviceGrant`, `pollDeviceGrant`, `waitForApproval`). It follows the reference node's `ServerClient`: an access token that is about to
  end is made again from the refresh token before a call, and after a 401 (once, and not when another call did it meanwhile); **one refresh at a time**, since a
  second one would use the refresh token the first one replaced, which ends the pairing; a refresh the server refuses clears the credentials, so the user pairs
  again. A server on a path (`https://example.com/joi`) is allowed. Errors are `ServerError` (the API's `code`, OAuth's `error`, else `http_<status>`).
- **`uploadImage(_:file:)`** (1.11): the image as the body of `POST .../images`, streamed from the file (`HTTPTransport.upload`, `URLSession.upload(for:fromFile:)`; a fake gets the file as
  `httpBody`), with the refresh after a 401 like every call; the answer is an `ImageUpload`. `NodeConnection.say` has `images`.
- **`CredentialStore`**: `KeychainCredentialStore` on the phone (`AfterFirstUnlockThisDeviceOnly`, so a node in the background can sign in), `MemoryCredentialStore`
  in tests.
- **`NodeConnection`** (an actor): the node WebSocket as `NodeSocket` has it. `connect()` says `hello`, waits for `welcome` (15 s), sends the manifest, and starts
  the reading and the heartbeat (a ping every half of the server's `heartbeatSeconds`; a server silent for twice that is left with the code 4008). Her frames come
  as `events` (`.frame(Incoming)` with the `NodeFrameData` of the generated package, `.disconnected`, `.retrying`, `.connecting`, `.reconnected`, `.closed`); `ping`
  and `tool_call` are answered inside (the `toolHandler`; without one a call fails with `unavailable`); a type of a newer version is ignored. A lost
  connection is made again after 1 s, 2 s, 4 s... up to 30 s with `hello.resumed` and `resume` (what it knows of the lost one); the close codes 4001-4004 and 4009
  (unauthorized, version, replaced, not found, revoked) end it for good. Nothing is kept for later: `say` while disconnected throws `notConnected`.
- **`FrameChannel`**: the text frames of a WebSocket; `URLSessionWebSocketChannel` is the real one.
- **`download(_:into:)`** of `JoiClient`: a file the server offers (`Download`: her `.joim`, her `.joia`) kept in the cache as `<sha256>.<ext>`, so the one that is there is not
  fetched again. The answer goes to a file (`HTTPTransport.download`, `URLSession.download`; never memory, a model is hundreds of megabytes), is checked against the hash,
  and is moved into place only then. The server names a file by the address it was reached at (`http://127.0.0.1:8080/...`): only its path and query are used, on the
  address the app knows. A hash that is not 64 hex digits is refused (it names a file of the cache).
- **`Body/`**: the containers. `CBOR` is a small reader (RFC 8949) for the metadata, into `JSONValue`. `SectionStream` reads the sections of a `.joim` / `.joia` as
  a stream (kind, length, SHA-256, data); a section's data is copied to a file in blocks while it is hashed, and a section that does not match leaves no file.
  `JoimFile.read` gives the model's VRM, `JoiaFile.read` the animations (`JoiaAnimation`, `JoiaStep`: loop, speed, in place, reverse, rotation, blend) and the clips,
  one file each. The format is `joim.md` / `joia.md` in joi-protocol; a newer major version is refused, a kind it does not know is skipped.

## `JoiApp`

The app's own code, so that it builds and is tested on the Mac: SwiftUI and the models, on `JoiClient`.

- **`AppModel`** (`@Observable`): the phase of the app (`setup`, `working`, `pairing`, `ready`). `connect()` takes the address (`joi.example.com` is `https://...`),
  asks the server what it takes, and pairs by the device grant unless it takes no auth (a development server); the code, its link and a QR code are shown
  (`PairingView`) while `waitForApproval` polls. The address is kept only after a sign-in, and the app goes straight on to the chat the next time it starts
  (`launch()`); the credentials are in the Keychain, with the address of the server that made them (`joi.credentialsServer`): an app pointed at another server drops them before it calls it, so no server gets another one's token. The node's id is a UUID in `UserDefaults`; its name is `phone`.
- **`ChatModel`**: her history, the user's words, her answer as the `text` frames come (a message per turn, found by the `ref` of the frame; a turn the server
  started, `turn`, makes its own), `reply` setting the whole text, `audio` handed to the `SpeechPlaying` (`SpeechPlayer`: `AVAudioPlayer` one piece after the
  other), the status of the connection (`connecting`, `connected`, `reconnecting`, `offline`) and a note when a turn is cut off by a lost connection. It needs
  `NodeLink` and `HistorySource` (which `NodeConnection` and `JoiClient` are), so its tests have neither server nor socket.
- **Images.** `ChatModel.send(_:images:)` takes files: each goes through `ImagePreparer` (ImageIO: a thumbnail at 1568 pixels with the orientation applied, written as JPEG; nothing is held whole in
  memory), is uploaded (`HistorySource.uploadImage`) and its id goes in `say`; it returns whether the message was sent, and the view keeps the words and images in the field when it was not. It refuses
  without an upload when `canSeeImages` is false (`JoiInfo.images`, read by `AppModel` and again on the server's `joi` event) or when there are more than four. `ChatMessage.imageCount` is what the
  user sent (the history's `ChatEntry.images`; a message of only images comes with no text and is kept): the bubble says "📷 2 images", the pictures themselves are not shown on the phone yet. The views are in `ImageInput.swift`: `ImageAttachButton` (a menu:
  *Take a photo* with `CameraPicker`, a `UIImagePickerController` (iOS only, and only when there is a camera), or *Choose from the library* with `PhotosPicker`, which hands the photo over as a file:
  `PickedImage`, a `FileRepresentation`) and `PendingImages` (thumbnails with a button to remove). `project.yml` has `NSCameraUsageDescription`; the library needs no string. The camera and the picker are
  only built for iOS, so `swift test` on the Mac covers `ImagePreparer` and `ChatModel`, and `mvn -Pswift,ios verify` the views.
- **Push-to-talk** (`VoiceInput.swift`, `AppleDictation.swift`). The microphone button in the chat's input row: tap to talk, tap again to send what was said at once, the cross in the strip over the field
  cancels (nothing is sent). `VoiceInput` (`@Observable`, a state of `idle`, `starting`, `listening`, `finishing`) is the logic and has no audio in it: it is given a `Dictating` (`begin()`
  returns an `AsyncStream<DictationEvent>` of `partial` / `final` / `failed`, `endAudio()`, `cancel()`) and the closures `send` and `beforeListening`; `ChatModel` makes it when it is given a
  `dictation` (`AppModel.makeDictation()`), sending through `send(_:)` and stopping her voice first (`stopSpeaking()`: talking over her is barge-in, as the Stop of the other nodes). The talk ends
  at a pause of 1.2 s after words (a timer started again by each partial result: Apple's recognizer has no end of speech of its own), when the user taps, after 60 s at most, and gives up after 8 s with
  no words (the note *I did not hear anything.*); a recognizer that does not finish within 5 s of `endAudio` does not hold back what was heard. What was heard goes as `say` text, never audio; words that
  could not be sent (no connection) are `leftover`, which the text field takes. Every timer and stream belongs to one talk (`session`), so what an earlier talk does late is dropped; a cancel while the
  microphone opens closes it when it has opened. The wake word and the intent are below. `AppleDictation` is the real `Dictating`: it asks for the permissions (`SFSpeechRecognizer.requestAuthorization`, `AVAudioApplication.requestRecordPermission`; a
  refusal is an error that says where to allow it), takes the phone's language else English (the first the phone can recognize **on the device**: `requiresOnDeviceRecognition`, so no sound goes to Apple;
  a language the phone cannot do by itself is an error, not a quiet fall back), sets the audio session to `playAndRecord` (`SpeechPlayer` sets `playback` again for her next piece; the session is
  left active when the microphone closes, because her voice may be playing in it, and a session deactivated while it plays stops her) and feeds an `AVAudioEngine` tap into an `SFSpeechAudioBufferRecognitionRequest`. The tap and the recognizer's handler are made in `nonisolated` functions:
  both are called from other queues, and a closure made in a main actor method is checked to run on the main actor (a crash). `Relay` keeps the last text, so an error at the end (*no speech
  detected*, 1110) still gives what was heard, or an empty `final`. `project.yml` has `NSMicrophoneUsageDescription` and `NSSpeechRecognitionUsageDescription`, and the hello declares the part
  `audio-in`. `swift test` covers `VoiceInput` (`VoiceInputTests`, with a fake `Dictating`) and its place in `ChatModel`; the recognizer and the microphone need a phone (the Simulator has a
  microphone and no on-device model for some languages). Not built: a language setting, the clip saved with the message.
- **The wake word** (`WakeWord.swift`, the waiting mode of `VoiceInput`, `WakeWordController.swift`). `arm(phrases:hints:)` keeps the microphone open (`State.waiting`) and searches the
  recognizer's text for "hey / hi / ok / okay <her name>" (`WakeWord.remainder(in:phrases:)`: whole words, lowercase and without accents, the first match wins; the name without a greeting is not a wake
  word, the recognizer hears "Joi" too often). A match starts a talk (`wake`: she stops speaking, `.listening`), and the rest of the text is the message: `spoken(_:)` cuts the phrase from every
  later result and from the final one, and a result in which the recognizer changed its mind about the name is skipped. The name alone waits `wakeWait` (5 s) and goes back to waiting with no note. The
  waiting listening is made again by `recycle`: every 40 s (a session of the recognizer is not meant to run for ever), after a `final` with no wake word, after a failure or an end of the stream; a failure
  waits 1 s, 2 s, 4 s... up to 30 s (`failures`; a microphone that cannot be opened, as in the background); a `DictationError` (a permission refused) is not tried again: the wake word is disarmed and the
  note says why. `suspend(true)` closes it while her voice plays (`ChatModel.state(.talking)`), `retryNow()` is called when the app comes in front (`ChatView`'s `scenePhase`), the button during waiting
  takes the talk over (`toggle`), and a talk that ends (sent, cancelled, nothing heard) goes back to waiting. `WakeWordController` holds what the user chose (`UserDefaults`:
  `joi.wakeWord.enabled`, `joi.wakeWord.onlyWhileCharging`; off by default) and arms or disarms the voice: only while the phone charges when so chosen (`UIDevice` battery state, watched);
  `ChatModel.wake` is it, shown by `HandsFreeView` (the menu over her body, *Hands-free*). `AppleDictation` gives the recognizer her name (`contextualStrings`) and opens the audio session with
  `mixWithOthers`; `project.yml` has the background mode `audio`. The matching and the state machine are tested (`WakeWordTests`, `VoiceInputTests` with the fake `Dictating`); what the real recognizer
  and the microphone do over hours, in the background, in a call or with music is **not tried** and needs a phone.
- **The App Intent** (`App/JoiIntents.swift`, in the app's target, since the system reads the intents of the app and not of a package). `TalkIntent` (*Talk to Joi*, `openAppWhenRun`) calls
  `VoiceTrigger.shared.listen()`; `AppModel` attaches the chat (`attach`: `voice.start()`), and a request made before the chat exists (the app was not running, the pairing is not done) waits 15 s for it.
  `JoiShortcuts` makes it an App Shortcut with the phrases "Joi", "Talk to Joi" and "Listen, Joi", so "Hey Siri, Joi" works without the user setting anything up. A Vocal Shortcut (iOS 18), the Action
  Button, Back Tap and the Shortcuts app run the same shortcut; they are set up by the user in the system's settings, and `HandsFreeView` says where. A Control Center or Lock Screen control would be a
  widget extension and is not built.
- **Her mouth.** The page reads it from her voice (see *Her mouth* in [Writing a node](nodes.md)). `SpeechPlayer` numbers the pieces it plays and has `onQueued`, `onStarted` and
  `onStopped`; `AppModel` hands them to `RendererBridge.speech`, `speechStarted` and `speechStopped`, which send the `speech`, `speech-start` and `speech-stop` messages (the
  piece's bytes in Base64; not kept for a page that opens later).
- **Her face.** Her eyes and her mood are messages to the page like the mouth (see *Her face* in [Writing a node](nodes.md)): `ChatModel.state` sets her eyes from her state
  (`AnimationPlayer.setEyes`), the `mood` frame (`MoodState`) sets her mood (`setMood`); `RendererBridge` sends `eyes` and `mood`
  and sends them again to a page that opens later, and puts an animation's `eyes` and `expression` (`JoiaAnimation`) into the `face` of its sequence.
- **Her body.** `AvatarModel` loads it: `GET .../body`, her `.joim` and `.joia` downloaded into the caches folder (`joi-body`, by hash; what she wore before is
  cleaned away), unpacked in `BodyFiles` (a folder `<hash>-joim` with `model.vrm`, a folder `<hash>-joia` with `<index>.vrma`, each with the marker `.unpacked`) off the main
  thread, and shown. The server's `event` named `body` loads it again, `joi` reads her animation roles again (`ChatModel.onServerEvent`, wired in `AppModel`). The page is the
  node's renderer page (`joi-node/src/main/renderer`) in a `WKWebView` (`AvatarView`, iOS only): no server, so the page and her files are read by a `WKURLSchemeHandler` for
  `joi-body://app/...` (the page from the app's `Renderer` folder, `body/model.vrm` and `body/clip/<uuid>.vrma` from the unpacked files, in blocks of 1 MB, and only while
  WebKit has not stopped the task: answering a stopped one raises an exception). The page has a native mode (`main.js`: when `webkit.messageHandlers.joi` exists): the app calls
  `window.joiReceive(message)` with the same messages the node sends as server-sent events (`model`, `play`, `toggles`, `offline`, `stop`), and the page posts `ready` (it asks
  for what it is to show), `loaded` (her model is there; until then the app writes "Drawing her" over it), `ended` / `error` (an animation that does not loop is over) and `log`
  (its console and errors go to `NSLog`: `xcrun simctl spawn booted log show --predicate 'process == "Joi"'`). The part with no WebKit is `RendererBridge`: it makes the
  messages from her animations (a clip of two steps is sent once; one that plays once has no step that loops), keeps the last model, toggles, play and offline messages for a page
  that opens later, remembers the looping animation she was asked for before her body came, and ends an animation that does not loop by itself when the page does not tell within its
  length and 3 s, as the node's `RendererServer` does. The full model is shown, not the lighter one for the Looking Glass; if a phone runs out of memory on a large one, that is the place to change.
- **`AvatarDirector`**: what her body does as her turns come and go, the same logic as the node's `AvatarDirector` (idle, talking, her first waking up while the server writes
  her words, which are held back, gestures she chooses, falling asleep; her words wait for her voice when speech is on, so the chat does not run ahead of what she says). It
  drives an `AvatarOutput` (`ChatModel` is one): `play`, `text`, `reply`, `speak`, `done`, `failed`, `state`. The renderer and the speech tell back: `animationEnded`
  (`AnimationPlayer.onEnded`) and `speechEnded` (`SpeechPlaying.onFinished`, when the queue of pieces is played). `ChatModel` sends every frame of a turn through it.
- **Pairing by camera.** The Xcode project registers the scheme `joi`; the QR code of the server's *Pair a phone* dialog is `joi://pair?server=...&offer=...`, the phone's camera
  opens the app, and `RootView.onOpenURL` calls `AppModel.open(_:)`: the link is checked (`AppModel.pairing(from:)`: scheme, host `pair`, an `http(s)` server, an offer of letters,
  digits, `-` and `_`), the old pairing is forgotten, and `startDeviceGrant(offer:)` gives the offer to the server, whose dialog approves the phone; the first poll is at once
  (`waitForApproval(askAtOnce:)`) and no code is shown. Tokens that the Keychain refuses are an error at once
  (`CredentialStore.saveFailure`), not a 401 on the next call. A Simulator build with no signature at all has no entitlements, so the Keychain says `-34018`
  (`errSecMissingEntitlement`): the Simulator build is signed ad hoc (`CODE_SIGN_IDENTITY=-`), which is enough for the Keychain there.
- **`Views`**: `RootView` is all the app shows. `ChatView` puts her body above the chat, half of the height (a quarter while the keyboard is up; side by side when the phone lies
  down); the chat list stays at the end (`defaultScrollAnchor(.bottom)`). Over the body are two buttons: *Her look* (a sheet, `PartsView`, one picker per part she has, from
  `AvatarModel.parts`) and a menu with *Sign out*. A change is `ChatModel.switchPart`: a `part` frame with an id, answered by `part_kept` (the body reloads), `part_refused`
  (her words are shown in the sheet: she may decide on it, so the answer can take a whole turn of hers) or `error`. The same sheet has her outfits (`AvatarModel.outfits`, the worn one marked; a picker above the parts, since 1.12): choosing one is `ChatModel.wearOutfit`, an
  `outfit` frame with an id that the server answers with the event `body` (the body is read again, which ends `wearingOutfit`) or an `error` (`outfitFailure`), and it
  sends `?node=` with the app's node id when it reads her body, so the body, parts and outfit are those of the profile the phone is in. The events of the server in the chat are friendly notes
  (`ChatModel.eventNote`: connected, first start, summary written, part switched, task ended, Looking Glass). Her tasks that wait for the user (`web_search`, `fetch_page` without a standing approval) are `ChatModel.questions`, kept from the `task` frames (a frame of the same id with another status takes
  the question back, the connection's loss clears them: the server sends them again when it is back) and shown by `TaskQuestionCard` over the chat, the oldest with how many more wait: *Approve*, *Deny*,
  and in its menu *Always approve <tool>*. `ChatModel.answer` calls `approveTask` / `denyTask` of `HistorySource` (`JoiClient`'s); a 409 (answered elsewhere) just takes the question back, any other
  error stays under the card. Not yet: a notification for a task that waits while the app is closed (the app has to be open: see *Staying connected* in `DESIGN.md`).

## The Xcode project

`joi-ios/project.yml` is the source, written into `Joi.xcodeproj` by XcodeGen (`brew install xcodegen`, then `cd joi-ios && xcodegen`); the project and `App/Info.plist` are not in
Git, so a capability or a setting is added in `project.yml`, not in Xcode. `App/JoiAppMain.swift` is the whole shell; `App/Renderer` (not in Git) is the renderer page, a folder resource of the target. The bundle id is `io.github.enerccio.joi`.
Run in the Simulator: build with `xcodebuild -project Joi.xcodeproj -scheme Joi -destination 'generic/platform=iOS Simulator' CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM= build`
and launch the `.app` in a booted device; the Simulator reaches a server on the Mac at `127.0.0.1` (a development server on 8091 as `CLAUDE.md` describes; ATS
allows local networking, other `http` addresses need a change in `project.yml`).

## Building with Maven

`mvn -Pswift,ios verify -pl joi-ios -am` (the profile `ios` of the root POM adds `joi-ios`; `swift` writes and tests the protocol package): the protocol
package, the renderer page (`npm install`, `npm run build` in `joi-node/src/main/renderer`, as the node's build, then `rsync` into `joi-ios/App/Renderer`), `xcodegen`, `swift test` of `JoiClient` and `JoiApp`, then `xcodebuild` for the Simulator into `joi-ios/target/DerivedData`. A device archive is only made
with `-Dios.skipArchive=false -Dios.team=<team id>`; the version is `-Dios.marketingVersion=x.y.z -Dios.buildNumber=n`. Needs Xcode and XcodeGen (macOS only). The `.ipa`
and TestFlight are not done.

## Tests

`swift test` in each package (or `mvn -Pswift test -pl joi-protocol` for the protocol one). `JoiClientTests` has `FakeHTTP` (answers by a closure and records the calls) and
`FakeServer` / `FakeChannel` (a scripted server that welcomes a hello by itself) in the tests' folder; the timings are short through `NodeConnection.Options`.
The tests of the refresh (four calls together make one), the heartbeat and the new tries check that those rules hold. `JoiAppTests` have a fake link, history and speech
for `ChatModel`.

`IntegrationTests` run against a real server when `JOI_TEST_SERVER` is set (`JOI_TEST_TOKEN` if it needs a token): `JOI_TEST_SERVER=http://127.0.0.1:8091 swift test`.
A node that connects makes the server start her first greeting turn, so use a copy of the data (see `CLAUDE.md`'s notes on a test instance), not the real one.
