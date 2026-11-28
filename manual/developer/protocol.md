---
label: The protocol
icon: plug
order: 760
covers:
  - joi-protocol/src/main/resources/joi-protocol
  - joi-protocol/src/main/java/com/github/enerccio/joi/protocol
  - joi-protocol/src/test/java/com/github/enerccio/joi/protocol/swift
  - joi-ios/Packages/JoiProtocol
  - joi-server/src/main/java/com/github/enerccio/joi/api/JoiAdminApi.java
verified: 1bc7a07
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

`JoiProtocol.VERSION` is `major.minor` (now 1.16), sent in the handshake. A minor version only adds things; both sides ignore fields and message types they
do not know. The server supports at least the previous major version and says so when a node is too old.

## The HTTP API

| Call | What |
|---|---|
| `GET /api/v1`, `GET /api/v1/me` | the version and how to authenticate (no auth needed for the first); who you are |
| `GET /api/v1/jois`, `GET /api/v1/jois/{id}` | her assistants; one with names, personality, today's mood, chat model, voice and her animations |
| `POST /api/v1/jois/{id}/chat` | `{"text": ...}`: she answers in text and speech. Whole, or streamed as server-sent events with `"stream": true` or `Accept: text/event-stream` (text pieces, `reply`, `audio` pieces, `done`, `error`, keep-alives every 15 s) |
| `POST /api/v1/jois/{id}/images`, `GET /api/v1/images/{id}` | an image for her: the raw body, streamed to a file; answer `ImageUpload` (id, size, width, height). The id goes in `ChatRequest.images` / `Say.images`; `GET` gives the image back (ETag = id) |
| `GET /api/v1/jois/{id}/chat` | her history, the latest 500 exchanges (`limit` up to 1000) oldest first; `more` and `before` slide the window back; `summarized` and `toolImages` since 1.16 |
| `GET /api/v1/jois/{id}/body` | the body she is in: models with their `.joim`, the parts that can be switched, how she looks, the animations with their `.joia`, her outfits (`outfits`, the worn one marked; 1.12); `?node=<nodeId>` (1.12) gives the body of the profile that node is in; 404 `no_body` for none |
| `GET /api/v1/models/{id}/joim`, `GET /api/v1/bodies/{id}/joia` | stream the files from disk, with the hash as the ETag (304 to `If-None-Match`) |
| `GET .../tasks`, `.../tasks/{task}`, `POST .../approve`, `.../deny`, `.../cancel` | her tasks; answer one (`approve` with `{always: true}` is the standing approval) |
| `GET/PUT/DELETE .../auto-approved[/{tool}]` | the standing approvals |
| `GET/POST /api/v1/admin/update`, `GET .../update/log` | the update of the installation (1.13, scope `update`) |
| `GET /api/v1/admin/extensions[/{jar}]`, `PUT/DELETE .../{jar}`, `POST .../{jar}/stop`, `.../start`, `GET .../{jar}/log` | the extensions (1.13, scope `extensions`): list, install or replace (the JAR as the body), remove, stop, start, the log |

A file to download is its URL, SHA-256, size, time and whether it is **current** (it holds what is set up now; else it is written again on the Exit of its
dialog). A task or file of another user is 404; a call that does not fit the status is 409 `conflict`.

### Authentication

The administrator chooses (Administration → API access) whether tokens and OAuth are offered. A **token** (`joi_...`) is made in User Settings → Access, shown once. **OAuth**
is the device grant (RFC 8628): the device asks `/oauth/device_authorization` for a code like `WDJB-MJHT`, the user approves it in the UI, the device polls
`/oauth/token` and gets an access token (`joia_...`, an hour by default) and a refresh token (`joir_...`, 90 days; each refresh gives a new pair). **No auth** is the
configuration's (`joi.api.no-auth`): a call without credentials acts as `joi.api.no-auth-user`, else the first administrator; a credential that is sent must
still be valid. Credentials are `AccessToken` rows (prefix and SHA-256).

