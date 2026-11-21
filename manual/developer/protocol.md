---
label: The protocol
icon: plug
order: 760
covers:
  - joi-protocol/src/main/resources/joi-protocol
  - joi-protocol/src/main/java/com/github/enerccio/joi/protocol
verified: b6f812e
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

`JoiProtocol.VERSION` is `major.minor` (now 1.7), sent in the handshake. A minor version only adds things; both sides ignore fields and message types they
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

## The containers

`.joim` and `.joia` start with a magic number (`0x1DEA1A10` and `0x1DEA1AC7`), a header with the format version (a reader refuses a newer major version),
and sections, each with its kind, length and SHA-256: the metadata as CBOR (RFC 8949) and the payload (the VRM, one VRMA per clip) as raw bytes.
Sections are read and written as streams. A reader skips kinds it does not know. The containers are in `protocol/joim/JoimFile` and
`protocol/joia/JoiaFile`, so a node reads them with the same code; the mapping to a model stays in the server.
