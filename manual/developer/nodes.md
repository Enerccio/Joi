---
label: Writing a node
icon: device-desktop
order: 710
covers:
  - joi-node/src/main/java/com/github/enerccio/joi/node
  - joi-server/src/main/java/com/github/enerccio/joi/domain/node
  - joi-server/src/main/java/com/github/enerccio/joi/api/NodeEndpoint.java
verified: 3dd8af1
---

# Writing a node

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
3. **Talking.** `say` (text, speak, reasoning); the frame's id is the turn, and the answer is the streamed chat as frames that refer to it: `reasoning`, `text`, `tool`,
   `reply`, `audio`, then `done`, or `error`. `stop` ends a turn. The server starts a turn itself with `turn` (an id and a cause), answered the same way: after the
   first manifest of a connection she wakes up for the first time or greets the user who is back, but not for a node that only lost the connection
   (`hello.resumed`).
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

## The reference node

`joi-node` (`com.github.enerccio.joi.node`):

| Class | What |
|---|---|
| `ServerClient` | the HTTP API: login, the device grant, her, her body, the downloads cached by hash |
| `NodeSocket` | the node WebSocket, with reconnecting |
| `AvatarDirector` | what her body plays: idle, talking, asleep through her first turn, waking up, a gesture on top |
| `NodeSettings` | the node's home (`~/.joi-client`, moved by `--joi-home-dir`, `JOI_HOME_DIR` or `JOI_CLIENT`: `NodeSettings.resolve`), `node.json` and `credentials.json`; every path of the node is under it |
| `NodeTools`, `NodeTool` | the tools the user switches on: the manifest with the ones that are on (a new, higher version when a choice changes), the routing of a call by its name, the `approval` frames of a call that asks |
| `Notifications` | the tool `notify`: off, ask or allow |
| `ClipboardOffers`, `DesktopClipboard` | the tool `offer_to_clipboard`: off, ask or allow; the clipboard of the terminal node |
| `TaskQuestions` | the tasks that wait for the user's answer |
| `PartStaging` | the staging of her parts |
| `local/UnixHttp`, `local/UnixWebSocket` | HTTP/1.1 and the node WebSocket over a Unix domain socket (the JDK's clients cannot connect to one): see below |
| `LocalMode`, `NodeInstance` | `--local <socket>` / `--web <url>` (or `JOI_LOCAL_SOCKET`, `JOI_WEB_URL`), the lock `node.lock` of one node per home |
| `JoiNodeCli` | the node in a terminal: the smallest complete example |
| `renderer/` | her body's files unpacked (`BodyFiles`) and served to the renderer page (`RendererServer`), the page's source in `src/main/renderer` |
| `fx/` | the JavaFX app: `JoiNodeApp`, `AvatarPane`, `ControlsPane`, `SpeechPlayer`, the Chromium host and the Looking Glass |

Start with `JoiNodeCli`: it logs in (token, device grant or none), picks her, downloads her body into the cache, connects, chats, answers the questions of tasks and
offers `notify` and `offer_to_clipboard`.

## The server on the same machine

A server address `unix:/path/to/api.sock` (`ServerClient.UNIX`) is the server's local socket (see [The desktop app](desktop.md)). `ServerClient` then talks through
`UnixHttp` (one connection per request, `Connection: close`, `Content-Length`, chunked or read-to-EOF bodies, streamed downloads to a file, a watchdog for the timeout,
refusal of line breaks in header values and of anything but a plain path), signs in with `signInLocal()` (`POST /oauth/local` with `client_id` `joi-node-<node id>`; no refresh
token: `refresh()` signs in again) and `authorization()` mints a new token when the one in hand expired. `NodeSocket.localSocket(Path)` opens the WebSocket with `UnixWebSocket`
(a client of RFC 6455 for `java.net.http.WebSocket`'s listener interface: masked frames, fragments, ping and pong, the close handshake) at `ws://localhost/api/v1/node`, and
`NodeSocket.authorizer(...)` supplies a fresh `Authorization` header on every reconnect, because the token of the first connection is an hour old by then.

The node of the desktop app is started with `--local` (`LocalMode.parse`); `JoiNodeApp.showLocal` is its first screen (it waits for the first account, retrying every 3 s).
`fx/Launcher` takes `NodeInstance.acquire(home)` before it starts the app and ends with code 3 when another node holds the lock of this home; the launcher of the server
looks at the same lock (`NodeInstance.running` is the model of the check it makes without a dependency on this module).

## A tool the user switches on

`notify` and `offer_to_clipboard` are `NodeTool`s in one `NodeTools`: `offered()` is the definition or null, so a tool that is off is not in the manifest and she does not know it exists; `NodeTools.changed()` sends the manifest again with a higher version when a choice is saved. A tool that asks the user goes through `NodeTools.ask`, which sends the `approval` frames (`asked`, then `allowed` or `denied`) around the answer; no answer in two minutes is a no. The user's choice is in `node.json` (`notifications`: `off`/`ask`/`allow`; `clipboard`: `off`/`ask`/`allow`; anything else is off).

`allow` of the clipboard tool copies without showing the text: what lands on a clipboard is pasted into a terminal or a form later, and the text can come from a web page through a task, so it is the user's informed choice (off by default, `ask` is the recommended one, the docs warn). The terminal node asks with `/yes` and `/no`, the JavaFX node with a dialog that shows the text (`askClipboard`), and copies on the JavaFX thread. `offer_to_clipboard(text, title?)` takes up to 100 000 characters (`ClipboardOffers.MAX_TEXT`, the same as a file of the server's workspace).

The server can call a node's tool itself, not only through her function `<node>__<tool>`: `NodeService.sessionsWithTool` lists the nodes that offer it (the one with a turn running first) and `NodeService.callTool` runs it, with the turn id and the same timeout and cancellation. `offer_file_to_clipboard` (see [Server tools](tools.md)) is built on that.
