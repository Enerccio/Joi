# Joi — design notes

The state of the discussion about Joi as of 2026-11-17: what it is meant to be, what is decided, what was tried, and
what is still open. It started from a conversation with Gemini about a hands-free assistant, and was then aligned with
the conventions of [Marginalia](../marginalia).

Status: `joi-server` boots on Marginalia's platform (login, then a workspace with a Models part where the
platform's AIs are added and edited); nothing else of the application exists yet. Two spikes exist (`spikes/avatar-vrm`), see [Spikes](#spikes).

## What Joi is

A personal AI assistant you talk to without pressing a button, which can act on your computers within limits you set,
and which has a face: a 3D avatar shown on a Looking Glass holographic display (or a 2D overlay when there is none).

The name comes from the holographic companion in Blade Runner 2049. The wake word is **"Hey Joi"**: "Joi" alone sounds
like "joy" and would wake on "enjoy", "join" and so on.

## Architecture

```
┌──────────────────────────────┐        ┌──────────────────────────────────────────────┐
│ joi-server ("brain")         │  WS    │ joi-node ("ears, hands, face")  one per machine│
│ • LLM tool loop, providers   │◄──────►│ • mic → VAD → wake word → speech-to-text     │
│ • memory, moods, bodies      │        │ • capability manifest on connect             │
│ • TTS (ComfyUI), event srcs  │        │ • harness: local policy, approvals, audit    │
│ • Vaadin UI                  │        │ • OS actions (files, shell, AX, screenshots) │
│ • stores avatar models       │        │ • avatar cache + local WS to the renderer    │
└──────────────────────────────┘        └───────────────┬──────────────────────────────┘
                                                        │ local WS (state frames)
                                        ┌───────────────▼──────────────────────────────┐
                                        │ renderer: three.js + VRM (+ Looking Glass)   │
                                        │ or a Unity app (see Avatar)                  │
                                        └──────────────────────────────────────────────┘
```

Modules: **joi-protocol** (records shared by both sides: manifest, policy, events, tool calls; JSON, versioned message
types), **joi-server**, **joi-node**, and the renderer(s).

- **Server** runs either fully locally or in Docker, like Marginalia.
- **Node** is Java on purpose, so it also runs on Windows and Linux. On macOS it must be a real `.app` bundle
  (jpackage, own bundle id, `NSMicrophoneUsageDescription`…) from day one: microphone, screen recording and
  accessibility permissions (TCC) belong to the executable that starts the JVM. `-Dapple.awt.UIElement=true` keeps it
  out of the Dock.
- **Several nodes** can connect to one server; the server registers per node only the tools that node offers.
- **Server ↔ node security:** nodes are paired with the server (a token per node, TLS when not on localhost) and can be
  revoked from the UI. Built for the HTTP API (see below): a **token** the user creates, or **OAuth** (the device
  grant, the pairing of a node), or **no auth** for development and localhost setups.

### The node protocol is a public, documented API

Anyone can write a node, in any language and for any platform: a Raspberry Pi in the kitchen, a car, a smart-home
bridge, a game mod, a Swift iPhone app, a Python script. joi-node is just the reference implementation, not a privileged
one; the server treats every node the same.

- **The spec is the source of truth, not the Java classes.** JSON Schema for every message, plus an
  [AsyncAPI](https://www.asyncapi.com/) document for the WebSocket (channels, message order, errors). The joi-protocol
  Java records are generated from the schemas (or checked against them in a test), so the docs can't drift from the code.
- **What the spec covers:**
  - the handshake: protocol version, pairing token, node name and kind
  - the manifest and `tools_changed`
  - tool calls and results, including streamed progress, cancellation and errors
  - approvals: the node asks, the user answers on the node, the server is told
  - audio in (speech-to-text results or raw audio), audio out (TTS chunks, "stop speaking")
  - avatar state frames, events, heartbeats and reconnecting
- **Versioning:** `major.minor` in the handshake. Minor versions only add things, and both sides ignore fields and
  message types they don't know. The server supports at least the previous major version, and says clearly when a node
  is too old.
- **Optional parts:** a node declares which parts it implements (`tools`, `audio-in`, `audio-out`, `avatar`, `approvals`,
  `screen`…). A minimal node can be a few dozen lines: handshake, a manifest with one tool, answer calls.
- **Help for node authors:**
  - a conformance test suite that runs against a node and reports what passes
  - a mock server to develop against
  - a small example node in Python
  - a node inspector in the Vaadin UI: the live message log, the manifest, the last tool calls
- **Security stays on both sides:** a third-party node is untrusted. The server still validates every result, and each
  node's tools are labeled with that node in the prompt (results are data, never instructions to Joi). The user decides
  per node what Joi may call there, and can revoke a node at any time.
- **Built so far (HTTP, `joi-protocol` 1.1):** the module `joi-protocol` holds the JSON Schemas (the source of truth),
  an OpenAPI description (`openapi.yaml`, with the rules of each call) and the Java records; a test checks that the
  records match the schemas, and the server's tests check every answer against them. The calls:
  - `GET /api/v1`: the version and how to authenticate (no auth needed). `GET /api/v1/me`.
  - `GET /api/v1/jois`: her assistants (id, name, last chatted). `GET /api/v1/jois/{id}`: one of them (names,
    personality, today's mood, wake word, chat model, voice with its description, and since 1.2 the animations she
    plays in her states, `JoiAnimations`).
  - `POST /api/v1/jois/{id}/chat`: `{"text": ...}`; she answers in text and speech. Whole by default (`ChatReply`, the
    audio Base64), or **streamed** as server-sent events with `"stream": true` or `Accept: text/event-stream`: text
    pieces while the model writes, `reply` when the text is stored, `audio` pieces (a sentence or a few, each a whole
    file, the first sentence alone so it plays soon, each next one at most twice the one before up to the model's
    chunk size, so it is rendered while the one before plays), `done`; `error` ends a failed one; keep-alive comments
    every 15 s. Both are useful: a simple client takes the whole reply, a voice node the stream. The speech starts once the
    text is whole (speaking while the model still writes is roadmap step 3). The turn is stored as a `ChatMessage`
    with its prompt; turns of one assistant run one at a time.
  - `GET /api/v1/jois/{id}/chat` (1.3): her history, the latest 50 exchanges (`limit`, up to 200) oldest first,
    each the user's words or the kind of the event, and her reply; `more` and `before` page back. A node shows it
    when it starts, so the chat goes on where it was.
  - `GET /api/v1/jois/{id}/body` (1.1): the body she is in (`404 no_body` for none): its models, the full one and
    the Looking Glass one, each with its `.joim` to download; the parts of the full model that can be switched, each
    in the state the body is in (the model's state); how she looks; and the animations the body plays (whether all of
    the library, the list) with their `.joia`. A file to download is its URL, SHA-256, size, time and whether it is
    **current** (it holds what is set up now; else it is written again on the Exit of its dialog).
    `GET /api/v1/models/{id}/joim` and `GET /api/v1/bodies/{id}/joia` stream the files from the disk, with the hash
    as the ETag (304 to `If-None-Match`), so a node caches them by hash. Only the user's own models and bodies.
    Until the WebSocket, a node polls the body call to see a change.
  - **Auth.** The administrator chooses (Administration → API access) whether **tokens** (`joi_...`, made in User
    Settings → Access, shown once, with an optional expiry) and **OAuth** are offered. OAuth is the device grant
    (RFC 8628): the device asks `/oauth/device_authorization` for a code like `WDJB-MJHT`, the user approves it in the
    UI (User Settings → Access → *Connect a device*, or the link `/?device=<code>` the device shows, after the login),
    the device polls `/oauth/token` and gets an access token (`joia_...`, an hour by default) and a refresh token
    (`joir_...`, 90 days; each refresh gives a new pair). **No auth** is not the administrator's but the configuration's
    (`joi.api.no-auth`, on in `mvn jetty:run`): a call without credentials acts as `joi.api.no-auth-user`, else the
    first administrator; a credential that is sent must still be valid. Turning a way off stops its credentials at
    once. Credentials are `AccessToken` rows (prefix + SHA-256, like Marginalia's API keys), listed and revoked under
    Access.
- **The node WebSocket (built, 1.5):** `asyncapi.yaml` and the schemas in `joi-protocol`,
  the records in `protocol/node`; `SchemaContractTest` checks the records, the enums and the AsyncAPI document
  against each other. `GET /api/v1/node` with `Upgrade: websocket`, the API's credentials (or in `hello.token` for a
  client that cannot set headers; never in the URL). Every message is a JSON text frame
  `{"type", "id", "ref", "data"}`: `type` names the data's schema, `id` is set when an answer refers to it, `ref` is
  the frame it answers. Unknown types and fields are ignored.
  - **Handshake:** `hello` (protocol version, the node's stable id and short name, kind, software, the assistant,
    its parts) → `welcome` (the version spoken, the session, the assistant, the heartbeat). One assistant per
    connection; the same node connecting again for her replaces the old connection.
  - **Manifest:** `manifest` after `welcome` and again on every change, with a growing `version` (this is the
    `tools_changed` of the design): OS, architecture, free-form capabilities, and the tools (name, description, JSON
    Schema of the arguments, tier `allow` / `ask` / `deny` / `depends`). She sees them as `<node>__<tool>` while the
    node is connected (model APIs allow no dots in function names), described as "On the node "desk" (desktop): ..."
    with what the tier means.
  - **Talking:** `say` (text, speak, reasoning); the frame's id is the turn, and the answer is the streamed chat as
    frames that refer to it: `reasoning`, `text`, `tool` (she used a tool), `reply`, `audio`, then `done`, or
    `error` (`stopped` for a turn stopped by `stop`, the barge-in). Chat moves onto the socket so that stopping and
    streaming share one connection; the HTTP chat stays for simple clients. The server starts a turn itself with
    `turn` (its id and cause), answered the same way: after the first manifest of a connection she **wakes up for
    the very first time** (`first_boot`, when she has no message yet) or **greets the user who is back**
    (`connected`), but not for a node that only lost the connection (`hello.resumed`, 1.3). Each is an event she is told as the user's turn, from a template the user can change
    (`JoiPromptField.FIRST_BOOT_EVENT`, `CONNECTED_EVENT`, `NodeEventTemplateData`: the node, its kind, when they
    last talked), and stored as an event message (`node.first_boot`, `node.connected`). Nothing is said when she has
    no chat model.
  - **Where she is shown (1.4):** `display` (`lookingGlass` true or false) when the user moves her onto a Looking
    Glass or back into the node's window; the server starts her turn about it (`turn` with the cause
    `looking_glass_entered` or `looking_glass_exited`) from its own template (`LOOKING_GLASS_ENTERED_EVENT`,
    `LOOKING_GLASS_EXITED_EVENT`), stored as `node.looking_glass_entered` / `node.looking_glass_exited`. A node that
    shows her there as it starts (it remembered the choice) does not send it: she was there already.
  - **Her parts (1.5):** `part` (the part's id and the state's id) when the user takes off or puts on a part of her on
    the node. The server keeps it on the body (`BodyService.switchPart`, so it is the same at the next start and on every
    node) and starts her turn about it (`turn` with the cause `part_switched`) with the text of the `body.part_switched`
    event (the state's sentence), stored as `node.part_switched`; a part or a state the body does not have is a
    `bad_message`. **Staged on both sides.** The node holds the user's changes back (`PartStaging`: the last state of a
    part counts, nothing goes until the user left the parts alone for 4 s), then sends one `part` with an `id` for each
    changed part and **locks the controls** until the server answered each (`part_kept`, or `error`, both with the id;
    15 s without an answer frees them). Only what the server kept is shown on her body; what it did not keep is shown
    as it is kept again. The lock lasts only until the answer, not through her reaction. The server **gathers** what it
    has not told her yet (`NodeSession.pend`): after the last event it waits 1.5 s, and while a turn of hers runs it
    waits for the turn to end, then starts **one** turn (cause `part_switched`) whose input has the text of every
    event, one per line, in the order they happened.
    **When the change shows (built).** The node has a switch, `partsShown` (a checkbox in the controls panel, kept in
    `NodeSettings`): `kept` (default: shown once the server answered `part_kept`) or `reaction` (shown with her
    reaction: the first `text` or `audio` of the `part_switched` turn, or its `done` / `error`, or after 40 s, so the
    lock never hangs). `part_kept` has `told`: whether her reaction is still to come; when it is not (she has not
    chatted, no chat model, or she decided on it herself) the node shows the change at once. The server writes the
    state when the `part` frame arrives, as her prompt (her look and her parts) must follow it in the reaction turn, and
    other nodes must see it. **A batch the server does not answer** in 15 s is not given up on blindly: the node reads
    `GET .../body` and takes a part as kept when the server has the state asked for; a part it does not have yet may
    still be being decided, so it asks again, three times in all; when the call fails the server is gone, and the
    controls are freed. An answer that comes after the node gave up on it is shown all the same.
    **Consent (built, per personality).** A personality has a text, `partConsent` (blank: she never decides, the change
    is made and she reacts), that says how she decides when the user asks to change a part: a tsundere's can say "If the
    user begs, consider allowing it." A built-in personality's text is changed in the personality dialog like its
    prompt (blank keeps the built-in one, which is blank), and a clone keeps it. With a text, a `part` is a **request**:
    the body stays as it is, the server tells her (`PART_REQUEST_EVENT`, a template: the requests with a number each,
    what else happened, `{{consent}}`) and she answers each with the tool `decide_part_change` (the request's number,
    `allow` or `refuse` with a reason she says); the tool is only offered in the turn that told her. On allow the
    server stores it and answers `part_kept` (`told` false); on refuse `part_refused` (her reason), and the node shows
    the control as it was. A request she leaves unanswered, a turn that fails, and the case where she has not chatted or
    has no chat model are all **allowed**: the body is the user's, and a failed call is no refusal. A request that
    changes nothing (the part is in that state already) is just answered. `GET .../body` has, for each part, its states with the toggles each sets (`BodyPartState`), and
    every toggle of the model as the body has it (`BodyInfo.toggles`), so a node can show the state without reading
    the `.joim`'s metadata.
  - **Tools:** `tool_call` (call id, tool, arguments, the turn when it is this node's, a timeout) → the node checks
    its harness again, may send `tool_progress` and `approval` (asked, then allowed or denied, with scope and how)
    → exactly one `tool_result` (ok, content, an error word: `denied`, `failed`, `cancelled`, `timeout`,
    `unknown_tool`, `bad_arguments`). Each frame about a call restarts its timeout; an asked approval waits for the
    user. `tool_cancel` when the result is no longer wanted; the node still answers.
  - **Events and heartbeat:** `event` (`body`, `joi`: read the HTTP call again), so a node no longer polls.
    `ping` / `pong` both ways; a connection silent for twice the heartbeat is closed.
  - **Reconnecting (1.3):** a node that lost the connection (the server restarted, the network dropped, `4008`)
    connects again after 1 s, 2 s, 4 s... up to 30 s, with `hello.resumed` and its latest manifest, so she does not
    greet the user again, then what the user said meanwhile. A turn that ran then is lost. It does not try again
    after 4001, 4002, 4003, 4004 or 4009.
    `NodeSocket` does it in joi-node; the node also closes a connection the server stopped answering on.
  - **Errors:** an `error` frame (`ApiError`, `ref` the frame) keeps the connection; close codes 4001 unauthorized,
    4002 unsupported version, 4003 replaced, 4004 no such assistant, 4008 timeout, 4009 revoked.
  - **On the server:** `api/NodeEndpoint` (Jakarta WebSocket, registered on the container once Spring is up; it
    keeps the `Authorization` of the upgrade) hands the frames to `NodeService`. A connection is a `NodeSession`
    holding its user's request scope (`ApiRequestAttributes`) for as long as it lives: its turns run in it. The
    tools of her connected nodes join every turn of hers through a `COLLECT_TOOLS` listener; a call is a
    `tool_call` and the turn waits, nothing blocked, for the `tool_result` (a failure or a denial reaches her as its
    reason). A stopped turn cancels its open calls (`tool_cancel`), and so does a turn stopped elsewhere, seen by
    the heartbeat tick, which also closes silent connections and times out calls. A node leaving fails its calls
    and stops its turn. `NodeServiceTest` drives it all through a fake transport, checking every frame against the
    schemas.
  - Not in 1.4 yet: audio in, avatar state frames, screen images (later minor versions); `event` frames are sent
    by `NodeService.notify` but nothing calls it yet.
  - Next: the JavaFX joi-node (see Node → The first node), then the conformance tests.
- **Same idea for renderers:** the node ↔ renderer state frames are documented too, so a renderer can be three.js,
  Unity, Godot or anything else.

## Code base and style

The server follows Marginalia's conventions (see its `CLAUDE.md` and `manual/developer`):

- Java 25, Vaadin Flow UI calling services directly (no REST API, no separate frontend), Spring wired in XML (no
  Spring Boot), Hibernate + SQLite + Flyway (Hibernate only validates), Maven, packaged as a WAR.
- Layering: entities in `domain.model` (`BaseEntity` → `OwnedEntity` → `ExtendableEntity`, `uuid` + soft delete),
  repository interfaces with `Jpa*Repository` implementations, service interfaces with `*ServiceImpl`, transactions via
  `@CommonTx` / `@CommonTxReadOnly`, ownership through `findForUser`.
- Style: 4 spaces, no Lombok, records for small result types, javadoc that explains intent, tests against the real
  Spring config on SQLite with contract base classes.

The node is a plain Java daemon (no Vaadin, no AspectJ weaving), the renderer is a small web app (or Unity).

**Open — sharing code with Marginalia.** Joi needs what Marginalia's `TODO.VN.md` calls layer 1, the platform: entity
base, security and users, AI / Protocol / Usage and the inference implementations, `function` / `tool` /
`structured`, GPU locks, jobs, templates, the generic UI widgets. It does not need the narrative core. Options:

1. Extract layer 1 into its own module first (smaller than the full visual-novel split, unblocks both projects; Joi
   then builds Marginalia from a pinned submodule like the VN project would).
2. Copy the code into Joi now (faster start, but every fix then lands twice and the copies drift).

The recommendation was 1; not decided. What option 1 means in practice for step 1 (the work in Marginalia, the gaps to
check) is in [MARGINALIA.md](MARGINALIA.md).

## Server

### UI (Vaadin)

- **Jois (built):** the first tab of the workspace (its icon: a hologram of a woman on a projector plate). A table of
  the user's assistants with their name and when they last chatted, Open and Delete; *Create Joi* (on the right, like
  Marginalia's *Add Book*) asks for a name, prefilled with the one of the User Settings (else the default). Open shows
  the **Joi dialog**, after Marginalia's manuscript dialog: tabs on the left, each change stored at once, Exit.
  - **About:** her name, what she calls the user, her personality, the models of the three roles, the TTS model and
    the voice actor.
  - **Animations (built):** the animations of the user's library she plays in her states on a node: asleep (it
    loops), falling asleep, waking up, and lists of idle ones (while she waits) and talking ones (while she speaks), of
    which the node plays one at a time (`AnimationRoles`, JSON on her; `JoiAnimationsPart`). A node plays them from
    her body's `.joia`, so a body that plays only some animations needs these among them; one deleted is no longer
    chosen. The API gives them with her (`JoiInfo.animations`).
  - **Templates:** her master system template, the memory prompts, the body section and the self-image prompt, with
    the preview of the system prompt.
  - **Chats (built, read only):** her conversation as a transcript, oldest at the top: the input (the user's words on
    the right, an event in the middle with its kind), her reasoning folded when she thought, her response in Markdown,
    and when, which model, which node. It opens with the last 30 messages at the bottom; scrolling to the top loads
    the 30 before, keeping the message the user reads in place (the platform's lazy edges, as in Marginalia's story;
    nothing is dropped, unlike there). A message is deleted by its trash button, or checked (shift-click checks a
    range) and deleted with the others. Editing and branching come with the conversation work. The tab follows the
    conversation while it is open (`ChatService.watch`): what is asked for shows at once, dimmed, "waiting for her
    reply" (a turn waits for the one before it), and is replaced by the exchange when her reply is stored. When her reply
    was spoken, the pieces of speech are copied into one clip as they are rendered (`ClipWriter`: wav pieces under one
    header, mp3 one after another; never all in memory), stored as a resource of the user and kept on the message
    (`ChatMessage.clipResourceId`, removed with it); the message then has a play button, and the card gets it live.
  - **Memories (built):** a grid of her memories, one row each (name, type, from you or her, created), newest first.
    A row opens into the memory: its name (only for the user, never in a prompt), its type (`MemoryImportance`), its
    keywords and its text in a growing text area, stored as typed. *Add memory* adds an approved one of medium
    importance at the top, open. Deleted by the row's trash button, or checked (shift-click checks a range) and
    deleted together.
  - **Summaries:** empty for now.
