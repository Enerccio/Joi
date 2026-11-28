---
label: Writing a node
icon: device-desktop
order: 710
covers:
  - joi-node/src/main/java/com/github/enerccio/joi/node
  - joi-browser/src
  - joi-server/src/main/java/com/github/enerccio/joi/domain/node
  - joi-server/src/main/java/com/github/enerccio/joi/api/NodeEndpoint.java
  - joi-server/src/main/frontend/joi-lipsync.js
verified: 1bc7a07
---

# Writing a node

The reference node's voice input (push-to-talk, speech to text on the node) is described in [Voice in](voice.md).

A node is a client of the node WebSocket: `GET /api/v1/node` with `Upgrade: websocket` and the API's credentials (in the `Authorization` header, or in
`hello.token` for a client that cannot set headers; never in the URL). The messages are in `asyncapi.yaml` and `schemas/` (see
[The protocol](protocol.md)). A node declares which parts it implements, so a minimal node is a handshake, a manifest with one tool, and answers to calls.

Every message is a JSON text frame `{"type", "id", "ref", "data"}`: `type` names the data's schema, `id` is set when an answer refers to it, `ref` is the frame it
answers. Unknown types and fields are ignored.

## The conversation

1. **Handshake.** `hello` (protocol version, the node's stable id and short name, kind, software, the assistant, its parts) answered by `welcome` (the version
   spoken, the session, the assistant, the heartbeat, `serverStartedAt`). One assistant per connection; the same node connecting again for her replaces the old
   connection.
2. **Manifest.** `manifest` after `welcome` and again on every change, with a growing `version`: OS, architecture, capabilities, and the tools (name,
   description, JSON Schema of the arguments, tier `allow`, `ask`, `deny` or `depends`). She sees them as `<node>__<tool>` while the node is connected.
3. **Talking.** `say` (text, speak, reasoning, `images`: the ids of images uploaded with `POST /api/v1/jois/{id}/images`, since 1.11; `text` may then be empty, and a node whose
   assistant cannot look at images gets an `error` frame `no_vision` for that turn); the frame's id is the turn, and the answer is the streamed chat as frames that refer to it: `reasoning`, `text`, `tool`,
   `reply`, `audio`, then `done`, or `error`. `stop` ends a turn. The server starts a turn itself with `turn` (an id and a cause), answered the same way: after the
   first manifest of a connection she wakes up for the first time or greets the user who is back, but not for a node that only lost the connection
   (`hello.resumed`). The greeting is one reaction: `manifest` holds the session (`NodeSession.hold`), the events that waited are pended, and
   `greet` waits for `SummarizerService.settle` (the summaries due now are queued and made; at most `summaryWaitMillis`, three minutes), so a
   summary made meanwhile is pended too (`tell` prefers the held session). Then `greetNow` starts one turn: what waited first, the greeting
   last. A `say` before that releases the hold and the greeting is dropped; a `display` meanwhile is pended, not a turn of its own beside the greeting. `CONNECTED_EVENT` gets the clock times of the last talk and of
   now (`lastChattedAt`, `backAt`: `Sunday 2:44 AM`, with the date when it is more than six days ago). With no chat model there is no greeting, and the node gets an `error` frame without a `ref` (code `no_model`) instead of silence.
4. **Tools.** `tool_call` (call id, tool, arguments, a timeout) to the node, which checks its own policy again, may send `tool_progress` and `approval` (asked, then
   allowed or denied), and sends exactly one `tool_result` (ok, content, an error word: `denied`, `failed`, `cancelled`, `timeout`, `unknown_tool`, `bad_arguments`).
   `tool_cancel` when the result is no longer wanted; the node still answers.
5. **Her body.** `event` (`body`, `joi`) tells the node to read `GET .../body` again. `animation` asks the node to play one once. `display` tells the server she is
   on a Looking Glass. `part` (staged by the node, see below) switches a part; the server answers `part_kept` or `part_refused`.
6. **Tasks.** The server sends `task` (a `TaskInfo` without the transcript) when a task is made and each time its status changes, and to a node that connects for
   each task that waits. A `waiting` one is a question: show it and answer through the HTTP API (`approve`, `approve` with `always`, `deny`). The first answer wins.
