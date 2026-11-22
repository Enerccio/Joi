---
label: The protocol
icon: plug
order: 760
covers:
  - joi-protocol/src/main/resources/joi-protocol
  - joi-protocol/src/main/java/com/github/enerccio/joi/protocol
  - joi-protocol/src/test/java/com/github/enerccio/joi/protocol/swift
  - joi-ios/Packages/JoiProtocol
verified: 3701cea
---

# The protocol

The server's API is public and documented, and the reference node is only one client of it. Anyone can write a node in any language.

## The source of truth

`joi-protocol/src/main/resources/joi-protocol` holds:

- `schemas/`: a JSON Schema for every message and record;
- `openapi.yaml`: the HTTP API with the rules of each call;
- `asyncapi.yaml`: the node WebSocket (channels, messages, order, errors);
- `joim.md` and `joia.md`: the two file formats.

The Java records are in `com.github.enerccio.joi.protocol` (`http`, `node`, `oauth`, `joim`, `joia`) and use Gson only. `SchemaContractTest` checks the
records, the enums and the AsyncAPI document against each other, and the server's tests check every answer against the schemas. **A change of the API
changes the schema, the record and the docs together with the code.**

## Versioning

`JoiProtocol.VERSION` is `major.minor` (now 1.10), sent in the handshake. A minor version only adds things; both sides ignore fields and message types they
do not know. The server supports at least the previous major version and says so when a node is too old.

## The HTTP API

| Call | What |
|---|---|
| `GET /api/v1`, `GET /api/v1/me` | the version and how to authenticate (no auth needed for the first); who you are |
| `GET /api/v1/jois`, `GET /api/v1/jois/{id}` | her assistants; one with names, personality, today's mood, chat model, voice and her animations |
| `POST /api/v1/jois/{id}/chat` | `{"text": ...}`: she answers in text and speech. Whole, or streamed as server-sent events with `"stream": true` or `Accept: text/event-stream` (text pieces, `reply`, `audio` pieces, `done`, `error`, keep-alives every 15 s) |
| `GET /api/v1/jois/{id}/chat` | her history, the latest 50 exchanges (`limit` up to 200) oldest first; `more` and `before` page back |
| `GET /api/v1/jois/{id}/body` | the body she is in: models with their `.joim`, the parts that can be switched, how she looks, the animations with their `.joia`; 404 `no_body` for none |
| `GET /api/v1/models/{id}/joim`, `GET /api/v1/bodies/{id}/joia` | stream the files from disk, with the hash as the ETag (304 to `If-None-Match`) |
| `GET .../tasks`, `.../tasks/{task}`, `POST .../approve`, `.../deny`, `.../cancel` | her tasks; answer one (`approve` with `{always: true}` is the standing approval) |
| `GET/PUT/DELETE .../auto-approved[/{tool}]` | the standing approvals |

A file to download is its URL, SHA-256, size, time and whether it is **current** (it holds what is set up now; else it is written again on the Exit of its
dialog). A task or file of another user is 404; a call that does not fit the status is 409 `conflict`.

### Authentication

The administrator chooses (Administration → API access) whether tokens and OAuth are offered. A **token** (`joi_...`) is made in User Settings → Access, shown once. **OAuth**
is the device grant (RFC 8628): the device asks `/oauth/device_authorization` for a code like `WDJB-MJHT`, the user approves it in the UI, the device polls
`/oauth/token` and gets an access token (`joia_...`, an hour by default) and a refresh token (`joir_...`, 90 days; each refresh gives a new pair). **No auth** is the
configuration's (`joi.api.no-auth`): a call without credentials acts as `joi.api.no-auth-user`, else the first administrator; a credential that is sent must
still be valid. Credentials are `AccessToken` rows (prefix and SHA-256).

**Her mood (1.10).** The node frame `mood` (`MoodState`: `mood`, `name`, and the face the user has set for it: `expression`, `weight`, `eyes`; empty before her first mood) goes to a node
after its manifest (`NodeServiceImpl.sendMood`) and again each time her mood changes (`ChatService.watchMoods`, which `NodeService` listens to: no dependency the other way). The
node shows it on her face; the schema is `MoodState.schema.json`, the Swift type is generated.

