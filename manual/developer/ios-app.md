---
label: The iOS app
icon: device-mobile
order: 765
covers:
  - joi-ios
verified: 1420cb9
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

- **`JoiClient`** (an actor): the HTTP API (`info`, `me`, `jois`, `joi`, `history`, `body`, `chat`, `tasks`, `approveTask`, `denyTask`) and the pairing, the OAuth
  device grant (`startDeviceGrant`, `pollDeviceGrant`, `waitForApproval`). It follows the reference node's `ServerClient`: an access token that is about to
  end is made again from the refresh token before a call, and after a 401 (once, and not when another call did it meanwhile); **one refresh at a time**, since a
  second one would use the refresh token the first one replaced, which ends the pairing; a refresh the server refuses clears the credentials, so the user pairs
  again. A server on a path (`https://example.com/joi`) is allowed. Errors are `ServerError` (the API's `code`, OAuth's `error`, else `http_<status>`).
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
- **Her mouth.** The page reads it from her voice (see *Her mouth* in [Writing a node](nodes.md)). `SpeechPlayer` numbers the pieces it plays and has `onQueued`, `onStarted` and
  `onStopped`; `AppModel` hands them to `RendererBridge.speech`, `speechStarted` and `speechStopped`, which send the `speech`, `speech-start` and `speech-stop` messages (the
  piece's bytes in Base64; not kept for a page that opens later).
- **Her face.** Her eyes and her mood are messages to the page like the mouth (see *Her face* in [Writing a node](nodes.md)): `ChatModel.state` sets her eyes from her state
  (`AnimationPlayer.setEyes`), a reply's `mood` and the mood in her info (also read again on the server's `joi` event) set her mood (`setMood`); `RendererBridge` sends `eyes` and `mood`
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
  (her words are shown in the sheet: she may decide on it, so the answer can take a whole turn of hers) or `error`. The events of the server in the chat are friendly notes
  (`ChatModel.eventNote`: connected, first start, summary written, part switched, task ended, Looking Glass). Her tasks that wait for the user (`web_search`, `fetch_page` without a standing approval) are `ChatModel.questions`, kept from the `task` frames (a frame of the same id with another status takes
  the question back, the connection's loss clears them: the server sends them again when it is back) and shown by `TaskQuestionCard` over the chat, the oldest with how many more wait: *Approve*, *Deny*,
  and in its menu *Always approve <tool>*. `ChatModel.answer` calls `approveTask` / `denyTask` of `HistorySource` (`JoiClient`'s); a 409 (answered elsewhere) just takes the question back, any other
  error stays under the card. Not yet: the microphone, the App Intents, a notification for a task that waits while the app is closed (the app has to be open: see *Staying connected* in `DESIGN.md`).

## The Xcode project

`joi-ios/project.yml` is the source, written into `Joi.xcodeproj` by XcodeGen (`brew install xcodegen`, then `cd joi-ios && xcodegen`); the project and `App/Info.plist` are not in
Git, so a capability or a setting is added in `project.yml`, not in Xcode. `App/JoiAppMain.swift` is the whole shell; `App/Renderer` (not in Git) is the renderer page, a folder resource of the target. The bundle id is `io.github.enerccio.joi`.
Run in the Simulator: build with `xcodebuild -project Joi.xcodeproj -scheme Joi -destination 'generic/platform=iOS Simulator' CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM= build`
and launch the `.app` in a booted device; the Simulator reaches a server on the Mac at `127.0.0.1` (a development server on 8091 as `CLAUDE.md` describes; ATS
allows local networking, other `http` addresses need a change in `project.yml`).

## Building with Maven

`mvn -o -Pswift,ios verify -pl joi-ios -am` (the profile `ios` of the root POM adds `joi-ios`; `swift` writes and tests the protocol package): the protocol
package, the renderer page (`npm install`, `npm run build` in `joi-node/src/main/renderer`, offline as the node's build, then `rsync` into `joi-ios/App/Renderer`), `xcodegen`, `swift test` of `JoiClient` and `JoiApp`, then `xcodebuild` for the Simulator into `joi-ios/target/DerivedData`. A device archive is only made
with `-Dios.skipArchive=false -Dios.team=<team id>`; the version is `-Dios.marketingVersion=x.y.z -Dios.buildNumber=n`. Needs Xcode and XcodeGen (macOS only). The `.ipa`
and TestFlight are not done.

## Tests

`swift test` in each package (or `mvn -o -Pswift test -pl joi-protocol` for the protocol one). `JoiClientTests` has `FakeHTTP` (answers by a closure and records the calls) and
`FakeServer` / `FakeChannel` (a scripted server that welcomes a hello by itself) in the tests' folder; the timings are short through `NodeConnection.Options`.
The tests of the refresh (four calls together make one), the heartbeat and the new tries check that those rules hold. `JoiAppTests` have a fake link, history and speech
for `ChatModel`.

`IntegrationTests` run against a real server when `JOI_TEST_SERVER` is set (`JOI_TEST_TOKEN` if it needs a token): `JOI_TEST_SERVER=http://127.0.0.1:8091 swift test`.
A node that connects makes the server start her first greeting turn, so use a copy of the data (see `CLAUDE.md`'s notes on a test instance), not the real one.