**Her expressions (1.15).** The node frame `expression` (`ShowExpression`: the name of an expression of her model, `weight` 0 to 1, `seconds` at most 60;
null for the node's defaults, 0.8 and 4) goes to her nodes when she calls `set_expression` (`ExpressionTools`, `NodeServiceImpl.express`). A null
`expression` ends the one shown. A node older than 1.15 ignores the frame.

**Pictures in a tool result (1.14).** `ToolResult.images` (up to four ids of images uploaded with `POST .../images`) carries pictures with a
node tool's result, as the node's `view_image` does. A result is text for the model, so `NodeServiceImpl.result` puts the ids into
`ToolImages` (per assistant: her turns run one at a time), and `CallToolsStep` takes them after the round's calls and adds one message:
the pictures as attachments for a multimodal chat model, else the describing model's notes (`ChatImageService.describe`), in the
template `TOOL_IMAGES` (`ToolImagesTemplateData`: `tools`, `shows`). The message stays in that turn only. The ids shown go into the
turn's `TurnProperties.TOOL_IMAGES`; `StoreStep` writes them into her message (`ChatMessage.toolImages`) and links them to it
(`ChatImageService.attach`), `ChatImageService.deleteOf` deletes them with it, and `JoiChatsPart` shows them with her reply. The history gives
them as `ChatEntry.toolImages` (1.16). An id that is not the
user's image is skipped. A node older than 1.14 sends no `images`.

**The history window and what is summarized (1.16).** `JoiApi.history` gives the latest 500 messages by default (`DEFAULT_HISTORY`;
`limit` 1 to 1000, `MAX_HISTORY`), and `before` (the first id given) slides the window back. Each entry has `summarized` when it was sent
before `SummarizerService.coveredUpTo` (the prompt's history starts there, see [The chat turn](chat-turn.md)), null otherwise, and
`toolImages` from `ChatMessage.toolImageUuids`. The JavaFX node (`ChatPane.drawPast`) draws a line after the last summarized entry and
pictures with `ServerClient.image` (cached in `<home>/cache/images`), "Show earlier messages" when `more`, and a Copy menu on every
line; the app (`ChatModel.messages(of:)`) adds a `.summaryBreak` row and counts the pictures.

**Administration (1.13).** Marginalia's `/admin` calls of its `ApiDispatcher`, in Joi's API: `JoiAdminApi`, called by `JoiApi.route` for a path that
starts with `admin`. A token has administration scopes (`AdminScope`: `update`, `extensions`; `AccessToken.scopes`, an extended attribute, names
comma separated as Marginalia's `ApiKey`). Only an administrator gives them (`AccessTokenService.create(name, expires, scopes)`, `AdminGuard`), and
`authenticate` puts them in the `Principal` only for a `TOKEN` whose owner is an administrator now: an OAuth device, the local node and no auth have none.
A call without its scope is 403 `forbidden`; `GET /api/v1/me` lists the scopes (`Me.scopes`). The update calls are the platform's `UpdateService`
(409 `conflict` when `canRequest` is false); the extension calls are `OsgiService` (503 `unavailable` without it, as in the tests). `PUT .../{jar}` is
streamed like an image: `JoiAdminApi.isExtensionUpload` tells `JoiApiServlet` to pass the stream on (256 MB, by `Content-Length` and again while it
copies), the call checks the credentials, copies the body to a temporary file and hands its bytes to `OsgiService.installPackage` (the platform takes
bytes); an `ExtensionVerificationException` is 422 `extension_rejected` with the report in the message. A `DELETE` answers 204. `JoiAdminApiTest` runs
the calls on a stand-in for OSGi.

**Profiles and outfits (1.12).** `GET .../body?node=<nodeId>` answers the body of the node's profile (see [Bodies and models](bodies.md)), `BodyInfo.outfits` lists the outfits (`BodyOutfit`), and the
node frame `outfit` (`OutfitSwitch`, node to server) puts one on in the node's profile; `NodeServiceImpl.outfitSwitch` wears it, tells her as for a part and the `body` event goes to the nodes of that profile.
The Swift types are generated from the schemas as for every other message.

**Images (1.11).** `POST /api/v1/jois/{id}/images` is the one call whose body `JoiApiServlet` does not read: for it (`JoiApi.isImageUpload`) the servlet passes the request's stream on
(`ApiCall.stream`, `bodyStream()`) and refuses by `Content-Length` over 20 MB (413 `bad_image`) before anything is read; `JoiApi.upload` checks the credentials first, streams the body to a
file in the user's uploads (capped again while it copies), and `ChatImageService.store` has `ChatImages.normalize` make the kept JPEG. Then `ChatRequest.images` (at most `MAX_IMAGES`, 4) or
`Say.images` names the uploads, and `text` may be empty when there is an image. `JoiInfo.images` is whether she can look at images (her chat model sees, or a multimodal deep reasoning model describes for it);
without it a chat or `say` with images is 409 `no_vision` (an `error` frame on the node socket) before anything is stored. `bad_image`: no readable image, too large (413) or an id that is not the
user's. A client that has no way to know (an older server: the field is absent) does not offer images. See [A chat turn](chat-turn.md).

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
- Regenerate and test: `mvn -Pswift process-test-classes -pl joi-protocol` (generate only) or `mvn -Pswift test -pl joi-protocol` (generate, the Java
  tests, then `swift test`). Not in the normal build, which must not write into the source tree. With only the Command Line Tools, pass the Testing
  framework to `swift`: `-Dswift.test.flags="-Xswiftc -F$F -Xlinker -F$F -Xlinker -rpath -Xlinker $F"` with
  `F=/Library/Developer/CommandLineTools/Library/Developer/Frameworks` and `DEVELOPER_DIR` set to the tools' folder; with Xcode (its licence accepted) plain
  `swift test` works.

## The containers

`.joim` and `.joia` start with a magic number (`0x1DEA1A10` and `0x1DEA1AC7`), a header with the format version (a reader refuses a newer major version),
and sections, each with its kind, length and SHA-256: the metadata as CBOR (RFC 8949) and the payload (the VRM, one VRMA per clip) as raw bytes.
Sections are read and written as streams. A reader skips kinds it does not know. An animation of a `.joia` may carry `eyes` and `expression` (the face it forces, see `joia.md`). The containers are in `protocol/joim/JoimFile` and
`protocol/joia/JoiaFile`, so a node reads them with the same code; the mapping to a model stays in the server.