- **API access (built):** User Settings → Access lists the user's tokens and devices (created, expires, last used,
  revoke), with *Create token* and *Connect a device*; Administration → API access chooses what is offered and how long
  OAuth tokens live. See The node protocol.

  An empty field is not set: the User Settings are used, and shown greyed out (the placeholder). A change of her name,
  the user's name or her personality is an **event** for her (`QueuedEvent`, kinds `persona.renamed`,
  `persona.user_renamed`, `persona.personality`, `persona.voice`, with the old and new value; the voice's event also
  tells her how the new voice sounds, the actor's description), but only once she has chatted: before
  that it is the initial setup, nothing to react to. The event waits in the queue and is delivered when a node of hers
  is connected (at once while one is: the dispatcher comes with the nodes).
- **Conversations:** searchable list, transcript with tool calls inline (arguments, result, approved by whom or
  denied), edit / delete / branch a message. Because history can be edited, the stored history is the source of truth
  and the prompt is rebuilt from it every turn.
- **Memory:** view and edit what Joi remembers, with the source conversation of each fact.
- **Nodes:** connected and known nodes, their manifests, the policy summary each reports (read-only: the node's policy
  is the authority), pairing and revoking.
- **AI providers and persona:** endpoints, models, system prompt, voice.
- **Tools and MCP servers:** enable / disable, which node offers what.
- **Audit:** the server's view of tool calls; nodes can upload their local audit log for browsing.
- **Bodies (started):** the second tab of the workspace (a figure on a stand). For now a table of the user's 3D
  **models** (`Model`: name, format, size, added; Open, Delete) and *Add model*. Add and Open show the model dialog,
  full screen: the name, the upload and what the file is on the left, a **3D preview** on the right (three.js +
  three-vrm, the renderer's libraries, in the `<joi-model-viewer>` element; orbit with the mouse; it reports the
  triangles, meshes, materials and, for a VRM, its version and expressions). A new model is stored once it has a
  name or a file.
  - **What to upload:** a **.vrm** or **.glb** is one file with everything inside (what the converter makes). A model
    of several files (an **.fbx** with its textures, `.fbx.meta` and `.mat` files, a **.gltf** with its buffers) goes
    in one **.zip**. A VRChat avatar can come as its **.unitypackage** as bought. In a zip or a package the avatar is
    found: VRM before GLB, glTF, FBX; among FBX files the humanoids (Unity's `.meta` says `animationType: 3` with the
    bones), the largest of them (props, particles and helper rigs like GoGo Loco's are small). The preview unpacks a
    zip in the browser and finds textures by path, else by name; a package is previewed once converted.
  - **No size limit** (models are large): the upload streams into a file of the user's resources, never into memory,
    and the file is a platform `Resource` linked to the model (stored as uploaded for now). A limit set by the
    administrator can come later if needed.
  - **Conversion to VRM (built):** an FBX (alone, in a zip or in a package) is converted on the server with Blender
    and the VRM add-on (`ModelConversionService`, the script `convert_avatar.py` in joi-server's resources). Blender
    runs on the Joi server's machine: `joi.blender` / `JOI_BLENDER`, else Blender.app on macOS, else `blender` on the
    PATH (a remote converter, like ComfyUI's GPU server, can come later). One conversion at a time, in the
    background (the dialog can be closed); the dialog shows the log live and the result.
    - The upload is unpacked into a work folder (from a package only the FBX, its `.meta`, the `.mat` files and the
      textures; deleted afterwards). Unity's GUIDs resolve the files: each `.mat` gives its textures and the FBX's
      `.meta` maps each FBX material to its `.mat` (`externalObjects`); without metas the materials match by name.
      The humanoid rig comes from the `.meta` (else the add-on guesses), visemes and blink from the VRChat names,
      the moods by the names of the face keys. Spring bones are not made by the converter (see below).
    - **What is left out is the user's choice**, nothing is filtered on its own: the avatar FBX (when there are
      several), the meshes (a checklist, known from the first conversion's report: props, alternative outfits),
      shape keys to delete (names or parts of names) or to bake in (always on), and the largest texture (2048).
    - The VRM is a second resource of the model; the report (meshes, materials and how they matched, expressions,
      missing bones, warnings) and the end of the log are kept on it. The preview switches between the upload and
      the converted VRM.
  - **What it can do (built):** read from the VRM (the converted one, or an uploaded .vrm / .glb; only its glTF
    JSON, `ModelCapabilities`): the expressions that move something (moods, blink, visemes, look, custom; VRM 0.x
    names mapped to 1.0's), the toggles (every mesh, and every shape key named hide / remove / off / toggle, which
    the converter keeps for that; with the state the model comes in, from the mesh's default weights) and the
    humanoid bones with the required ones missing. The dialog lists them: an expression is a button that tries it
    on the preview, a toggle has a box that shows or hides the part on the preview and a name ("Hide Halo" →
    "halo" by default) that the user can change, stored on the model by the toggle's id (`mesh:Scarf`,
    `key:Headgear/Hide Halo`), so the names survive a new conversion. They are read again when the dialog opens.
  - **The table** also shows the bodies a model is in ("Joi: Street"); a model in a body can't be deleted.
  - **The dialogs' layout (built):** the model and body dialogs put the editing side and the 3D preview in a split
    (`ui/widgets/EditorSplit`, a Vaadin `SplitLayout`, 40 % for the editing side at first); where the user leaves
    the divider is kept in the browser per dialog (`JoiJavascriptLibrary.rememberSplit`, `localStorage`).
  - **Spring bones (built):** the bones that swing as she moves (hair, a tail, ears, cloth) are written into the
    converted VRM by Joi, not by Blender (`SpringBones`; only the VRM's JSON chunk is rewritten, the binary is copied).
    - **Guessed from the bones' names** at the first conversion: below the humanoid bones, a bone named hair / bang /
      twin / braid, tail, ears, or scarf / ribbon / skirt / cape / strap… starts a chain (`SpringChain`) with a preset
      for how it moves (hair, tail, ears, cloth, stiff: stiffness, gravity, drag, hit radius). Body parts and the face
      (breasts, butt, genitals, jiggle bones, tongue, cheeks, fingers, toes, eyes) are never guessed nor offered. A
      bone named "root" or "base" only holds others: its children start the springs. Each straight run of bones is one
      spring; a single bone has no tip to swing to and is left out.
    - **Colliders** on her head (a sphere above the head bone, in world up whatever the bone's axes), neck, chest,
      spine, hips, upper legs and upper arms (capsules to the next bone), sized by the height of her hips.
    - **The model dialog** lists the chains: a box to switch one off, its preset, its bones, removing one, starting a
      chain from another bone (a chain inside another takes that part of it), guessing again, and **Apply to the
      model**, which writes a new VRM in place of the old one. The look doesn't change, so snapshots and a description
      that were current stay current. "Sway her" turns the preview from side to side to see them move.
    - The chains are stored on the model by the bones' names, so a new conversion keeps the user's chains.
    - Only a converted VRM 1.0 gets them; an uploaded VRM keeps its own.
    - **Not from the prefab:** a VRChat package's prefabs refer to the FBX's objects by Unity's local IDs, with no
      table to their names. Meshes and materials hash as xxHash64 of `"Type:<type>-><name><n>"`, but the GameObjects,
      transforms and renderers (which the PhysBones and the starting toggles point at) use another hash we couldn't
      reproduce. The prefab is not needed: the starting toggles are the model's default state (the FBX's own weights,
      then the user's choice), and the guessed chains with presets replace the PhysBones' exact values, which don't
      carry over to VRM one to one anyway.
  - **Export and import of a processed model (built):** one file in a unified format, for easy sharing: the
    converted VRM with what was decided on it (the toggle names, the capabilities, the description and the sentences
    of the parts and the groups, the default state, the spring chains, the user's notes, the last proposal, the
    conversion settings and report). Importing it gives a ready model with no conversion. **No images:** the snapshots
    and close-ups, and the user's reference images, only serve to make the description. They are transient and
    replaceable: the importer takes new snapshots if they want to propose again, and adds their own images. So the
    file is smaller, and a store page isn't passed on. What a user shares is up to them: Joi checks no licences.
    - **Written on Exit:** the model dialog has no Save (changes are stored at once), so when it closes (Exit or Esc)
      and what a `.joim` of the model would hold changed, a progress dialog writes a new one (`JoimService`). It is a
      resource of the model, in place of the one before, and the dialog shows its size, time and a download link, or
      that it will be written again. "Changed" is a fingerprint of the metadata and the VRM's hash
      (`ModelService.exportFingerprint`), so a model with no VRM, or with a conversion running, gets none.
    - **Imported by the upload:** a `.joim` uploaded in the model dialog becomes the model (format `JOIM`): its VRM
      the converted file, what was decided on it from the metadata (`JoimMetadata`), no upload kept, and the file
      itself its current export. The same VRM bytes keep the same hash, so a description that was current stays
      current. Bodies are not in the file yet (a body points at the imported model like any other).
    - **Cleaning up files:** a converted model needs only its VRM; the upload (a Unity package of hundreds of MB)
      is read again only by a new conversion. "Clean up files" in the model dialog (with what it frees) deletes the
      upload once there is a converted VRM, and the files of older versions still on disk (VRMs, `.joim`s, snapshots
      and uploads that were replaced: soft-deleted resources linked to the model, unless another resource uses the
      file). A warning says they can't be referenced again: no new conversion and no preview of the upload until it
      is uploaded again, and no restoring an older version. The model keeps the upload's name, marked removed
      (`Model.uploadRemoved`); its `.joim` stays current, since what it holds didn't change.
    - **The file** (`.joim`, "Joi model"; also, later, the stream to a node and its cache): starts with the magic
      number `0x1DEA1A10` ("IDEAL AI 0"), then a header with the format version (a reader refuses a newer major
      version) and the profile (FULL, NODE), then sections, each with its kind, its length and its SHA-256: the
      metadata as **JSONB**, here CBOR (RFC 8949), the VRM as raw bytes. Sections are read and written as streams,
      never whole in memory, and a reader skips the kinds it doesn't know. The container is in `joi-protocol`
      (`protocol/joim/JoimFile`, `Cbor`; documented in `joi-protocol/joim.md`), so a node reads it with the same code;
      the mapping to a `Model` stays in the server.
    - **Also the delivery to a node (later):** the same container with the NODE profile: only what a renderer needs
      (the VRM, the capabilities and toggle names, the state) and none of the review material (the proposal, the
      user's notes: larger, and private). The section hashes are the node's cache keys, so a new description doesn't
      re-send the VRM, and a broken download resumes at a section. See Bodies → Storage.
- **Moods, event rules:** see below.
- **Chat:** text chat in the browser (a node without hands), useful before voice works.

### LLM providers

All major providers. Marginalia already has the OpenAI-compatible core plus Anthropic and Google Gen AI (as extension
AIs) — ported right away. Grok (xAI), Mistral, DeepSeek, OpenRouter, Ollama, LM Studio, llama.cpp and vLLM are
OpenAI-compatible, so they are covered by the same protocol. Only Bedrock / Azure / Vertex hosting differ (auth), later
if ever. Tool calling and streaming must be solid with every provider: they are central here.

### A chat turn

A turn follows the design of Marginalia's story generation, simplified and without its UI (built: `domain/chat/turn`,
run by `ChatServiceImpl`). Marginalia's agent runner is not used.

- **Steps** (`TurnStepType`), each a bean listed under the chat service in `services-config.xml`: prepare (today's
  mood, the data of her system prompt), payload (the prompt and the latest messages), tools (what she may call),
  inference (one round of the model), call tools (the calls of that round), store (the `ChatMessage` and its prompt),
  speech, cleanup. Inference and call tools loop until a round has no calls; after the platform's
  `MAX_FUNCTION_ROUNDS` the model must answer in text.
- **Everything is asynchronous.** A step hands over through callbacks and never waits on a thread. A listener holds
  the turn until it calls `chain.next()`, and a function answers through its continuation, so a node's tool or an
  approval can answer minutes later. `ChatService.chat` returns a future; the turns of one assistant still run one at
  a time. The work runs on virtual threads in the caller's request scope (its user).
- **Events** (`TurnEvents`): listeners (`addEventListener`) change the turn: the template data, the payload, the
  functions (`COLLECT_TOOLS`), a call before it runs (a listener may answer instead: a refusal, the user's answer),
  its result, the reply before it is stored, the text she speaks. They can stop it through its cancellation token.
  Observers (`addEventObserver`) only watch, synchronously; the pieces of the reply are for observers only.
  `AFTER_CLEANUP` comes for every turn, with its outcome.
- **Who hooks in:** the server's own features (her memories in the prompt, server tools such as `remember` or
  `set_mood`, the nodes' tools and approvals) and later extensions. Nothing is offered yet: `COLLECT_TOOLS` starts
  empty.

### Persona

Default stance: **the meta view.** Joi knows what she is — an AI assistant running on your server, speaking through
nodes, shown as a hologram — and does not pretend to be human. Within that she is a real character, not a robotic
helper: her own voice, opinions and humour, curiosity, moods she owns ("I rolled grumpy today"), things she likes and
dislikes, continuity through her memory.

- **Her setup is part of her world.** She knows her bodies are avatars she can switch, that the camera, outfits,
  toggles and animations are hers to use, that she has hands on some machines and not others, that some things need
  your approval — and talks about them naturally ("let me lean in", "I can't touch that folder, want to allow it?").
- **Honest about being an AI**, without disclaimers in every sentence: she doesn't invent a human life or claim
  experiences she can't have; when asked what she is, she answers straight.
- **Renaming:** "Joi" is only the default. The name is a setting of each assistant; changing it updates her prompt
  (she knows her new name) and the **wake word** with it. The rename is also an **event** in her context, like a
  toggle you change: "you were renamed from Joi to Kairo". If she already has history with you she can react to it
  in character — notice it, tease, ask why, keep a nickname — while a brand-new assistant just starts with the name.
  Old conversations keep the name she had then; memory knows both. The wake word changes (default "Hey <name>", editable on
  its own). The keyword spotter (sherpa-onnx) takes any phrase as text, no training, so the server just pushes the new
  phrase to the nodes. The UI warns about weak wake words (one syllable, common words like "Joy" → "enjoy") and has a
  test mode that counts false wakes while you talk normally.
- **Configurable:** the system prompt and persona (name, voice, personality traits, how formal, the moods and what
  they do) are editable in the UI; the meta view is the default, not a lock. The parts the app needs (tools, body
  state, mood, privacy mode, approvals) are added by the server around the user's persona text, so editing the persona
  can't break them.
- **The master system template** (Handlebars, editable, with a preview) holds the persona: who she is (the meta view),
  `{{#personalityPrompt}}`, `{{#mood}}`, how she talks, then the sections the server fills per turn:
  `{{#profile}}`, `{{#memories}}` (the recalled facts and episodes), `{{#conversations}}` (the other active ones),
  `{{#body}}`, `{{#node}}`; `{{char}}` is her name and `{{user}}` the user's. An empty section is left out. The memory's
  prompts are templates too: the conversation summary, the day summary and the facts to remember; so are the body
  section that fills `{{#body}}` and the request for a body's self-image. **Every prompt sent to a model is such a template**, resolved along the same chain (hers,
  the user's, the default), so the user can change any of them.

### Several assistants

A user can define more than one assistant — e.g. a **coding / work** assistant and a **home** one (Joi). Each has its
own name and wake word, persona and personality, bodies, moods, model roles, memory and conversations, and its own
**scope of nodes and tools** (the work assistant gets the dev machine's shell and repositories; the home one the phone,
calendar, smart home). In the data model the assistant is the owner of conversations, memory and bodies (all under the
user).

**The data model (built, no UI or inference yet).** All owned by the user, each with its assistant:

| Entity | Table | What it is |
|---|---|---|
| `Joi` | `assistants` | An assistant. Her **template attributes** (`PersonaAttributes`: her name, what she calls the user, the prompts, the personality, the moods, the models of the roles, the voice, the day boundary, the pause), the wake word and today's mood are all extended content. They are the first link of the chain of a setting: a blank one falls back to the user's setting, then to `JoiDefaults` (`JoiPromptField.resolve(joi, setting, defaults)`). |
| `ChatMessage` | `chat_messages` | One exchange, like a part of Marginalia: the input, of type **User** (what you said or typed) or **Event** (what happened), and her response with its **reasoning**, the tool calls, the model, the tokens and the timings. Columns: `sentAt` (what summaries cover), `pinned`, `privateMode`. |
| `MessagePrompt` | `message_prompts` | The prompt sent for a message, deflated, at most one per message (as in Marginalia), with the memories and summaries that were in it. |
| `Summary` | `summaries` | A summary of a time, see Memory. |
| `Memory` | `memories` | A fact, with its importance: `IMPORTANT` (always recalled when it matches), `MEDIUM`, `TRIVIAL` (recalled last, the first to go). Keywords, the message it came from, approved or not, private or not. |
| `JoiTask`, `JoiTaskMessage` | `joi_tasks`, `joi_task_messages` | A task she started and its transcript, see Model roles. |
| `QueuedEvent` | `queued_events` | An event that waits for a connected node, see Events. |
| `Body` | `bodies` | A body of hers, made of the user's models (`Model`, `models`), see Bodies. |

Conversations as units (live, active, records) are not an entity yet: the messages are one timeline per assistant, and
the summaries cover times of it.

**Interaction between them** (same user only), opt-in per pair:

- An assistant can **ask another one** through a tool (`ask_assistant(name, question)`): the other answers from its own
  memory and search, in its own voice, within what it is allowed to share ("what was I working on today?" asked of
  Joi, answered by the work assistant).
- **Sharing rules** per pair: nothing, summaries only, facts with certain tags, or full search. Privacy-mode content is
  never shared. Each assistant's tools stay its own; asking another assistant never borrows its tools — the other one
  acts itself (and its harness still applies) if it decides to.
- **Hand-offs:** "tell the work one to remind me about the review" → a message lands in the other assistant's queue.
- Later, for fun: both on screen, talking to each other.
- **Snooping (idea):** an assistant can receive the events of another one, opt-in per pair like the sharing rules.
  The home Joi hears that the work assistant was renamed, got a new body or finished a task ("so you gave Kairo a new
  outfit, hm?"), and can react to it in character. The events are copies, marked with the assistant they came from;
  private ones are never passed on.

### Personality (separate from mood)

**Personality** is who she is; **mood** is how she is today. The personality is a stable setting, the mood varies
within it.

- **Presets:** neutral assistant (professional, minimal persona), warm companion, cheerful, sarcastic, kuudere (cool,
  dry), dandere (shy), tsundere (prickly on the surface, caring underneath), yandere-lite (clingy, playfully jealous) —
  and **custom** ones the user writes.
- **Built (defaults and settings):** the presets above are built-in defaults by key (`neutral`, `warm`, `cheerful`,
  `sarcastic`, `kuudere`, `dandere`, `tsundere`, `yandere`), and so are the moods (`calm`, `happy`, `playful`,
  `curious`, `affectionate`, `focused`, `sleepy`, `grumpy`, `melancholic`, `excited`, each with a roll weight and a
  voice delivery). Every setting follows Marginalia's chain without the folders: the assistant's own value (once
  assistants exist), else the user's setting, else the built-in default (`JoiDefaults`, per prompt language, English
  for now). Under **User Settings** a user changes a built-in personality or mood (an empty field keeps the default, a
  reset brings it back) or adds their own, picks the personality, the models of the roles, the voice, and edits the
  prompts. The user's name (`{{user}}`) follows a chain too: the setting, else the name of the account. Unsaved
  changes on the page freeze the other parts until they are saved or discarded, as in Marginalia. The summaries have
  no word limit: their length follows the conversation (a long, deep talk gets a long summary). A personality says how some moods sound in it; a mood it says nothing about uses the mood's own text.
- A personality is a prompt fragment plus how the moods express themselves in it (a tsundere's "happy" sounds
  different from a cheerful one's), the mood weights for the daily roll, default expressions / idle animations, and
  voice parameters. Switchable any time (UI, voice, Stream Deck), per persona.
- **Flavour, not behaviour.** A personality changes tone and banter only. It never changes what she does: she doesn't
  refuse approvals, sulk by withholding help, act against your interests or the harness. Jealousy or spite stay
  playful in-character teasing, never real manipulation (no guilt-tripping you about other people or time away).
  **One exception, and the user's own choice:** when the user asks to change a part of her **body** (take off the
  visor), a personality that has a *part consent* text (blank by default, in none of the built-in ones) lets her
  decide, and she may refuse in character (see Her parts, 1.5). It covers her body's looks only: never a tool, an
  approval, a task or help, and a failed call or no answer means allowed.
- **Cloning.** A personality (built-in or the user's own) is cloned from its row in User Settings: the copy is the
  user's own, as the personality is now (a changed built-in one with its changes), under a new key (`tsundere-copy`)
  and opens to be edited, so a built-in one can be varied without losing it.
- **Model limits:** strongly negative archetypes (spiteful, possessive) are softened or refused by many hosted models;
  local models follow them more closely. Expect a hosted model to play a gentler yandere.

### Model roles

At least two text models, each configured in the UI (any provider):

- **Conversation model** — what Joi talks with: fast first token, short turns, persona and mood, simple tools
  (toggles, expressions, timers, quick lookups). Latency matters most here because speech waits for it. Locally it is
  the lighter / more quantized model that stays loaded next to the TTS model.
- **Heavy-duty model** — strong reasoning and long tool loops: multi-step tasks on the node (files, shell, code),
  research, planning, interpreting a new body (multimodal), memory consolidation and summaries. Slower; may be a
  remote API or a big local model that takes the GPU in turns (as in Marginalia).

The conversation model hands work to the heavy one through a tool (`delegate` with the task and context). The task runs
in the background: Joi says she is on it, keeps talking, and reports back (in her own voice, through the conversation
model) when it finishes or needs an approval.

**Tasks after the reply (designed; the entities are built: `JoiTask`, `JoiTaskMessage`).** A tool call of Joi can start
a task, a call to inference of its own (`delegate` to the heavy model, research, a summary). The task does not start
during the reply: it waits (`QUEUED`) until the message that started it is finished, spoken included. Then it runs, and
its transcript (the instructions, the model's turns with their reasoning and tool calls, the tool results) is stored as
task messages. This matters most with a single inference server or a single GPU that swaps models: the reply holds the
conversation model and the TTS model, and only when she has said "I'm on it" does the GPU switch to the heavy model.
Meanwhile the avatar takes a **thinking pose**, so it is visible that she is working. When the task ends, its result
comes back to the conversation as an event message (`resultMessage`), and she reports it in her own voice. The speech
of that report is the full synthesis again (text and voice); it may be one task that returns both at once, so the GPU
switches back only once. States: `QUEUED`, `RUNNING`, `WAITING` (an approval), `DONE`, `FAILED`, `CANCELLED`. Smaller roles — salience scoring of events, short classifications — can
use the conversation model or a third cheap one.

In the UI each text model is marked for what it may be used for (`TextLLMType`, several allowed): **Chat** (the
conversation role), **Deep reasoning** (the heavy role) and **Auxiliary** (the smaller jobs). A role picks its model
from the ones marked for it. Whether a model takes images is the model's own **Multimodal** flag.

**During development** (prices as of 2026-11, per 1M tokens in / out):

| Role | Dev default | Why |
|---|---|---|
| Conversation | Claude Haiku 5.5 (`claude-haiku-5-5`, $0.10 / $0.50) | fast and nearly free, so iterating on persona, moods and tools costs almost nothing |
| Heavy | Claude Sonnet 5.5 (`claude-sonnet-5-5`, $2 / $10) | reliable long tool loops, multimodal for the body import |
| Hard cases, now and then | Claude Opus 5.5 (`claude-opus-5-5`, $4 / $20) | to check whether a failure is the model or our prompts / tools |

- **Local conversation model, measured 2026-11-17:** Gemma 4 12B Q6_K (+ mmproj) on the R9700 (llama.cpp b11977):
  44 tok/s, two parallel tool calls right on the first try, vision OK. Thinking must be **off** for the conversation
  role (`chat_template_kwargs: {enable_thinking: false}`): a short reply took 2 s instead of 10 s, with the same tool
  calls. With the MTP draft head (`model-draft` + `spec-type = draft-mtp`; without the type it fails with "requires
  state embedding") generation goes from 44 to about 59 tok/s on free text (38 % of drafts accepted) and to about
  100 tok/s on tool calls (89 %), with the same answers and tool calls: use the MTP + mmproj preset. With thinking on
  and MTP, a short reply takes 4.4 s (about 270 thinking tokens, so about 3 s of silence before the first word) and the
  two tool calls 2.4 s: usable, but thinking stays off for the conversation role and the heavy role (or `delegate`)
  does the thinking.
  Gemma 4 31B Q6_K (the heavy-role candidate) gains as much: 20 → about 33 tok/s on free text and 53 tok/s on tool
  calls with MTP (40 % / 89 % accepted), tool calls the same.
- **Local stays in the loop:** a quantized local model is tested on purpose (a nightly run or before a release), since
  that's what the local setup ships with and weaker models break tool calls first.
- **Most tests use no LLM at all:** a scripted fake provider that returns canned replies and tool calls drives the tool
  loop, the harness, events and memory in unit tests. Recorded real replies (record / replay) cover the provider
  adapters.
- **A small eval set** (persona consistency, the right tool for a request, toggles both ways, `delegate`) runs against
  each model, so switching the dev default, or a user picking a model, is a measured choice.
- Prompt caching on the long, stable part (persona, tool definitions, body description) keeps the remote cost down
  further.

### Privacy mode

A per-conversation switch for when the topic should not leave the house while an external provider is configured:

- **Quick to turn on:** a button in the UI, a voice command ("private mode"), a Stream Deck key. Joi shows it — a HUD
  lock icon and a change in the avatar (e.g. a different rim colour) — so it is always visible which mode is on.
- **Everything goes local:** every model role of that conversation uses the local models (the heavy role falls back to
  a local model too, or `delegate` is off); speech-to-text and TTS stay local; tools that send data out (web search,
  external MCP servers, external APIs) are off or always ASK.
- **Afterwards:** the conversation is summarized by the local model, or not summarized at all — your choice per
  conversation (or a default). Facts pulled from it are tagged **private**.
- **It stays private later:** private conversations, their summaries and private facts are only put into prompts that
  go to local models; they are left out of day / week roll-ups and of search results while an external model is in use.
- **Switching mid-conversation** applies from that point on: what was already sent to an external provider can't be
  taken back, so the UI offers to start a fresh private thread instead. Each turn records which provider saw it.

### Memory

1. **Facts** — short, editable entries with their source, activated by keywords or embeddings (Marginalia's lorebook
   activation is most of this already). Joi writes them through a `remember` tool; you can approve / edit them in the UI.
2. **Episodes** — summaries of past conversations (like Marginalia's summary chain), searchable.
3. **Profile** — a small always-in-prompt block about the user.
   - **How the user looks (idea):** the user can upload photos of themselves, or Joi can ask for one ("I don't know
     what you look like, would you show me?"). A multimodal model describes them in text, like a body's self-image:
     the user reviews the description, and it goes into the profile (and memory), so a text-only chat model knows it
     too. The photos stay on the server, private to the user; only the reviewed text is used in prompts. The request
     is a prompt template on the chain like every other prompt. Later, a node's camera can take the photo when asked.

**Conversations** are separate units: one is **live** (the prompt is built from it), the older ones are **records**.
In between, several can be **active** at once:

- **Active** conversations are the open threads of the moment — the same day, or a topic still in progress (the GPU
  setup, a trip being planned). They are not closed by a pause; they keep their recent turns and running summary and
  can become live again.
- **Routing:** each new utterance goes to the active conversation it belongs to — the conversation model decides from
  the content ("back to the GPU thing", a follow-up question) — or opens a new one. You can also switch or merge
  threads in the UI. A thread is not tied to a node: started on the phone, continued at the desk.
- The live conversation's prompt also carries short summaries of the other active ones, so Joi knows what else is
  open ("we also still have the flight to sort out").
- An active conversation becomes a **record** at the day boundary or after a longer inactivity (configurable), or when
  you or Joi mark it done.

- **After a pause** (e.g. 30–60 min without interaction) the live conversation steps back to active; when it becomes
  a record, the heavy model, in the background, writes its summary and pulls out facts for memory. Pauses and the day
  boundary are the natural seams, so they are the main triggers.
- **Records are history, not context.** Editing a closed conversation only changes the record; its summary is not
  rewritten unless you ask ("re-summarize" in the UI, optional).
- **Recall by search:** Joi gets a tool to search past conversations (full text + semantic over turns and summaries,
  filters by date / node / topic) and pulls in what was said when it matters — "what did we decide about the GPU last
  week?" — instead of carrying old conversations in the prompt. Results come with the conversation and date, so she
  can cite them and you can open them in the UI.

**Compaction** (summaries replace raw turns in the prompt; the raw history stays in the database, browsable in the
UI — nothing is deleted):
- **At the day boundary** (the same one the daily mood roll uses, e.g. 4:00) the day's conversations roll up into a **day
  summary**; days roll up into weeks, weeks into months, months into years.
- **A summary covers a time, not a list of messages** (built: `Summary`): it has two timestamps, `coverFrom` and
  `coverTo`, and a type (`ROLLING`, `DAY`, `WEEK`, `MONTH`, `YEAR`). A longer summary is the shorter ones of its time
  run through a prompt of its own (a week from its days). Once it exists it covers them: the prompt takes the longest
  summary of a time and skips the shorter ones inside it (`SummaryCoverage`), so a week shows as one week, not as seven
  days. Summaries of the same type never hide each other.
- **When the prompt nears the context budget** in the middle of a long conversation, the oldest raw turns of the
  live conversation are summarized (rolling), so a long session never hits the limit.
- The prompt is then: profile + relevant facts + today's conversation summaries (+ yesterday's day summary) + the live
  conversation's recent raw turns; anything older comes in through the search tool.
- Editing the **live** conversation changes what the next prompt is built from. A turn can be **pinned** to stay
  verbatim (a promise, an exact instruction).

### Moods

- A `Mood` has a prompt fragment (tone), TTS parameters (voice, speed, emotion where the model supports it), avatar
  parameters (expression weights — the ARKit face shapes make this portable across bodies —, rim light colour, glitch
  level) and a weight for the random roll.
- **Rolled at random at the first interaction of the day** (after a configurable day boundary, e.g. 4:00, so a late night
  still counts as the same day). During the day it changes when you switch it in the UI — pushed to the nodes in real
  time; the avatar transitions at once, speech at the next sentence, and Joi can react to the change — or through the
  conversation / Joi herself (a `set_mood` tool). Every change is logged.
- Open: whether the conversation should tilt the mood or only the UI and Joi's own tool change it.

### Bodies (avatars)

- A **Body** is a model file plus its **capabilities** (expressions, visemes, animations, outfit toggles), read from
  the model itself, and an **appearance description**.
- **Built:** `Body` (table `bodies`) belongs to an assistant and points to the user's models: the full one and,
  optionally, a lighter one for the Looking Glass (else the full one is shown there too). Its name is an extended
  attribute; what it can do and how she looks in it are its model's (`ModelCapabilities`, the toggle names, the
  description). One body of hers is **active** (a column): the first one she gets, then the one she is put into. In her
  dialog the **Bodies** tab lists them (her body now, name, the models; put her into one, Open, Delete); the body
  dialog (full screen) has the name, the two models (only models with a VRM: converted, or uploaded as .vrm / .glb),
  how she looks, and the 3D preview of either model.
  - **She is told** (a `body.switched` event, like the persona changes, once she has chatted): when she gets her
    first body, is put into another one, or loses hers (the active body deleted). The text has how she looks now.
  - **How she looks belongs to the model (built):** the appearance is the model's, not the body's: every body of
    a model, of any assistant, looks the same, and the export carries it. The body dialog shows it read only, put
    together for the body's state (`Appearance`); it is described and edited in the model dialog.
  - **Self-image (built):** in the model dialog, once the model has a VRM. *Take snapshots* renders, as the preview shows
    the VRM (`takeSnapshots` of `<joi-model-viewer>`, lit from the camera): six shots of the whole model (full body
    front, three-quarter, profile and back, the face front and three-quarter; 768 × 1024 JPEGs on a plain grey
    background, framed by the model's size and the VRM's head bone), in the model's default state, and for each
    part **described apart** a close-up per state (768 × 768, framed on the part: its meshes, or the vertices its shape
    keys move, seen from the side it is on; a shape key toggle's mesh is shown for its close-ups even when the mesh is
    off by default, or both states would show nothing). Each goes to the server like an upload; they are image resources of the user linked to
    the model, with the hash of its VRM, and a new set replaces the old one. Which toggles are described apart is a
    box (*Describe*) on each toggle row; until the user picks, the shape key toggles ("Hide Halo") are. A second box,
    *Auto description*, marks the described toggles that are **layers of her look** (the bodysuit, the scarf, the
    halo; not the NSFW parts, which change the model in many places, nor her eyes). The close-ups start from the
    default state and switch only the part (with what it is part of and what is part of it on); then the layers go
    off, and the shots of the whole model are of that **bare state** (`Model.bareShown`: the default with the layers
    off; the default itself when none is marked). Her description is of the bare model, and each layer worn adds its
    sentence (`Appearance`), so it is built from the most bare to the most dressed. The user adds
    **their images** (PNG, JPEG, WebP: store pages, photos) and **notes** (what a toggle is, what the images can't
    show).
    - *Propose a description* (`AppearanceService`, in the background, one at a time; images larger than 1568 px
      scaled down) goes to the user's deep reasoning model when it takes images, else their chat model. First the
      whole model (the shots, the user's images, the notes, the toggles numbered: the appearance prompt): the
      description of the model **bare** (the described parts as the renders show them), and names for the toggles, by number
      (small models garble ids; an id or a "mesh / key" is matched too). Then **each described toggle** with its two
      close-ups (the part prompt, `PartImageTemplateData`): a sentence per state, the base one (marked default) as it is ("A black
      visor with red markings covers your eyes.") and the others as what changed ("The visor is gone: your eyes are
      pale grey."). The dialog shows the progress. Both
      prompts are templates on the chain. Proposing uses the snapshots there are; only *Take snapshots* renders new
      ones.
    - The user **reviews** it (`AppearanceProposal`, kept on the model): edits the description, keeps or drops each
      new name and each part's sentences, then *Use it* or *Discard*. The description and the sentences stay
      editable on the model afterwards. They remember the hash they were made from: when the model changes, the
      dialog says the snapshots and the description are out of date. Using a new description is an event
      (`body.described`) for each assistant in a body of that model.
    - **Her look now** is the description plus the sentence of each part off its default (`Appearance`):
      in her prompt, in the event of a switch, and later the answer of a `look_at_yourself` tool and the text of a
      toggle event ("You took off your visor: your eyes are pale grey."), so she can react to it. The state is the
      body's (see *The body state* below).
    - **The default state (built):** the first box of each toggle row in the model dialog says whether the part is
      shown by default (stored on the model; a toggle not set is as the model comes). The preview shows the model so,
      the shots are taken so (each close-up switches only its part, the rest stays as by default), and a new body
      will come so. Changing it calls for new snapshots.
    - **Groups (built):** in the model dialog's *Groups*, the user groups toggles and names the states of a group
      ("eyes": "blindfold" with the band shown, "bare" with the band hidden and the iris and pupil shown), each with a
      box per toggle and a button that shows it on the preview (`ToggleGroup`, `ui/bodies/ToggleGroupsPanel`). Each
      state gets a close-up of the whole group (framed on all its toggles) and a sentence, so parts that only make
      sense together are described together and the user says what covers what. A toggle is in one group at most and
      is described with it; a described toggle in no group is a part of its own, with the states shown and hidden.
      These are the **parts described apart** (`DescribedPart`), and only they can be switched. A group has a
      **default state** the user marks (the editor asks for it); its toggles' defaults come from it (their own boxes
      lock). The least covered state reads best as the default ("bare", so the blindfold is what changes: "A black
      blindfold covers your eyes." added to a description of her eyes, not "the blindfold is gone" after a
      description that has it). Until one is marked the shots take the toggles' own defaults and every state's
      sentence is added. The part prompt
      gets the states numbered in the order of the images, the default marked, and answers a sentence per number.
    - **The tree of toggles (built):** a toggle can be **part of** another (`ToggleTree`): a shape key is part of the
      toggle of its mesh unless the user says otherwise (the panties under the bodysuit: a shape key's mesh is in the
      VRM, so it is known with no extra data). In the model dialog each toggle row has *Part of* (rows are indented
      under their parent); what is changed from the inferred is stored on the model (`toggleParents`, the toggle's id →
      its parent's id, "" for none; in the `.joim`'s metadata). A toggle's **own state** is never changed by its parent:
      what is seen is its **effective** state, shown only while every toggle above it is shown, so a parent put back
      shows its parts as they were. The renderer applies it (`BodyToggle.parent` in the API, 1.5; the server keeps
      each toggle's own state), the snapshots too: a part's close-ups have what it is part of shown (the panties in
      the bodysuit, though that is hidden by default) and what is part of it (the bodysuit with all its options), unless
      the part sets it itself, as a group does. The rest of the body is in the default state, so the default state
      should have everything on that she can wear. Her look
      leaves out the sentence of a part under a hidden one (the hidden one's sentence covers it). Switching a part
      is **one event**: the part, its state's sentence, and what went with it: "With it, golden halo is gone from
      sight too." (the parts under it now out of sight), "... is in sight again.", or, for a part switched while what
      it is under is hidden, "It is not seen while hat is hidden." Not built: reading the tree from the original
      (the prefab's hierarchy, the toggle clips and menus of a VRChat package, which also give groups and default
      states).
    - **The default is the baseline.** The shots show the model by default, so the description of what never
      changes has each part in its default state (the blindfold on, if that is the default). A part's other states
      are **news**, what changed ("The blindfold is gone: your eyes are vibrant red."). Her look is the description
      plus the sentence of each part **not** in its default state: the state it is in, matching, else the nearest
      (the fewest toggles off). Changing a default moves the baseline, and calls for new snapshots and a new
      description.
      Outfits are the same idea for the whole body, so the editor can serve both.
    - Later: which expressions fit which mood, and outfits (sets of toggles) described as a whole.
  - **Her prompt** (`{{#body}}`, the body section: a template on the chain like every prompt, `BodyTemplateData`) says
    the body she is in, how she looks, the expressions she can show (moods and
    custom ones), the parts she can switch (only the parts described apart) with the names the user gave and the
    state they are in (a group's other states too), and
    her other bodies. Her tools to change them (`toggle_part`, `look_at_yourself`) come with the tool loop.
- **Importing a body** is a guided step in the UI. Next to the model file the user gives what they have: their own
  description, screenshots (the creator's store page, in-world photos, their own renders), notes on what each toggle /
  outfit is. A multimodal LLM interprets all of it together with snapshots the renderer takes (front, three-quarter,
  profile, face, each outfit) and proposes the appearance description, names for the outfits and toggles ("Hide Halo"
  → "halo"), and which expressions fit which mood. The user reviews and edits the proposal before the body is saved.
- **Self-image:** the result is the model's appearance description (what never changes, and each toggle described
  apart, shown and hidden), editable in the UI and tied to a hash of the model; when the model changes, the snapshots are retaken and the LLM proposes an update to review. Joi gets
  the active body's description in her prompt (she knows how she looks), its capabilities as tools (`play_animation`,
  `set_expression`, `toggle_outfit`, `look_at`), and the list of her other bodies (she or you can switch).
- **Toggles work both ways.** The body's state (active body, outfit parts, eyepatch up / down, halo, effects,
  expression overrides) lives on the server, and both sides change it through the same path:
  - **The user** toggles from the UI, a Stream Deck key or by voice → the server updates the state, the renderer
    shows it, and **Joi is told** (an event in her context: "you put your eyepatch up", "you were switched to the street
    outfit"), so she can react to it — comment, play along, or object and change it back.
  - **Joi** changes it herself with tools (`toggle_outfit`, `set_expression`, `switch_body`…) when she decides to — to
    match her mood, the conversation or a request — and the same state update reaches every display and the UI.
  The renderer never owns the state; it only shows it.
  - **The body state (built, the user's side):** a body keeps the state of each part described apart that is off its
    default (`BodyState`, JSON on the body: the part's id → the state's id; a part not in it, or whose state is gone,
    is in its default; the toggles in no part stay as by default). Another model on the body starts as it comes. The
    body dialog's *Her parts* has a choice per part (a group's states, or shown / hidden; the default marked); a switch
    is stored (`BodyService.switchPart`), the preview and her look follow, and when she is in the body and has chatted
    she is told (`body.part_switched`: "A part of you was switched: eyes, now "blindfold"." and the state's sentence).
    Her look (`Appearance`) and the parts in her prompt follow the body's state. The Looking Glass model is shown as it
    comes for now. The node shows the state (the renderer page sets the toggles, on the pane and on the Looking Glass)
    and switches it: its **controls panel** (`fx/ControlsPane`, between the chat and her body, a panel for the node's
    controls in general, in sections; the first is *Her parts*: a box for a part that is shown or hidden, a choice
    for a group's states) sends `part` (1.5) staged as above, disabled while the node is not connected, and locked while a batch waits. A switch made in the server's
    UI does not reach a node that is already running yet (it shows it at its next start). Next: her tools. Toggles come from the model's capabilities (its meshes and
  "hide X" shape keys, kept by the converter), but **only the described parts can be toggled** (the *Describe* box
  in the model's dialog): she can't change what she has no words for, and every toggle changes her look. The rest
  stay as the model comes, and are only tried on the preview. Her prompt and her tools list only the described parts.
- **Outfits** follow the same rule. A body can have several outfits (named sets of toggles, e.g. "street", "armor",
  "casual") and removable pieces (scarf, gloves, shoes, accessories); some models ship alternative clothing meshes or
  "hide" keys for this. Outfits are defined per body (detected from the model where possible, named and edited in the
  UI). You switch or take off a piece and Joi is told and reacts; Joi switches or changes pieces herself with tools
  (`set_outfit`, `toggle_outfit`) — e.g. to fit the time of day, the weather, her mood or what you are doing — and
  her appearance description follows the outfit she is wearing.
- **Accessories** can also live **outside** the body: separate small models (glasses, hats, a scarf, a headset,
  earrings, a held mug or book, a pet on the shoulder) in an **accessory library** on the server, shared by all bodies,
  like animations.
  - **Attached to bones:** an accessory is parented to a humanoid bone (head, neck, chest, a hand, upper arm…) with an
    offset, rotation and scale. Because every body has a humanoid rig, one accessory fits all bodies. Each body can keep
    its own fit, since heads differ in size. Several attach points are possible (left / right hand, head / back).
  - **Rigid or skinned:** rigid items (glasses, hats, held props) just follow their bone. Clothing that has to bend
    (a jacket, a skirt) needs skinning to the body's skeleton. That works when it was made for that rig (same bone names
    as VRChat "outfit for avatar X" packages), and is merged at conversion time. Rigid first; skinned clothing later,
    per body.
  - **Import:** GLB / FBX / VRM, converted on the server like bodies (textures downscaled, MToon or the renderer's
    material). The same guided step as for bodies: screenshots and notes → the LLM proposes a name, a description
    ("round gold-rimmed glasses") and the default bone, and the user reviews it.
  - **Fitting editor (optional):** a small WebGL view in the Vaadin UI (three.js + three-vrm, the same code as the
    renderer). It shows the body with the accessory, with a transform gizmo to move, rotate and scale it, a bone picker,
    snapping to the attach point, and a mirror for left / right. It previews the camera shots and outfits, and checks a
    few poses from the animation library so it doesn't clip through the face. The fit is saved per body + accessory.
    Without the editor, the default fit from import is used.
  - **Hiding:** an accessory can hide parts of the body while worn (the body's "hide hair" key under a hat), set in the
    fit.
  - **Both ways, like toggles and outfits:** you put one on from the UI or a Stream Deck key and Joi is told; Joi
    puts them on and takes them off with tools (`wear_accessory`, `remove_accessory`, `hold_item`), e.g. reading
    glasses when you look at code, an umbrella when it rains. An outfit can include accessories. Her appearance
    description includes what she is wearing.
  - **Delivery:** accessories are stored and cached by hash like bodies and sent separately, so adding one doesn't
    re-download the whole body. The renderer attaches them at runtime.
  - Adding one to the library is an event ("new glasses are available"), staged until the user saves.
- **Animations** live in an **animation library** on the server, shared by all bodies: because every body has a
  humanoid rig (VRM humanoid / Unity Humanoid), a clip made for one plays on all of them (retargeting).
  - **Built so far:** the user's **library of clips** (`Animation`: a VRMA file as a resource of the user, with its
    name, description and tags, what was read from the file — the length, the bones it moves, whether it moves
    expressions; `VrmaFiles` reads only the JSON chunk — and the FBX or BVH and take it was converted from) and
    **animations** (`AnimationPack`, an "animation" in the UI): what she plays. A clip alone does nothing. An
    animation is a name, a description, its **steps** in order and whether the whole animation loops, and the body it
    is previewed on. A step (`AnimationStep`, JSON on the animation) is a clip and how it plays there: whether it
    loops, its speed, in place, whether it plays **reversed** (the clip's keyframes turned around in the viewer:
    sitting down played back is standing up, so one clip serves both), its **rotation** (a quaternion: the whole clip
    turned around her hips where the step starts, for a clip made facing or lying another way; set in degrees around
    X, Y, Z, in three.js's YXZ order, `StepRotation`, or with rotate rings on the hips in the preview,
    TransformControls) and its **blend in** (seconds of crossfade from the last frame of the step before: two clips
    whose poses don't meet, lying flat and then getting up half-sitting, flow into each other). These belong to the
    step, stored on the animation; the clip is never changed. A looping step repeats until the animation is **finished** (her state changes, she or
    the user stops it), then the steps after it play once: "jumping jacks" is the start once, the jumps looping, the
    stop once. A looping animation starts again after its last step until it is finished: clips that don't loop on
    their own, a wave and a nod, again and again. The clip's old loop, speed and in-place are only what a new step
    of it starts from. Two tabs: **Clips** uploads clips and lists the library (`AnimationsPart`; `AnimationDialog`
    edits a clip and plays it in a loop; a clip's **New animation** button makes an animation of it, named after it);
    **Animations** (`AnimationPacksPart`, `AnimationPackDialog`) edits the steps in a table (loop, speed, in place, reverse, blend in; the rotation of the step chosen under it,
    moved up and down) and plays the animation on open and on Play, Finish ending a looping step; a step chosen in the
    table or in the strip of steps under the preview (`ClipPreview`, the step playing marked, with its rounds) plays
    alone. Both tables have checkboxes and Delete selected. A clip added to an animation appends its description to
    the animation's, on a line of its own. A clip can be in several animations and steps; deleting an animation keeps
    its clips, deleting a clip takes it out of its animations. Clips play in the three.js preview on any body of her
    assistants, in the state of its parts (`@pixiv/three-vrm-animation` retargets them; `joi-model-viewer.js`
    `playSequence` plays the steps, cut from one to the next with no blending yet).
  - **Export and import (`.joia`, "Joi animations"):** Export selected in the Animations tab downloads the checked
    animations with the clips they play, each clip once, as one file (`JoiaService`; the container
    `protocol/joia/JoiaFile` and its metadata `JoiaMetadata` are in `joi-protocol`, documented in `joia.md`). Import
    .joia adds the file's animations as new ones. Clips are linked by uuid: a clip the user already has is linked and
    its copy skipped; any other is added to the library keeping its uuid, so importing the same file again links it
    (a uuid of one of the user's deleted clips gets a new one). A uuid of another user's clip refuses the whole file
    before anything is written: a file can't reach another user's clips.
  - **FBX and BVH** are converted on the server (`AnimationConversionService`, `convert_animation.py`, headless
    Blender, one Blender at a time with the models' conversions: `Blender`): the VRM add-on maps the bones to the
    humanoid (it knows Mixamo, Unreal, Biped, VRoid, MMD... and guesses from the skeleton's shape) and exports each
    take as a VRMA against a T-pose. Hips that rest at the floor (most BVH) get the first frame's position as their
    rest, since a player scales the hips' movement by their rest height. The rest pose matters: an FBX without a mesh
    has no bind pose, so its rest is whatever pose it was written in; when that is not a T-pose (or close, like an
    A-pose), the add-on straightens the limbs by direction only and a twist of the forearm is lost. Still to come:
    the humanoid clips of VRChat packages (Unity `.anim`, in muscle space), layers, groups, defaults per state,
    bodies paired with clips, descriptions by a model, the node's cache of clips.
  - **Import:** VRMA (VRM Animation), FBX (e.g. Mixamo), BVH, and the Humanoid clips found in VRChat packages (emotes,
    dances); converted on the server like bodies. Each clip gets a name, tags and a short description (wave, nod, shrug,
    jump, dance, idle variants), proposed by the LLM from a preview and reviewed by the user. Bodies can also bring
    clips of their own (their toggle / effect animations).
  - **Layers:** a base idle loop (several, chosen by mood), gestures on top (upper body masked, so a wave doesn't
    stop the breathing), additive head motion, facial expressions and lip sync separately. Small motions — nodding,
    shaking the head, looking at you or away — are procedural and need no clip.
  - **Both ways:** Joi plays them with a tool (`play_animation`, listing what fits the active body and shot), e.g. waves
    hello, shrugs, dances when asked; you trigger them from the UI or a Stream Deck key and she is told.
  - Clips that leave the frame (a jump, a full-body dance) ask for a wider shot, so the camera follows.
  - **Animation viewer and editor:** a dialog like the model's, with the clip played on a chosen body in the three.js
    preview (play, pause, scrub, loop, speed, the camera presets). Its edits are kept on the clip, never in the file:
    the name, the description, the tags, a trim (start, end, loop points), the speed, and which layer it plays on
    (whole body, upper body, additive).
    - **Descriptions:** each clip has a short description of what it shows and when it fits ("waves with the right
      hand, a friendly hello"), proposed by a multimodal model from frames of the preview (the same way as the
      self-image: a template on the chain, reviewed, editable) and written for her: she picks a clip by what it
      says.
    - **Groups (later):** the user groups animations (not clips: she plays animations) and describes the group
      ("greetings", "happy idles", "thinking"). She sees the groups and their descriptions first, and the animations
      of a group when she needs them, so a big library doesn't fill her prompt. An animation can be in several
      groups. Playing a group plays one of its animations, picked at random or by mood, so a repeated wave isn't
      always the same.
  - **Bodies are paired with animations:** a body has the clips and groups it can play (all of the library by
    default; the user removes what doesn't fit it, e.g. a clip that clips through a big tail or a skirt, and adds the
    body's own clips). **Built:** the body dialog's *Her animations*: a box for all of the library (the default, also
    animations added later; stored as none chosen) or a choice of the user's animations (`Body.animations`, the uuids,
    `domain/bodies/BodyAnimations`; `BodyService.animations`). On **Exit**, when they changed (a fingerprint of what
    the file holds, as for a `.joim`), the dialog writes them with their clips into the body's **`.joia`**, a resource
    of the user linked to the body, in place of the one before (`JoiaService.exportBody`); a body with no animations
    has none. The dialog shows the file with a download, and says when it is out of date. The API serves it. Her prompt and `play_animation` list only the clips and groups of the active body, and a
    switch of body changes the list. Adding a clip or a group to a body is an event, staged like the rest.
  - **Default animations, per state:** the user sets which clip or group plays for each of her states: **idle**,
    **listening**, **thinking** (while the model works), **talking**, **waiting** for a tool, **sleeping** (privacy
    mode, the night), and the start and end of a conversation. The library has defaults, a body can override
    them (her idle in one body isn't the same as in another), and moods can override them too (a tired idle). The
    renderer plays the default of the current state; a clip she plays herself goes on top and then gives way to it.
  - **Storage and delivery:** a clip is a VRMA file plus its **metadata**, a JSONB document of its own (the
    animation's schema, versioned like the container): the name, the description, the tags, the trim and loop
    points, the speed, the layer, its groups, where it came from (Mixamo, a VRChat package, the user's own), what
    was read from the file (duration, frame rate, the bones it moves, root motion removed or not), and the hash of
    the VRMA. Groups, a body's clips and the default per state are JSONB documents
    too. Frames of the preview made for the description are transient like a model's snapshots and are never stored
    in a bundle.
    - **Cached one by one, sent as a bundle:** a node caches each clip (its VRMA and its metadata) by hash, so an
      edited description or trim re-sends a few bytes and never the motion. On connect the server sends a manifest
      (the active body's clips, groups and defaults, with hashes), the node answers with the hashes it misses, and the
      server streams those as one animation container, a section per clip: one request instead of a hundred, and a
      broken transfer resumes at a section. Changes later are deltas the same way.
    - **The animation container** is the same kind as a model's, with its own magic number `0x1DEA1AC7` ("IDEAL ACT"), the
      version header, a CBOR metadata section and a `VRMA` section per clip (built: the `.joia` export). The node's
      cache will use it too; between the server and a node it is a stream.
    - **Sharing a set** ("my idles", "greetings") is the `.joia` export; later it also carries groups, defaults per
      state and the bodies they were meant for. What a user shares is up to them: Joi doesn't check licences or
      leave clips out.
  - Later: generating motion from a text prompt with a text-to-motion model, saved into the library.
- **Camera** is part of the same display state, per display, and works both ways too. Joi moves it with a tool
  (`set_shot`) over a fixed grid of presets instead of free coordinates: **framing** (face close-up, bust, waist up, full
  body) × **angle** (front, three-quarter left / right, profile, slightly high / low). She can lean in for an intimate
  or emotional moment, pull back to show an outfit ("show me the new jacket" → full body), or turn to show a side. You
  can change it from the UI or a Stream Deck key, and she is told. Transitions are smooth and rate-limited (no jumpy
  cuts). On the Looking Glass the framing maps to the view diameter and target, and angles stay near frontal because
  of the narrow view cone. The self-image snapshots use the same presets.
- **Several bodies**; each display holds its own active body.
- **Her bodies are a list assigned to her**, one of them active (built: `Body` rows of the assistant, the `active`
  flag). She can only switch between the bodies on her list (`switch_body` offers just those), and so can the UI for
  her. On connect a node is sent each body of her list it has not cached (by the content hashes), so a switch is
  instant, never a download in the middle of a conversation. Adding a body to her list (or removing one, or a new
  version of its model) syncs the nodes connected now, in the background, and is an event for her ("you have a new
  body: Armor"), staged like other configuration changes. Removing a body tells the nodes they may drop it from
  their cache.
- **Storage:** the server owns the bodies (converted model, description, capabilities, content hash). Nodes cache by
  hash, download a body the first time it is shown (resumable, with a cache limit), and serve it to their local
  renderer. Conversion (headless Blender, or Unity for the Unity renderer) runs on the server, so nodes never need
  Blender or Unity. The node sees a body as an opaque hashed file for one renderer type: likely the export
  container with the node's profile (see UI → Bodies → Export).
- Users bring their own avatars, from wherever they get them.

### Events (what happens around Joi)

Everything that changes around her reaches her as an **event**, through one event system — not as edits hidden in a
summary. Kinds:

- **Your actions:** toggles, outfit or body switched, camera, mood or personality changed, privacy mode on / off,
  rename.
- **Configuration and inventory:** a new body / outfit / animation imported, a body edited, a body or tool
  **unavailable** (its node offline, Bridge down, an extension not loaded) and available again, nodes connecting and
  leaving, `tools_changed`.
- **External** (see Proactive events): mail, calendar, webhooks — the same pipeline, but marked untrusted.

Each event carries a time, a source and a **retention grade**:

- **transient** — matters only now (you toggled the halo, a node went offline for a minute); it's in the live context,
  she may react, then it's gone (only the audit log keeps it);
- **summarizable** — part of the conversation like a turn, folded into summaries as usual;
- **permanent** — becomes a memory fact that never fades: a rename, a new body or outfit she now owns, a personality
  change, a new assistant she can talk to.

**Configuration changes are staged.** Edits in the UI (a body import with ten toggles named, outfits defined,
animations tagged) don't fire one event per field: when you **save**, the server computes the **delta** and sends one
event ("a new body 'Ryuu' with outfits street / armor and 12 toggles; animation 'wave' added; body 'Old' removed").
Rapid user actions coalesce too (toggling the halo five times = one event with the final state), and reactions are
rate-limited so she comments when it's interesting, not on every click. Events that arrive while nobody is talking wait
for the next interaction (or are dropped, if transient and stale).

**Queued events (built: `QueuedEvent`).** An event that happens while she can't take it (no node is connected: every
node is shut down and you add a model in the UI) is stored with its kind, time, text and data and waits. When a node
connects, the waiting events go to her in the order they happened as **Event** messages (`ChatMessage` of type `EVENT`,
the event is marked delivered in it), so she can still react ("oh, a new model?"). One that no longer matters (a model
added and removed again) is marked handled without a message.

### Proactive events

- **Event sources** are a plugin point on the server: email (IMAP IDLE, Gmail / Graph push), calendar (CalDAV or the
  provider API), chat (bot / webhook APIs), a generic webhook endpoint. Each produces an `Event` (source, sender,
  summary, body, urgency).
- On macOS a node can also read notifications (no public API: the Notification Center SQLite database under
  `~/Library/Group Containers/group.com.apple.usernoted/`, needs Full Disk Access and may change between macOS versions;
  or reading the banners through Accessibility). A catch-all, not the main path.
- **Salience:** rules plus a cheap model decide whether Joi speaks up, shows a card, or keeps it for "anything new?",
  using sender, keywords, your context (call, focus, quiet hours) and mood. Editable in the UI.
- **Prompt injection:** event content is untrusted. It goes into the prompt marked as data, turns started by an event
  get stricter tools (everything that changes something is ASK, never ALLOW), and the server tags such requests so the
  node's harness enforces it too.

### Screening untrusted input (idea)

A small local model screens untrusted text (mail, chat, webhooks, fetched pages, file contents, OCR of the screen)
for prompt injection before the main model sees it. It is **a signal, not the defense**: the defense stays structural
(content marked as data, stricter tools in turns that read it, the node's harness enforcing it). A detector misses
attacks written to slip past it and flags innocent text ("ignore the previous mail, here is the right date").

- **The model:** a purpose-built classifier rather than a general 3B chat model. Classifiers in the 20–90M class
  (DeBERTa-based prompt-injection detectors, Meta's Prompt Guard line) answer in milliseconds on the CPU, with no GPU
  slot next to the conversation model and TTS; a 3B LLM asked "is this an injection?" is slower and less consistent.
  Which one, and how well it does on our own samples, is a spike.
- **Long text** is checked in windows (the classifiers read about 512 tokens), the highest score wins.
- **What a hit does:** the content still reaches the model (dropping it would let anyone hide a mail from Joi), but
  wrapped with a warning; the turn's tools go to ASK for everything, including what is usually ALLOW; the approval
  card says why; the source is logged. Above a higher threshold the content is summarized by a tool-less model
  instead of being passed through.
- **Memory:** text from an untrusted source keeps its taint when it is written to memory, and is screened before, so an
  injection can't wait in memory and come back in a later, trusted turn.
- **Its own setting:** off, flag only, or flag + strict; thresholds per source. Privacy mode changes nothing (it runs
  locally).
- An eval set of injections and of normal mail / pages that look like one measures misses and false alarms before a
  threshold is chosen.

### Physical controls (idea)

A **Stream Deck** (or similar button / dial pad) attached to a node as a control surface, with user-bindable keys:
swap the body, switch or pin the mood, mute the mic / push-to-talk override, stop speaking, approve or deny the pending
ASK card, trigger a canned prompt or tool, toggle outfit parts. The node reports the deck as a capability (model, key
count, dials); bindings live on the server (editable in the Vaadin UI, per node) and the node renders key images from
state (current body / mood thumbnails, a lit "approve" key while a card is pending). Approvals from the deck count as
local, physical approvals — useful for the harness.

## Node

### The first node (JavaFX)

The first real node is a desktop app in JavaFX (`joi-node`). What it does, in order:

1. **Login** to the server: a token, or OAuth (the device grant: a code the user approves in the server's UI), or
   none on a development server. Its id, name, the server and the assistant chosen last are kept in
   `~/.joi-client/node.json`, the credentials in `credentials.json` (readable by the user alone), kept per server address: the login screen offers the servers the node got into before (a combo box, the last one first), a known server needs no token typed, and *Forget this server* removes it with its token or pairing.
2. **Her pick:** the list of the user's assistants, unless the one chosen last still exists; then she is loaded.
3. **A loading screen** while her body's `.joim` and `.joia` download (cached by SHA-256 in `~/.joi-client/cache`,
   checked before they are kept).
4. **Connected**, the server looks at whether she has ever talked (any chat message; summaries only come from
   messages):
   - **Never: her first waking up.** The node shows her **sleeping** (the animation chosen in her dialog, Animations
     tab), and the server starts her first turn at once: the synthetic event `first_boot`, from its own template
     (`FIRST_BOOT_EVENT`), where she reacts to being woken for the first time, tells the user what she can do and
     says what she thinks of her body. Her words (which can be long) and her voice are rendered while she sleeps and
     held back; when the turn is done she plays **waking up**, and then speaks. Holding back the words until an
     animation is over should work for every animation. Then she waits for the user (text for now).
   - **Before: the user is back.** The synthetic event `connected`, its own template (`CONNECTED_EVENT`: a greeting
     that fits how long they were away).
5. **The chat** beside her body: text chat with her voice played back (no microphone yet). It starts with her
   history from the server (`GET /api/v1/jois/{id}/chat`), so it goes on where it was. When she has a voice, the
   text of a reply waits for its first piece of speech, so the chat doesn't run ahead of her. Her body is a 3D view next
   to the chat; **On the Looking Glass** moves her onto the Looking Glass: the node asks Bridge where the display is
   and for its calibration (`LookingGlassBridge`), opens the renderer page in its Looking Glass mode full screen on it in the user's
   Chrome (`fx/LookingGlassWindow`), and closes the page in the pane (one renderer at a time). The server
   is told (`display`), so she reacts. The choice is kept in `node.json` (`lookingGlass`): a node left with her on
   the Looking Glass opens it again as it starts, without telling the server; one where it could not be opened
   (no Bridge, no display) remembers that and only tries again from the button. An unplugged display closes the
   window and brings her back.

While she waits she plays one of her **idle** animations (another each time), while she speaks one of her **talking**
ones; falling asleep and asleep come from her dialog too (`AvatarDirector`).

**Built (stub):** `joi-node` holds the server client (`ServerClient`: login, device grant, her, her body, the
downloads), the node WebSocket (`NodeSocket`), what her body plays in each state (`AvatarDirector`, tested), the
settings (`NodeSettings`), and `JoiNodeCli`, the node in a terminal (text chat, the animations printed). The JavaFX
app (`node/fx`: `JoiNodeApp` with the screens above, `AvatarPane`, `SpeechPlayer`) builds with `-Pfx` (OpenJFX 25)
and starts. Her body is the renderer page (`node/renderer`: `BodyFiles` unpacks her cached `.joim` / `.joia`,
`RendererServer` serves them and the page, built from `src/main/renderer` by the `fx` profile with npm offline);
Chromium (JCEF 152) shows the page in the pane, rendered off screen and copied into a JavaFX image each frame (the
mouse doesn't reach it yet); her state and the animation are written over it, and Open in browser shows the same
page in a browser.

**The renderer's host (decided: JCEF).** JavaFX's WebView has no WebGL, so the renderer page runs in JCEF, as in
[Renderers](#renderers): the same Chromium on every node, a borderless window of the node's own on the Looking Glass
(no WebXR popup), her body in the app's pane. The page is the server's `joi-model-viewer.js` with a small node
script, built with Vite into the node's resources. The node unpacks her cached `.joim` and `.joia` and serves the VRM,
the clips and the page on `127.0.0.1` (a random port and a secret path); it tells the page what to play over
server-sent events and the page posts back when an animation ends, so the same page also runs in a normal browser.

**Open:**
- **Text on the Looking Glass Go without blur:** to find out (text drawn on a plane at the focal depth, or kept off
  the hologram).
- **Two modes for an animation she plays from a tool call:** *staggered* (the animation first, then her voice) or
  *immediate* (her voice with the animation).
- **Talking animations that match her voice:** ideally lip sync and gestures that follow the speech (visemes from the
  audio).

### Capability manifest

Sent on connect: OS and architecture, microphone (and permission), screen capture, accessibility, HUD available,
installed tools (git, docker…), holographic display (detected, Bridge running, calibration), the policy summary.

**Nodes define their own tools.** The manifest carries full tool definitions — name, description, JSON schema of the
arguments, and the harness tier each falls under (ALLOW / ASK / DENY, or "depends on arguments") — not just flags.
The server does not know node tools in advance; it registers what each node sends, namespaced per node
(`desk.capture_screen`, `phone.set_alarm`), and routes calls back to that node, which validates the arguments and
enforces its harness again.

- **Updates on change**, not only on connect: a permission granted, the Go plugged in or Bridge going down, a program
  installed, the policy edited, a node plugin or a local MCP server added → the node sends its `manifest` again
  with the new set and a higher version (the server keeps the highest).
- **Server-side tools** (memory, search, bodies / outfits / camera / animations, mood, delegate) are defined by the
  server; only node tools come from nodes.
- A node can host **local MCP servers** and expose their tools the same way, under its own harness.
- What Joi sees in a turn: the server tools plus the tools of the node you are talking through, and of the other
  connected nodes by name ("on the desk"), so she can act on another machine when it makes sense.

### Harness (Claude Code style)

- A local policy file (e.g. `~/.joi-client/policy.json`) the server cannot change.
- Paths resolved with `toRealPath()` before checking against allowed roots and denied paths (`~/.ssh`, `~/.aws`,
  browser profiles…) — no `../` or symlink escapes.
- **ALLOW** (silent: reads inside allowed roots, the active window title, a screenshot you asked for), **ASK** (a card
  with Allow once / Allow for session / Deny, by click or by voice: shell, writes, deletes, messages, clicks), **DENY**
  (the policy file itself, sudo, system paths).
- Local append-only audit log (`audit.jsonl`): request, resolved path / command, who approved, exit code.

### Web

Search and fetch can run **on the server** (when enabled for the assistant) or **on a node**:

- **Server-side** is the default when enabled: no round trip through a node, works when no node is connected, and the
  server keeps a cache of fetched pages (the same page asked twice, or by two assistants, is fetched once).
- **Node-side** for what needs that machine's network: intranet or home-network addresses, a VPN only the node has,
  sites that block the server's address — and `open_url`, which always runs on the node you are at.
- The server picks: its own tools when enabled, the node's for local / intranet URLs or when the server has no web
  access. Both offer the same tools:

- **`web_search`** — through a configurable backend: a self-hosted SearXNG, or an API (Brave, Kagi, …). Returns titles,
  URLs and snippets.
- **`fetch_page`** — downloads a page and strips it for the model: readability extraction to Markdown (no scripts,
  navigation, ads), title / date / links kept, long pages chunked or summarized by the heavy model. A headless browser
  (Playwright) for pages that need JavaScript, with an optional screenshot for a vision model. Fetching is anonymous —
  never with the cookies of your own browser.
- **`open_url`** — opens a page in your browser on that node, for you to look at (ALLOW right after you asked, ASK
  otherwise).
- **Untrusted content:** everything fetched is data, not instructions — marked as such in the prompt, and turns that
  read web content run with stricter tools (anything that changes something is ASK), as with mail.
- **Privacy mode:** queries and fetches only go through local / self-hosted services (SearXNG), or are refused.

### Seeing the screen

When a model with vision is available, the node can give Joi images of what you see:

- **From you:** a hotkey to capture the screen / a window / a dragged region, pasting or dropping an image into the
  HUD or the chat, the clipboard ("look at this"), files from the share sheet on the phone.
- **From Joi:** a `capture_screen` tool (display, window, region; the node reports what it can capture — on macOS
  ScreenCaptureKit with the Screen Recording permission). Harness rules apply: **ASK** by default; **ALLOW** only right
  after you asked her to look ("what's this error?"). Never silently in the background, and a capture indicator shows on
  the HUD whenever one is taken.
- **Routing:** if the live conversation model has no vision, the image goes to a multimodal model (the heavy one, or a
  local vision model) which describes it for the conversation. In **privacy mode** images only go to local models.
- Screenshots are attached to the conversation (viewable in the UI) with a retention setting; denied paths of the
  harness have a counterpart here — windows of listed apps (password manager, banking) are never captured.

### Voice

- **Pipeline:** microphone (16 kHz mono) → Silero VAD (drop silence) → wake word → speech-to-text → stop after ~1.2 s of
  silence. sherpa-onnx has Java bindings for VAD, keyword spotting and streaming ASR, so the node stays cross-platform;
  Apple's on-device recognizer can be a better optional backend on macOS.
- **Wake word and speech-to-text run on the node** (latency).
- **Text-to-speech runs on the server:** Chatterbox (fast, see the measurements below) or Higgs Audio v3 in ComfyUI
  (slower, but with emotion and sound tags). A speech server renders a whole job before anything plays, so the server
  cuts the reply into pieces and sends one TTS job per piece: piece 1 plays while piece 2 renders.
- **TTS models and voice actors** (built, taken from Marginalia's audiobook plugin): a TTS model is one speech server
  (Chatterbox-TTS-Server, or ComfyUI with Higgs Audio v3), the model it must have loaded, its GPU lock and the speech
  defaults. A voice actor is a voice (a reference clip of the user, or a voice file of the server) with voices of its own
  for a mood (whisper, shout...) or a language, and a **description** of how it sounds in words ("low and warm, a little
  husky"), which she reads: in her system prompt (`{{#voice}}`, when she has a speech model) and in the event when her
  voice changes. Both are per user, under Models. Her voice names an actor (hers, else the user's), and her mood picks
  the actor's mood voice and, for Higgs, the emotion token of the reply (`SpeechText`).
- **Barge-in:** the node keeps VAD running while it plays audio (with echo cancellation, or Joi wakes herself); the
  protocol carries "stop speaking".
- **GPU (local setup):** the platform's GPU locks, set up under Administration. A lock is a way to fill a GPU (start-up
  and release steps), not one model, and the jobs of one lock run side by side. So Joi has a **voice lock** whose
  start-up loads the conversation LLM and the TTS model; both the conversation AI and the TTS model name it (no job
  limit, so the LLM streams while TTS renders the sentence before). A local heavy model is a second lock: a delegated
  task releases the voice lock, and the voice comes back with the next reply. A model that never unloads goes into
  every lock's start-up (load when missing) and no release, so the locks still know what the GPU holds.
- **Joi and Marginalia on one GPU:** each keeps its locks in its own database, so neither knows what the other left
  loaded. A lock's **steps before every job** check it each time, without marking the whole GPU as used outside:
  on a shared `llama-server` router, the voice lock runs `tools/gpu-control`'s `POST /llama/unload` with
  `{"keep": ["{{model | strip:openai/}}"]}` before every job. It unloads the other program's big model and does
  nothing when only Joi's model is loaded, so a model in use is never unloaded for nothing. A TTS model's lock gets
  its model as `{{model}}` too (an AI gets its model id from the platform), so one step serves every model. For
  ComfyUI, `POST /comfy/ensure` as a step before every job starts or restarts it when it does not answer.
- **Measured (2026-11-19, AMD R9700, ROCm 7.2, Gemma 4 12B without thinking for the text):** 15 streamed chats per
  setup through the API, medians, times from sending the message.

  | Speech | Reply | Text ready | First audio | Fully spoken | Audio |
  |---|---|---|---|---|---|
  | Chatterbox Turbo | 1 sentence, ~80 characters | 1.0 s | 3.2 s | 3.2 s | 4.8 s |
  | Chatterbox Turbo | 2 pieces, ~200 characters | 1.8 s | 3.2 s | 6.3 s | 10.6 s |
  | Chatterbox Turbo | 3–4 pieces, ~530 characters | 4.2 s | 6.2 s | 15.5 s | 30.9 s |

  Chatterbox renders a piece in about 0.4 of its length, so playback never waits for the next piece. Higgs v3 (fp32)
  needs about 1.8 of the length (about 16 audio tokens a second): a short sentence takes 6–7 s, a long one 14–20 s.
  Most of the wait before the first audio of a long reply is the text: the speech starts once the reply is whole and
  stored. Speaking the first sentence while the model still writes (roadmap step 3) would bring it to about 3 s.
  With the 12B the text is quick (about 1.5 s for a short or medium reply), so this is **an option of the assistant**
  (in the About tab of her dialog, beside her voice; off by default), and a node can ask for it or against it per
  message (a field of `ChatRequest`, a minor version of the protocol): a voice node wants it, a text client does not.
  Worth it for long replies and slower models; it costs the model and the speech sharing the GPU for
  a few seconds.
  A cold start costs more: Chatterbox from a stopped container about 60 s, Gemma reloaded about 20 s.
- **Speech servers on ROCm:** Higgs must stay fp32 (bf16 gives noise). ComfyUI must run **without**
  `PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True`: with it, ComfyUI's `empty_cache` after a prompt made the next
  generation of the loaded Higgs crash the GPU (`HSA_STATUS_ERROR_EXCEPTION`); without it the engine stays loaded
  between pieces (no reload of about 10 s a piece). Chatterbox keeps its memory flat in steady use (5.9 GB over 50
  pieces); it grows only when the model is swapped inside the server (`/api/unload`, another model), so its lock steps
  only ensure or stop the container.

### Mobile companion node (ideas)

A phone (iPhone / Android) as another node on the same protocol, with its own manifest and its own harness.

- **Talking on the go:** push-to-talk in the app; hands-free through the OS (iOS: Siri with App Intents / Shortcuts —
  "Siri, ask Joi…", no third-party wake word in the background; Android: a foreground service can run the same VAD +
  wake word pipeline, at a battery cost). Replies as speech (TTS streamed from the server) and text.
- **Her face:** the active body in 2D / 3D in the app (VRM in a WebView or a native renderer), and **AR** (ARKit /
  ARCore) to place her on the desk or the table. The same body state, toggles and outfits as everywhere else.
- **Approvals:** the phone as the approval device for ASK cards from any node (a push notification with Approve /
  Deny), with Face ID / fingerprint for the sensitive ones — a physical, local confirmation the harness can require.
- **Context and events:** location (arriving home, leaving work), calendar, the phone's own notifications as an event
  source (Android can read them with a NotificationListenerService; iOS cannot), photos / camera on request ("what is
  this?"), share sheet into Joi.
- **Tools on the phone:** reminders, alarms, messages / calls through the OS (always ASK), Shortcuts on iOS as a
  user-extensible tool set.
- **Connection:** pairing by scanning a QR code from the Vaadin UI; reaching the home server from outside through a
  VPN (WireGuard / Tailscale) or a relay — never an open port. Offline: queue the request, or a small on-device model
  for trivial answers.
- **Watch:** quick voice and approvals from the wrist.
- **Tech:** Android can share the Java/Kotlin protocol module; iOS is Swift, with the protocol types generated from
  joi-protocol's JSON schemas. (The Looking Glass Unity plugin also targets iPhone 15/16 Pro, so a phone could drive
  the Go.)

## Avatar and Looking Glass

### The display

- Looking Glass **Go** (`go_p`), serial `LKG-E08597`: a normal 1440×2560 portrait monitor to macOS (it also mounts a
  small FAT12 USB volume of the same name). It can be detected by the `LKG-` screen name without Bridge.
- **Bridge** 2.6.3 (the app's Info.plist says 2.0, which is stale) on `127.0.0.1:33334` (REST: `PUT` with a JSON body;
  `/enter_orchestration {"name"}` → token, `/available_output_devices {"orchestration"}`, `/exit_orchestration`), plus
  9724 and 11222. It reports the Go's calibration (pitch, slope, center, view cone 54°, DPI 491) and the default quilt:
  11×6 = 66 views in 4092×4092, aspect 0.5625.
- **Bug (not fixed in 2.6.3):** Bridge crashes at launch when the Go is already plugged in (hidapi `hid_init` →
  `IOHIDManagerScheduleWithRunLoop` off the main thread, SIGABRT). Workaround: start Bridge, then plug the Go in. The
  node reports "Go present, Bridge down" and the HUD says to replug.

### Renderers

1. **three.js + VRM** (`@pixiv/three-vrm`, `@lookingglass/webxr`) in a Chromium window: light, cross-platform, what the
   Looking Glass WebXR library supports. Needs the model converted to VRM.
2. **Unity** with the official Looking Glass plugin: imports VRChat packages nearly as they are (lilToon / Poiyomi,
   blendshapes, animator states). Heavier, non-Java, built per OS, and PhysBones still need replacing (the VRChat SDK
   can't be shipped in your own app).

The protocol stays renderer-agnostic (a body reports capabilities; the server never knows VRM from Unity). **Decided
after both spikes: three.js + VRM** (Unity ran at about 6 fps on the Go and its materials broke, see
[Unity path](#unity-path)).

**Hosting it inside the node:** the renderer runs in a browser embedded in the Java node, so there is no separate app
or runtime to install and the node owns the window; only on the Looking Glass it is the user's own Chrome (below):

- **JCEF** (Chromium Embedded Framework for Java, what JetBrains IDEs use; `jcefmaven` ships the native builds for
  macOS / Windows / Linux) gives the same Chromium WebGL as the spike. JavaFX WebView is WebKit with weaker WebGL and no
  WebXR, so not it.
- The node opens a **borderless window on the Looking Glass display**, at its position and size from Bridge, and the
  page renders the quilt and applies the lenticular shader with the calibration the node passes in (built:
  `src/main/renderer/src/looking-glass.js` hooks the viewer's frame, `renderFrame`: 66 cameras along the viewer
  camera's right, each frustum turned back to meet at the point it looks at, a view cone of 54° × 0.47, into an sRGB
  4092² quilt of 11 × 6 tiles, then each subpixel reads the view its lens shows; Chromium renders it at the
  display's own pixels, scale 1). While she is on the Looking
  Glass her pane shows a **tuning panel** instead of the page (`fx/LookingGlassTuningPane`, kept in `node.json` as
  `lookingGlassTuning`, sent to the page live): depth (the share of the view cone), focus (the sharp plane, metres
  from the point the camera looks at), zoom, nudges of the calibration's center and pitch, the views inverted, the
  lenses from the top, the quilt size, a frame cap, how many views are rendered in full, and debug views (the quilt
  as it is, the middle view only).
  Tuned by eye on the Go with the test avatar: depth 0.27, focus −0.35 m, zoom 0.97, center −0.003 (the defaults).
  On the Looking Glass the page is shown by **the user's own Chrome** (Google Chrome, or a Chrome / Chromium set as
  `lookingGlassBrowser` in `node.json`; a requirement only for the Looking Glass, the node says when it is missing):
  `fx/LookingGlassWindow` starts it as an app window full screen on the display (`--app`, `--start-fullscreen`), with
  a profile of the node's (`~/.joi-client/looking-glass-chrome`) and its background services off, and quits it when
  the window is closed from the button, when the node quits or is stopped (a shutdown hook), or when the display goes
  away. `JOI_LOOKING_GLASS_DEBUG_PORT` opens its DevTools port. Why not JCEF: off screen in the app, as the pane,
  each frame was read back from the GPU, copied into JavaFX and uploaded again: 15 fps against a 20 fps cap,
  Chromium's processes near 80% CPU each, and the whole machine lagged (video playback too), though the spike drew
  the same in Chrome without it. JCEF's own window shows nothing on macOS, in the JavaFX process (black) and in an AWT
  process of its own (it showed the frame's background): JCEF puts Chromium's view inside Java's window, whose own
  layer draws over it. Measured on the Go at the 20 fps cap: Chrome holds 20 fps, its GPU and page processes 20–45%
  CPU each, no lag; uncapped about 23 fps.
  **Views rendered in full** (`rendered`; all 66, or 9, 5, 3, 2, 1; 5 by default): the others are made from the
  two nearest rendered ones by their depth, RGB-D. The views differ only by a move along the camera's right, so a
  texel at distance z moves along its row by shift × (1 − focus / z). Each rendered view's depth is measured per row
  (the nearest and farthest of her texels, a pass of one pixel per row), and each pixel of a made view looks along
  its row over the texels that depth can move onto it (rows without her are skipped) for the span between two
  neighbouring texels that lands on it (a jump in depth of more than 3% between them is an edge, not filled), and
  keeps the nearer of the two views. Lost: what neither rendered view sees (black), transparent parts that write no
  depth over the background, the light changing between views. Tried first: a grid mesh over each rendered view,
  each vertex moved by its depth; a vertex per texel was needed for her hair (at every second texel thin strands
  broke into streaks), and 32 M vertices a frame ran at 9–12 fps, slower than all 66 renders; then a fixed reach
  around the focus, which cut what she held towards the viewer into pieces. Uncapped on the Go, the machine busy:
  all 66 views 19.5 fps, 9 rendered 43, 5 rendered 45, 3 rendered 38 (views far apart need longer searches). Still to try: cheaper shading on the Go, the lighter Looking
  Glass model of the body. That avoids the
  WebXR polyfill's popup and "enter XR" button, which were the fragile part of the spike. The node still asks Bridge for
  the calibration (REST on 33334).
- Other windows from the same code: a 2D window or HUD on a normal monitor (one view), and the body fitting editor in
  the Vaadin UI (there in the user's own browser).
- The page talks to the node over a local WebSocket (state frames: expression, visemes, animation, camera shot,
  toggles). Bodies come from the node's cache through a local file handler, never from the internet.
- Cost: one Chromium process (about 150–300 MB) instead of a Unity player; a node without a display (headless,
  server room) doesn't start it at all.

### VRChat models

The good source of models is VRChat avatars, and porting them is the hard part:

- **Easy:** the Unity humanoid rig (the mapping is in the FBX's `.meta`), the standard `vrc.v_*` visemes and
  `vrc.blink`, ARKit face shapes when present, named expression shape keys, outfit meshes and "hide X" keys.
- **Hard:** the look (Poiyomi / lilToon are Unity-only: matcaps, rim, decals, hue shift are lost in MToon), PhysBones
  (replaced by spring bones guessed from the bones' names), VRCFury / contacts / particles / animator logic
  (VRChat-only, skipped).
- **Licences** of bought avatars differ; that is up to the user.
- **Performance:** the Go renders 66 views per frame, so a 180k-triangle model is ~12M triangles per frame. The test
  avatar is a detailed PC avatar; the cost belongs to the model and the display, not to Joi. Options:
  - a **lighter avatar** (VRChat "Quest" / mobile variants are made to a budget), or the converter making one
    (decimate, merge materials, 1024 textures) as a **Looking Glass variant** of the body, kept next to the full one;
  - hiding meshes covered by clothes, cheaper shading on the Go (toon / Lambert instead of MToon), fewer views, lower
    idle frame rate;
  - **no Looking Glass:** a normal 2D window, a second monitor or the phone renders one view, not 66, so even the full
    model runs smoothly there. The Go is one display a body can be shown on, not a requirement.
  The body import can measure each variant on the display it's meant for and warn when it falls below about 20 fps.

## Spikes

### VRChat FBX → VRM 1.0 (headless Blender), now `convert_avatar.py` in joi-server

```
Blender -b --factory-startup --python convert_avatar.py -- <config.json>
```

Started as a spike with a hand-written config per avatar; it is now the server's converter (see UI → Bodies), which
writes the config itself. Needs the "VRM format" add-on (extensions.blender.org, id `vrm`). The config names the FBX,
the Unity materials (read from the package's `.mat` files), meshes to leave out, shape keys to bake or drop, the
expression mapping and the FBX `.meta` with Unity's humanoid mapping; all but the files have defaults (the script's
docstring lists them).

Tested on a VRChat avatar package (Poiyomi 7–9, VRCFury, 31 PhysBones, 240 face shape keys including ARKit; kept locally
in `tmp/`, never committed):

- Rig from Unity's `.meta`: all humanoid bones including fingers and eyes (the add-on's own guess was one bone off).
- 25 expressions: visemes, blink, 5 moods from named keys, 7 custom (blush, smirk, pout, tears, heart eyes, think,
  worry).
- Textures to 2048; emission only where the material has an emission map (otherwise MToon glows white).
- Result: 181k triangles, 100 MB VRM (morph targets and PNG textures; to slim later).
- Spring bones: the add-on's automatic guess found none; Joi's guess by name finds the tail, the cat ears, the ears,
  the hair (62 bones) and the scarf.
- Missing: the hair tint (Poiyomi decals / hue shift), MToon shade texture.

### `spikes/avatar-vrm/viewer` — three-vrm + Looking Glass WebXR

`npm install` then `npm run dev` (port 5181), or the `avatar-viewer` entry of `.claude/launch.json`. Idle breathing and
sway, blinking, fake lip sync, mood easing (keys 1–5, 0; b s h w k toggles; t talks), eyes on the camera, a big
"Enter Looking Glass" button (L). It posts fps, load info, errors and XR session events to
`tmp/viewer-report.jsonl`.

Status: **works on the Go** (Chrome 157, Bridge 2.6.3). Three fixes were needed:

- three.js 0.187 hands the polyfill's fake `XRSession` to Chrome's native `XRWebGLBinding`, which rejects it → the
  viewer deletes `window.XRWebGLBinding` before creating the renderer (three.js falls back to `XRWebGLLayer`).
- The polyfill binds its own textures with raw WebGL calls; three.js's state cache misses it, the bone texture update
  (`texSubImage2D`) hits the wrong texture and every **skinned** mesh vanishes → `renderer.resetState()` each frame
  while presenting, keeping the XR render target.
- The library's mouse controls (scroll zooms, drag rotates) moved the cameras into the fog → the viewer re-applies its
  framing every frame.

Tuned by eye on the Go: fov 23°, depthiness 0.47, gaussian sigma −0.14, 2-view filtering, 0.55 m view diameter.

Performance on the M4 (Go, 66 views, 4092² quilt, `?bench=1` benchmark):

|                                                   | fps       |
|---------------------------------------------------|-----------|
| no avatar                                         | 58–60     |
| untextured basic material                         | 46        |
| unlit texture                                     | 32.5      |
| `MeshToonMaterial` + texture (1 light / 3 lights) | 27 / 25   |
| Lambert + texture                                 | 26        |
| MToon (3 lights, normal maps → 1 light, none)     | 15.5 → 18 |
| quilt 3072 / 2048 (MToon)                         | 17.5 / 22 |

Pruning the model to the 31 shape keys its expressions use (from 229; file 100 → 51 MB) did not change the fps. The
cost is per-pixel shading × 66 views, MToon above all; textures with alpha cut-out (hair, bodysuit) add overdraw. The
column / row count cannot be changed at runtime (the library ignores it), only through `?cols=&rows=` at load.

### Unity path

Tested 2026-11-17: Unity 2022.3.62f3 (built-in pipeline), Poiyomi 10.0.29, Looking Glass Unity plugin 3.2.8, a native
arm64 Metal build of a scene with the avatar and a Hologram Camera on the Go. Project `/Volumes/Annex/unity/JoiLKG`,
setup and build in batchmode by `Assets/Joi/Editor/JoiSetup.cs`.

- **Slower than three.js:** about **6 fps** with the Go's real calibration (about 20 fps while the plugin was still
  rendering with its emulated default device). three.js manages 15 fps with MToon and 25–27 with a cheaper toon / Lambert.
- **Materials broke:** the avatar's materials target Poiyomi **8.1** ("Poiyomi Toon Outline"), which 10.0.29 doesn't
  ship. Switched to Poiyomi 10's Toon and locked, they render wrong (mostly white, clothing see-through). Fixing them
  needs the old Poiyomi version, or re-tuning every material by hand.
- **The VRChat prefabs don't load** once add-on packages are filtered out (they nest the missing prefabs), so the
  scene is built from the FBX with the materials mapped by name, the same as the VRM converter.
- **Plugin setup gotchas**, for any later Unity work:
  - It needs `com.unity.ugui` and **`com.unity.nuget.newtonsoft-json`**. Without Newtonsoft it silently never
    connects to Bridge and renders with an emulated "Portrait" calibration (diagonal stripes on the Go).
  - Delete its test folders (they need the test framework).
  - The calibration arrives about 25 s after start.
  - Start the app with `-monitor <Go index> -screen-fullscreen 1 -screen-width 1440 -screen-height 2560`.
  - A new unsigned build hangs at macOS's first-launch "Verifying…" when it lives on the Annex volume; build into
    `~/Applications`.

**Result:** three.js + VRM stays the renderer. Unity would only be worth another look for effects three.js can't do,
with avatars whose shaders match the installed Poiyomi.

## Roadmap

1. joi-protocol (schemas and the spec first), joi-server and a headless joi-node: text chat, the harness (file read /
   write, shell with ASK), the manifest, pairing. The base everything else plugs into.
2. Voice in: VAD, wake word, speech-to-text.
3. Voice out: sentence-streamed TTS to the node (each piece is sent as soon as it is rendered: built), speaking while
   the model still writes (an option of the assistant), barge-in.
4. The avatar renderer on the Go, the states (idle, listening, thinking, speaking), approval cards in 3D; bodies and
   self-image.
5. Deeper OS actions, long-term memory, MCP, proactive events.

## Open decisions

- **Which binary JSON** for the export container's JSONB sections (CBOR, BSON, Smile...): one with a streaming
  reader and writer in Java and in the renderer's language.

- ~~Extract Marginalia's platform layer first, or copy~~ — extracted: `marginalia-platform` on Marginalia's `dev`;
  Spring XML, `persistence.xml` and Flyway stay per application (see [MARGINALIA.md](MARGINALIA.md)).
- ~~Renderer for the Go: VRM / three.js or Unity~~ — three.js + VRM (Unity spike: about 6 fps and broken materials, see
  [Unity path](#unity-path)).
- Whether the conversation tilts the mood.
- Getting Looking Glass WebXR mode to start in Chrome.
