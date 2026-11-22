---
label: The iOS app
icon: device-mobile
order: 765
covers:
  - joi-ios
verified: 7768ec3
---

# The iOS app

The iOS app is a native Swift node (`joi-ios/`), planned in `DESIGN.md` → *The iOS app*. It is a client of the public protocol like any other node: the
server does not change for it. So far there are two Swift packages and no app target yet; they are plain SwiftPM, run by `swift test` on the Mac
(Swift 6, so Xcode 16 or later; the Simulator is only needed for the app).

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

## Tests

`swift test` in each package (or `mvn -o -Pswift test -pl joi-protocol` for the protocol one). `JoiClientTests` has `FakeHTTP` (answers by a closure and records the calls) and
`FakeServer` / `FakeChannel` (a scripted server that welcomes a hello by itself) in the tests' folder; the timings are short through `NodeConnection.Options`.
The tests of the refresh (four calls together make one), the heartbeat and the new tries check that those rules hold.

`IntegrationTests` run against a real server when `JOI_TEST_SERVER` is set (`JOI_TEST_TOKEN` if it needs a token): `JOI_TEST_SERVER=http://127.0.0.1:8091 swift test`.
A node that connects makes the server start her first greeting turn, so use a copy of the data (see `CLAUDE.md`'s notes on a test instance), not the real one.