7. **Heartbeat.** `ping` and `pong` both ways; a connection silent for twice the heartbeat is closed. `log` carries a note of the system.
8. **Errors.** An `error` frame keeps the connection. Close codes: 4001 unauthorized, 4002 unsupported version, 4003 replaced, 4004 no such assistant (also once she is deleted: the next frame gets `error` `not_found` first), 4008 timeout,
   4009 revoked.

## Images

A node that lets the user attach an image uploads each with `ServerClient.uploadImage` (the file is the body, streamed: `BodyPublishers.ofFile`, or `UnixHttp.sendFile` on the local socket, with
the 401 refresh as every call) and puts the ids in `NodeSocket.say(..., images)`. `JoiInfo.images` says whether to offer it at all (`ServerClient.joi`). The JavaFX node's `ChatPane` has an
*Image…* button (a file chooser) and takes an image from the clipboard (Ctrl/Cmd+V when it has an image and no text; written to a temporary PNG), shows the waiting images as small pictures with a
button to take one away, and uploads on a virtual thread before it sends; the terminal client has `/image <file> [words]`. Nothing is done to the picture on the node: the server turns it upright
and scales it.

The JavaFX node's history (`ChatPane.history`, `drawPast`) is the latest 500 messages (`ServerClient.history(joi, limit, before)`); "Show earlier
messages" loads the page before the first id and keeps the scroll where it was. The images of a message and her `toolImages` come from
`ServerClient.image` (`GET /api/v1/images/{id}`, streamed into `<home>/cache/images/<id>.jpg` once, an id that is not a plain name refused) and
show as thumbnails that open whole. A `Separator` line follows the last `summarized` entry. Every line has a context menu: Copy, and Copy the
conversation (`transcript()`: the labels in order, the summary line as `---`).

## Reconnecting

Connect again after 1 s, 2 s, 4 s... up to 30 s, with `hello.resumed` and the latest manifest, so she does not greet the user again. After the server was
down, `hello.resume` has the `serverStartedAt` the node got, when it lost the connection and the close code: the first node to come back starts a turn with the
cause `server_restarted`. Do not try again after 4001, 4002, 4003, 4004 or 4009. While offline do nothing for the user: keep nothing to send later, because what
the user said while she could not hear is said again by them.

## Parts of her body

The user's changes of a part are staged: hold them back until the user left the parts alone for 4 s, send one `part` with an `id` for each changed part, and lock the
controls until the server answered each (`part_kept`, `part_refused` or `error`, with the id; 15 s without an answer frees them). `say` has an optional `withEvents`:
the server then takes the events of the `part` frames sent just before into the turn of the words. In the reference node a batch lost with the
connection (also while the user is asked whether their words go with it) is told as not kept, so the controls show what is kept again.

A node trusts the server only so far: the reference node names a cached download by its `sha256` only when that is 64 hex digits, and a frame it
can't handle is logged and the socket reads on.

## Profiles

The user may give her profiles (home, work) and assign each node to one by the `nodeId` of its `hello` (see [Bodies and models](bodies.md)). A
node needs only to read her body with `GET .../body?node=<its nodeId>` (protocol 1.12): the server sends it the `event` `body` only when
its profile changed. Nothing else changes in the frames; what she is told follows the node that talks to her.

