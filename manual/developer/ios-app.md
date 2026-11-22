---
label: The iOS app
icon: device-mobile
order: 765
covers:
  - joi-ios
verified: 203475b
---

# The iOS app

The iOS app is a native Swift node (`joi-ios/`), planned in `DESIGN.md` → *The iOS app*. It is a client of the public protocol like any other node: the
server does not change for it. It is three Swift packages and a thin Xcode project around them; the packages are plain SwiftPM, run by `swift test` on the
Mac (Swift 6, so Xcode 16 or later). What the app does so far (stage 1, without her body): it asks for the server, pairs, shows her history, and chats with
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

## `JoiApp`

The app's own code, so that it builds and is tested on the Mac: SwiftUI and the models, on `JoiClient`.

- **`AppModel`** (`@Observable`): the phase of the app (`setup`, `working`, `pairing`, `ready`). `connect()` takes the address (`joi.example.com` is `https://...`),
  asks the server what it takes, and pairs by the device grant unless it takes no auth (a development server); the code, its link and a QR code are shown
  (`PairingView`) while `waitForApproval` polls. The address is kept only after a sign-in, and the app goes straight on to the chat the next time it starts
  (`launch()`); the credentials are in the Keychain. The node's id is a UUID in `UserDefaults`; its name is `phone`.
- **`ChatModel`**: her history, the user's words, her answer as the `text` frames come (a message per turn, found by the `ref` of the frame; a turn the server
  started, `turn`, makes its own), `reply` setting the whole text, `audio` handed to the `SpeechPlaying` (`SpeechPlayer`: `AVAudioPlayer` one piece after the
  other), the status of the connection (`connecting`, `connected`, `reconnecting`, `offline`) and a note when a turn is cut off by a lost connection. It needs
  `NodeLink` and `HistorySource` (which `NodeConnection` and `JoiClient` are), so its tests have neither server nor socket.
- **`Views`**: `RootView` is all the app shows; the chat list stays at the end (`defaultScrollAnchor(.bottom)`). Not yet: her body, approvals, tasks, the
  microphone, the App Intents, notes of the server shown as text (`node.connected` is shown as it is).

## The Xcode project

`joi-ios/project.yml` is the source, written into `Joi.xcodeproj` by XcodeGen (`brew install xcodegen`, then `cd joi-ios && xcodegen`); the project and `App/Info.plist` are not in
Git, so a capability or a setting is added in `project.yml`, not in Xcode. `App/JoiAppMain.swift` is the whole shell. The bundle id is `io.github.enerccio.joi`.
Run in the Simulator: build with `xcodebuild -project Joi.xcodeproj -scheme Joi -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build`
and launch the `.app` in a booted device; the Simulator reaches a server on the Mac at `127.0.0.1` (a development server on 8091 as `CLAUDE.md` describes; ATS
allows local networking, other `http` addresses need a change in `project.yml`).

## Building with Maven

`mvn -o -Pswift,ios verify -pl joi-ios -am` (the profile `ios` of the root POM adds `joi-ios`; `swift` writes and tests the protocol package): the protocol
package, `xcodegen`, `swift test` of `JoiClient` and `JoiApp`, then `xcodebuild` for the Simulator into `joi-ios/target/DerivedData`. A device archive is only made
with `-Dios.skipArchive=false -Dios.team=<team id>`; the version is `-Dios.marketingVersion=x.y.z -Dios.buildNumber=n`. Needs Xcode and XcodeGen (macOS only). The `.ipa`
and TestFlight are not done.

## Tests

`swift test` in each package (or `mvn -o -Pswift test -pl joi-protocol` for the protocol one). `JoiClientTests` has `FakeHTTP` (answers by a closure and records the calls) and
`FakeServer` / `FakeChannel` (a scripted server that welcomes a hello by itself) in the tests' folder; the timings are short through `NodeConnection.Options`.
The tests of the refresh (four calls together make one), the heartbeat and the new tries check that those rules hold. `JoiAppTests` have a fake link, history and speech
for `ChatModel`.

`IntegrationTests` run against a real server when `JOI_TEST_SERVER` is set (`JOI_TEST_TOKEN` if it needs a token): `JOI_TEST_SERVER=http://127.0.0.1:8091 swift test`.
A node that connects makes the server start her first greeting turn, so use a copy of the data (see `CLAUDE.md`'s notes on a test instance), not the real one.