**Pairing offers (1.9).** `POST /oauth/device_authorization` takes the optional form field `pairing_offer`: the secret of the QR code the server's *Pair a phone* dialog shows
(`joi://pair?server=...&offer=...`, built by `PairPhoneDialog`). `DeviceAuthorizationService.createOffer` makes it (in memory, ten minutes), the device gives it with its request for
codes, and the dialog, polling `waitingForOffer`, approves that device as the user of the screen (the approval needs the user's session). An unknown, expired or closed offer is a
400 `invalid_request`. The app's side is `JoiClient.startDeviceGrant(offer:)` and `AppModel.open(_:)`.

**The local door (1.8).** `POST /oauth/local` (`{client_id, client_name}`, answer `LocalToken`: `access_token` `joil_...`, `token_type`, `expires_in`; one token per
`client_id` is reused, kind `LOCAL`) gives a token with no credentials, but only on the server's **Unix domain socket** (`joi.api.local-socket`, a path):
`JoiApiServlet` asks `ApiConfiguration.isLocalSocket(request.getLocalAddr(), request.getLocalPort())` (on a socket the local address is the path and the port -1; the
remote address is empty) and puts the answer into `ApiCall.local`, so what a caller sends cannot change it. On TCP the call is 404 `invalid_request`; before the first account
409 `no_user`. The token acts as the user `joi.api.no-auth-user`, else the first administrator, and is not subject to the switches of tokens and OAuth in Administration.
A client that wants to use the socket needs HTTP/1.1 and WebSocket over it: see [Writing a node](nodes.md) and [The desktop app](desktop.md).

## The Swift package

The iOS app (planned, see `DESIGN.md` → *The iOS app*) does not copy the protocol by hand: `joi-ios/Packages/JoiProtocol` is a Swift package whose types are
**generated from the schemas**, not from the Java records, so it cannot drift from the source of truth.

- `SwiftGenerator` (`joi-protocol/src/test`, plain Java and Gson, Jackson YAML for `asyncapi.yaml`) writes into the package's two `Generated` folders:
  a `Codable`, `Hashable`, `Sendable` struct per object schema (a nullable or not required property is an `Optional`, `date-time` is `Date`, Base64 is
  `Data`, a free-form object is `[String: JSONValue]`, a property that is not a plain Swift name gets a coding key); a string enum per enum schema with an
  `unknown(String)` case, so a value of a newer server does not fail a decode (a value named `unknown` becomes `unknownValue`, a Swift keyword gets backticks); `Frames.swift` (the `NodeFrameData` of each type of frame, taken from
  `asyncapi.yaml`, `Frame.typedData()` and a constructor per type, `Frame.say(...)`); `JoiProtocolInfo` (the constants of `JoiProtocol`); and
  `FixtureTests.swift`, a decode and an encode of each sample of `ProtocolSamples` (the same samples `SchemaContractTest` validates).
- A schema feature the generator does not know is an error, not a guess; the types of `NodeMessage.schema.json` and of `asyncapi.yaml` must agree.
- By hand: `Package.swift`, `JSONValue.swift` (`JSONValue`, the coders `JoiJSON.makeDecoder()` / `makeEncoder()` with ISO 8601 dates with or without
  fractions, `JoiProtocolError`) and the tests of what the samples do not show (`HandWrittenTests`). The tests use Swift Testing and keep Foundation out
  of the files that import it (the Command Line Tools lack its cross-import module).
- **The generated files are in Git**, because an Xcode build does not run Maven. `SwiftGeneratorTest` generates in memory and fails, with the command to
  run, when a file differs or is left over; it skips itself when there is no `joi-ios` folder (a build of the server alone).
- Regenerate and test: `mvn -o -Pswift process-test-classes -pl joi-protocol` (generate only) or `mvn -o -Pswift test -pl joi-protocol` (generate, the Java
  tests, then `swift test`). Not in the normal build, which must not write into the source tree. With only the Command Line Tools, pass the Testing
  framework to `swift`: `-Dswift.test.flags="-Xswiftc -F$F -Xlinker -F$F -Xlinker -rpath -Xlinker $F"` with
  `F=/Library/Developer/CommandLineTools/Library/Developer/Frameworks` and `DEVELOPER_DIR` set to the tools' folder; with Xcode (its licence accepted) plain
  `swift test` works.

## The containers

`.joim` and `.joia` start with a magic number (`0x1DEA1A10` and `0x1DEA1AC7`), a header with the format version (a reader refuses a newer major version),
and sections, each with its kind, length and SHA-256: the metadata as CBOR (RFC 8949) and the payload (the VRM, one VRMA per clip) as raw bytes.
Sections are read and written as streams. A reader skips kinds it does not know. An animation of a `.joia` may carry `eyes` and `expression` (the face it forces, see `joia.md`). The containers are in `protocol/joim/JoimFile` and
`protocol/joia/JoiaFile`, so a node reads them with the same code; the mapping to a model stays in the server.