Her outfits (since 1.12): `BodyInfo.outfits` lists them (the one she wears in the node's profile marked `worn`), and a node that lets the user choose one
sends `outfit` (`OutfitSwitch`, the outfit's id; `NodeServiceImpl.outfitSwitch`). The server wears it in the node's profile, tells her as for a part, and
sends the `event` `body` to the nodes of the profile, the sender too, which show it when they read her body again; take every toggle of `BodyInfo.toggles`
then, not only the parts', as an outfit sets toggles that are in no part. The reference node's controls panel has the choice (`ControlsPane.outfits`).

## The reference node

`joi-node` (`com.github.enerccio.joi.node`):

| Class | What |
|---|---|
| `ServerClient` | the HTTP API: login, the device grant, her, her history (a window that slides back), her body, the downloads cached by hash, the chat's images cached by id |
| `NodeSocket` | the node WebSocket, with reconnecting |
| `AvatarDirector` | what her body plays: idle, talking, asleep through her first turn, waking up, a gesture on top |
| `NodeSettings` | the node's home (`~/.joi-client`, moved by `--joi-home-dir`, `JOI_HOME_DIR` or `JOI_CLIENT`: `NodeSettings.resolve`), `node.json` and `credentials.json`; every path of the node is under it |
| `NodeTools`, `NodeTool` | the tools the user switches on: the manifest with the ones that are on (a new, higher version when a choice changes), the routing of a call by its name, the `approval` frames of a call that asks |
| `Notifications` | the tool `notify`: off, ask or allow |
| `ClipboardOffers`, `DesktopClipboard` | the tool `offer_to_clipboard`: off, ask or allow; the clipboard of the terminal node |
| `harness/` | the harness: `Policy` (`policy.json`), `PathGuard` (real paths, roots, denied paths), `Audit` (`audit.jsonl`), `Harness` (the flow of a call), `FileTools` (`read_file`, `list_files`, `write_file`, `view_image`), `ShellTool` (`run_command`); see *The harness* below |
| `browser/` | the browser companion's plumbing: `BrowserLink` (the node's socket `browser.sock`), `NativeHost` (Firefox's native messaging host), `BrowserHost` (Connect Firefox: the launcher and the host's manifest); the tools are `harness/BrowserTools`. See *The browser companion* below |
| `TaskQuestions` | the tasks that wait for the user's answer |
| `voice/` | voice in, push-to-talk: the microphone, Silero VAD, the end of an utterance, speech to text with sherpa-onnx, the download of the models; its setting is `voicePauseMs` in `node.json`. See [Voice in](voice.md) |
| `PartStaging` | the staging of her parts |
| `local/UnixHttp`, `local/UnixWebSocket` | HTTP/1.1 and the node WebSocket over a Unix domain socket (the JDK's clients cannot connect to one): see below |
| `LocalMode`, `NodeInstance` | `--local <socket>` / `--web <url>` (or `JOI_LOCAL_SOCKET`, `JOI_WEB_URL`), the lock `node.lock` of one node per home |
| `JoiNodeCli` | the node in a terminal: the smallest complete example |
| `renderer/` | her body's files unpacked (`BodyFiles`) and served to the renderer page (`RendererServer`, which also tells the page about her voice: see *Her mouth* below), the page's source in `src/main/renderer` (its native mode, where the app calls the page and the page posts back, is for the iOS app: see [The iOS app](ios-app.md)) |
| `fx/` | the JavaFX app: `JoiNodeApp`, `AvatarPane`, `ControlsPane`, `VoicePanel`, `SpeechPlayer` and `LineOutput` (her voice), the Chromium host (`Chromium`: started with `--no-first-run`, or Linux shows its Terms of Service window, which nobody can answer in an off-screen browser) and the Looking Glass |

Start with `JoiNodeCli`: it logs in (token, device grant or none), picks her, downloads her body into the cache, connects, chats, answers the questions of tasks and
offers `notify` and `offer_to_clipboard`.

## The server on the same machine

A server address `unix:/path/to/api.sock` (`ServerClient.UNIX`) is the server's local socket (see [The desktop app](desktop.md)). `ServerClient` then talks through
`UnixHttp` (one connection per request, `Connection: close`, `Content-Length`, chunked or read-to-EOF bodies, streamed downloads to a file, a watchdog for the timeout,
refusal of line breaks in header values and of anything but a plain path), signs in with `signInLocal()` (`POST /oauth/local` with `client_id` `joi-node-<node id>`; no refresh
token: `refresh()` signs in again) and `authorization()` mints a new token when the one in hand expired. Every refresh (an OAuth pair or a local token) runs under one lock of the process, and a call that got a 401 refreshes only if no other call did meanwhile: a second refresh would make the first one's token useless (a local token is replaced, a used refresh token ends the pairing). `NodeSocket.localSocket(Path)` opens the WebSocket with `UnixWebSocket`
(a client of RFC 6455 for `java.net.http.WebSocket`'s listener interface: masked frames, fragments, ping and pong, the close handshake) at `ws://localhost/api/v1/node`, and
`NodeSocket.authorizer(...)` supplies a fresh `Authorization` header on every reconnect, because the token of the first connection is an hour old by then.

The node of the desktop app is started with `--local` (`LocalMode.parse`); `JoiNodeApp.showLocal` is its first screen (it waits for the first account, retrying every 3 s).
`fx/Launcher` takes `NodeInstance.acquire(home)` before it starts the app and ends with code 3 when another node holds the lock of this home; the launcher of the server
looks at the same lock (`NodeInstance.running` is the model of the check it makes without a dependency on this module).

## A tool the user switches on

`notify` and `offer_to_clipboard` are `NodeTool`s in one `NodeTools`: `offered()` is the definition or null, so a tool that is off is not in the manifest and she does not know it exists; `NodeTools.changed()` sends the manifest again with a higher version when a choice is saved. A tool that asks the user goes through `NodeTools.ask`, which sends the `approval` frames (`asked`, then `allowed` or `denied`) around the answer; no answer in two minutes is a no. The user's choice is in `node.json` (`notifications`: `off`/`ask`/`allow`; `clipboard`: `off`/`ask`/`allow`; anything else is off).

`allow` of the clipboard tool copies without showing the text: what lands on a clipboard is pasted into a terminal or a form later, and the text can come from a web page through a task, so it is the user's informed choice (off by default, `ask` is the recommended one, the docs warn). The terminal node asks with `/yes` and `/no`, the JavaFX node with a dialog that shows the text (`askClipboard`), and copies on the JavaFX thread. `offer_to_clipboard(text, title?)` takes up to 100 000 characters (`ClipboardOffers.MAX_TEXT`, the same as a file of the server's workspace).

The server can call a node's tool itself, not only through her function `<node>__<tool>`: `NodeService.sessionsWithTool` lists the nodes that offer it (the one with a turn running first) and `NodeService.callTool` runs it, with the turn id and the same timeout and cancellation. `offer_file_to_clipboard` (see [Server tools](tools.md)) is built on that.

## The harness

`node/harness` holds her tools for the machine's files and shell (DESIGN.md → Node → Harness). They are `NodeTool`s like
`notify`: `Harness.tools()` goes into the same `NodeTools`. Each tool (`HarnessTool`) only **plans** a call. It resolves the path
or reads the command, refuses what the policy denies or what has bad arguments (`Harness.Refused`), and otherwise returns a
`Harness.Go`: whether the user is asked, what "allow for session" covers, the `Question` and the action. `Harness.execute` then does
the rest the same way for every tool:

1. A refusal is written to the audit and returned.
2. If the call asks and the session does not cover it, the user is asked with `NodeTools.askScoped`, which sends the `approval`
   frames with `once` or `session`. A `null` answer is no answer in time.
3. The decision is written to the audit. If that fails, the call fails and nothing runs.
4. The action runs, and its end (ok, error, exit code, bytes) is written.

The policy is read from disk on every call and every manifest (`Policy.load`). A file that cannot be parsed is the defaults,
with both tools off. `PathGuard` makes a path absolute (`~`, or a relative path under the first root), resolves the longest part
that exists with `toRealPath()` and appends the rest (`PathGuard.real`). The call works on that path: a write opens it with
`NOFOLLOW_LINKS`. `within` compares by names, and ignores case on macOS and Windows. The node's home is denied in code, not in
the file.

`ShellTool` runs `$SHELL -l -c` (else `/bin/zsh -l -c` on macOS, `/bin/sh -c`; `cmd.exe /c` on Windows). It sets the input to
`/dev/null` and `PAGER=cat`, `GIT_PAGER=cat`, `TERM=dumb` and `NO_COLOR=1`. The output and the errors are read together into a
`Capture` (32 000 characters from the start, 32 000 from the end). A `tool_progress` goes out every 15 s, through
`NodeTools.progress` and `Frames.progress`, which restarts the server's timeout. At the timeout, or when the thread is interrupted,
the process and its descendants are killed. `NodeSocket` keeps the thread of each running call by its id and interrupts it on a
`tool_cancel`; the call still answers, with `cancelled`. The checks of a command (`deniedProgram`: a word between the shell's
separators, without a folder or `.exe`; `deniedPath`: a denied path written out, from `~`, `$HOME`, `${HOME}` or
`%USERPROFILE%`, as a whole name) are a backstop. The gate is the question.

`FileTools.ViewImage` (`view_image`) is offered when the files are on, the policy's `images` tier (`Policy.imagesTier`: `allow`,
`folders` (the default, `null` in the file), `ask`, `off`; an unknown word is off) is not off and the node has set an
`Harness.ImageUploader`. The JavaFX and the terminal node set one when they connect: it uploads the file with
`ServerClient.uploadImage` (`POST .../images`) and answers the id. The tool refuses what is not a picture by its extension or
is over 20 MB, asks by the tier (session key: the folder), uploads the picture and answers a `ToolResult` with the id in
`images` (protocol 1.14). The server shows the picture to her after the round's results (see [A chat turn](chat-turn.md)).
`Harness.setImages` writes the tier, from **Pictures she may look at** in the dialog or `/images`.

The terminal node asks with `/yes`, `/session` and `/no` (`JoiNodeCli.askHarness`), the JavaFX node with `fx/HarnessDialogs`
(the question, and **Joi → Files and shell…**). `HarnessTest` covers the tiers, `..` and links, denied paths and the node's home,
the session, the audit, a policy that cannot be read, the shell's checks, timeout and cancel, and `view_image` by its tiers.

## The browser companion

The Firefox extension is `joi-browser/` (Manifest V2 with a persistent background page, so its native port stays open; see
DESIGN.md → Node → Browser companion). `background.js` holds the port to the native host `joi_node` and answers the node's
requests; `media.js` is injected into a tab of a granted site when a media request needs it (`window.__joiMedia`, a generic adapter
on `<video>` / `<audio>` and the Media Session, one for YouTube); `page.js` the same way for `read_page` and `page_screenshot`
(`window.__joiPage`: `read`, the main text or the selection; `measure`, the page's size and whether a password field is in the
picture); `popup.*` shows the state, grants sites (`permissions.request` of an origin, in the click) and switches pictures on
(`permissions.request` of `<all_urls>`, which `tabs.captureTab` needs). Since that permission makes Firefox answer yes for every
site, the user's sites are the extension's own list (`storage.local` `sites`, made the first time from the origins granted before;
`granted()` wants the site in it). A picture is `captureTab` as a JPEG (quality 85), scaled to at most 2000 wide; `full_page` takes
a `rect` of the whole page, cut at 16 000 high. It comes back as base64 in the result (Firefox's 1 MB limit is only towards the
extension; `NativeHost` takes up to 64 MB from it).

The path of a request:

1. A tool of `harness/BrowserTools` calls `BrowserLink.request(op, args, timeout)`: a line `{"type":"request","id","op","args"}`
   on the Unix socket `<home>/browser.sock` (mode 600).
2. `NativeHost` (started by Firefox through the launcher `browser-host.sh` that `BrowserHost.install` writes into the node's home)
   relays the line as a native message: a 4-byte length in the machine's order, then the JSON (at most 1 MB to Firefox).
3. The background page answers `{"type":"result","id","ok","data"|"error"}`, and it goes back the same way.

The host tells the extension `{"type":"node","state":"connected"|"absent"}` and retries the socket every 3 s; on `connected` the
extension says `hello`, and only then is the link `connected()`. A newer connection replaces the older one. When stdin ends the
host closes the node's connection and exits.

The tools are harness tools (`BrowserTool` extends `HarnessTool`), so they share its questions, sessions and audit. A tool is on
when `browser` is `on` in the policy, its tier is not `off` and the link is connected; `BrowserLink`'s listener calls
`Harness.toolsChanged()`, which sends a new manifest when the extension comes or goes. The tier `asked` needs to know who started
the turn: both nodes pass every `turnStarted(turn, cause)` to `Harness.turnStarted`, which keeps the last 50 turns of cause `say`. A
tool that names a tab first asks the extension for it (`tab_info`, also `pick: "media"` for the tab that plays) and refuses a denied
site (`BrowserTools.deniedSite`: the host or a subdomain of a `deniedSites` entry) before the question. The audit gets the site, not
the address. "Allow for session" covers a browser tool on every site (its session key is the tool's name): the sites are the extension's
grant and `deniedSites`, so the node does not ask per site. `media_control` is `allow` by default for the same reason (it runs a
script only in a granted tab). `Harness.browserTiers()` lists the tools with their tier and default, and
`Harness.setBrowserTier(tool, tier)` keeps a choice (a tier equal to the default is removed from `browserTools`) and sends a new
manifest; **Joi → Browser…** (`HarnessDialogs.browser`) and `/browser <tool> <tier>` (`JoiNodeCli`) use them. `read_page`
(`BrowserTools.ReadPage`) and `page_screenshot` (`PageScreenshot`, on only with an `ImageUploader`, see *The harness*) take the
tab in front (`tab_info` with `pick: "active"`: the active tab of `windows.getLastFocused` of a normal window); both may name
another; for `page_screenshot` a named tab that is not the one in front (`PageScreenshot.frontTab`), in a turn the user did not
start, is asked with `everyTime` and a warning, and the audit says `in_front`; the picture is written to a temporary file, uploaded and its id
put in `ToolResult.images`. Both mark the turn (`Harness.pageRead`): later in that turn `open_url` is asked whatever its tier, and
its `Harness.Go` has `everyTime`, so an earlier "allow for session" does not cover it; the question's warning says she read a page.
`BrowserToolsTest` covers the session across sites, the choices, reading, the picture and the stricter turn.

`BrowserHost.install` takes the Java of this run (`java.home/bin/java`) and the class path the node's classes came from (the
`URLClassLoader`'s URLs under Maven's `exec:java`, else `java.class.path`). It writes the host's manifest into
`~/Library/Application Support/Mozilla/NativeMessagingHosts` (macOS) or `~/.mozilla/native-messaging-hosts` (Linux); on Windows it
leaves the manifest in the node's home and writes the registry key `HKCU\Software\Mozilla\NativeMessagingHosts\joi_node`. The
manifest allows only the extension's id, `BrowserHost.EXTENSION_ID`.

The extension is built with `web-ext` (`joi-browser/package.json`: `npm run lint`, `build`, `run`, and `sign`, which needs
`WEB_EXT_API_KEY` and `WEB_EXT_API_SECRET` from AMO in the environment; `tools/sign_firefox_extension.sh` and the workflow `browser.yml`
sign it, see [Building](building.md#the-firefox-extension)). `BrowserCompanionTest` runs the real host between a fake
Firefox (`FakeFirefox`, pipes for stdin and stdout) and the node's socket; `BrowserToolsTest` runs the tools through it.

## Her mouth

The mouth is read from the sound, in the renderer page, so the Java node and the iOS app share one implementation and the speech servers need to tell nothing (none of
them gives phoneme timings). The player of the node plays the audio itself, so the page cannot tap it: a wav piece through one Java Sound line that stays open between pieces (`LineOutput`: starting an output device takes from a few hundred milliseconds to seconds, so a player started for each piece made the mouth lead the voice; here `speech-start` is sent when the line runs, just before the first sound is written, and the line closes after 20 s of silence), any other piece (an mp3, or a wav the JDK does not read, or no line to be had) on macOS by `afplay` in a process of its own and elsewhere by JavaFX's player, where `speech-start` is sent as the player is started and the mouth is late by `delay`; the page gets three
messages, all of them transient (not sent again to a page that opens later): `speech` (`index`, `data`: a piece, wav or mp3, in Base64, before it plays), `speech-start`
(`index`, `at`: that piece plays now, and the wall-clock time in milliseconds its sound began at, taken by the player; the page is the same machine's, and a page busy drawing (the Looking Glass's many views) or reading another piece gets to the message late, so the mouth's clock is set from `at` and not from the moment it reads it; ignored when it is more than 3 s from the page's clock) and `speech-stop` (her speech is over or was stopped). The node numbers the pieces itself (`SpeechPlayer`: the numbers of the server's
`audio.index` start again at 0 in each turn); `AvatarPane` hands the three calls to `RendererServer.speech`, `speechStarted` and `speechStopped`.

`joi-server/src/main/frontend/joi-lipsync.js` (copied into the renderer page's `generated/` by `copy-viewer.mjs`, like the viewer) reads a piece: the file is decoded with Web
Audio (`OfflineAudioContext.decodeAudioData`), and `analyze` gives a track, one frame every 20 ms with the weights of the five VRM visemes. The loudness (RMS, against the
loudest part of the piece) says how far the mouth opens; the first two formants (linear prediction of the pre-emphasized sound, the first two peaks of its envelope) say
which vowel it is, as the distance to the centers of the five vowels; a consonant (the weight of the spectrum above 3.5 kHz) opens it a little and keeps the vowel. The track is
smoothed in time. `MouthPlayer` plays the tracks against a clock: `speech-start` sets the clock (plus `delay`, 60 ms for a player that takes a moment to start), and a track
that was not ready by then joins in late. The page's loop gives `<joi-model-viewer>.setMouth(weights)` each frame, which writes the weights into the model's expressions after the
animation mixer and before the model updates, so a clip's own expression tracks do not take the mouth over; `setMouth(null)` gives it back. A model without the expressions is
left alone.

Tests: the messages (`RendererServerTest`, `RendererBridgeTests`). The analyzer was tried on synthetic vowels (a pulse train through three resonators: all five were found) and on
a macOS voice (`say`), and the page with `RendererPreview`'s `speak <wav>` command and a body from the cache, which moves the mouth without a server or a player.

## Her face

Her eyes and her expression are a layer of the viewer (`<joi-model-viewer>`, `setFace`), like the mouth: off until a page calls it (the server's dialogs, which try
expressions one by one, need none), and run each frame after the animation mixer and before the model updates, so a clip does not take them over. The node page turns
it on at the start. In the server's dialogs an animation with a face turns it on for itself, and the next animation without one turns it off again, so the
dialogs that try expressions get the face back. The layer has three inputs, the first that says something wins: **the animation's face** (`sequence.face`: `{eyes, expression}` of the animation
being played, from its `.joia`), **her state's eyes** (`eyes`: `{eyes: "auto"|"closed", ease}`) and **her mood** (`mood`: `{mood, expression, weight, eyes}`, the server's `mood` frame as it is: the mood's face is its own setting, so the page has no table
and a mood of the user's works). Over all of them for the expression, not the eyes: **an expression she chose** (`shown`: `{expression, weight, seconds}`, the
server's `expression` frame, her `set_expression`; held whole until its time is up, then the layer fades it out; `null` ends it). The messages `eyes` and `mood` are kept and sent again to a page that opens
later (`RendererServer.eyes` / `mood`); the node says them from the director: `AvatarPane.state` (sleeping: closed in 0.4 s, falling asleep: closed over 3 s,
waking up: open over 1.5 s, else open) and the `mood` frame (`NodeSocket.Listener.mood`, sent after the manifest and on each change of her mood: the Mood today box, her `set_mood`, the roll). The iOS app does the same (`ChatModel.state`, `RendererBridge.setEyes` / `setMood`).

What the layer does: the lids go to the target (closed 1, half 0.55) with an exponential ease; while the eyes are `auto` a blink (0.2 s, one in five a double) comes every 2 to 6 s on the
`blink` expression; the emotion expressions (`happy`, `angry`, `sad`, `relaxed`, `surprised`) fade to their weights over about a second (three-vrm applies the models' own
override of blink and mouth). The mood's expression is held for hours, so it leaves the eyes to the state and the blinking: the layer registers a copy of the
model's expression without the shape keys of the eyes and lids (`_withoutEyes`: names with eye, blink, wink, 目, まばたき, the brows kept), as many models' happy and relaxed shut
the eyes; an animation's expression is short and stays whole. The VRM `lookAt` follows the camera plus a small random offset that changes every 0.6 to 2.5 s, and nothing while the eyes are closed. A model without
the expression ignores it. The animation's `eyes` and `expression` are fields of `AnimationPack` (extended attributes, so no migration; `AnimationFace` keeps the allowed values and
drops the others), of `JoiaMetadata.Animation` (the `.joia`), and set in the animation's dialog (`AnimationPackDialog`, whose preview shows them: `ClipPreview.setFace`).
The `expression` message is not kept (`RendererServer.expression`): it is over before a page that opens later would show it. The node passes it on from
`NodeSocket.Listener.expression` through `AvatarPane.expression`; the iOS app from `ChatModel` through `AnimationPlayer.showExpression`.
Tried with `RendererPreview`'s `eyes <auto|closed> [seconds]`, `mood <expression|none> [weight] [half|wide]` and `expression <name|none> [weight] [seconds]` commands.

