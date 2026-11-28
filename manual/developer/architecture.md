---
label: Architecture
icon: versions
order: 790
covers:
  - DESIGN.md
  - pom.xml
verified: dce9fc6
---

# Architecture

```
joi-server ("brain")                                joi-node ("ears, hands, face")  one per machine
 - LLM tool loop, model roles                WS     - her body's files, cached by hash
 - memory, moods, events            <------------>  - the chat, talking to her, her voice
 - TTS through speech servers                       - tools of the machine, with their tier
 - bodies, animations, conversion                   - answers to the questions of her tasks
 - Vaadin UI, HTTP API                                       |
                                                              | local HTTP + server-sent events
                                                     renderer page (three.js + VRM)
                                                     in Chromium (JCEF), or in the user's Chrome
                                                     for the Looking Glass
```

## The server

A Java 25 WAR on Marginalia's platform (see `MARGINALIA.md`): Spring wired in XML with no Spring Boot, Hibernate on SQLite with Flyway,
and Vaadin Flow for the UI with no separate REST API for it. It owns everything: the conversation, her memories and summaries, her bodies, the
converted models, animations, speech, and the permission to use tools. It runs the model calls, so the same server serves any
number of nodes.

A turn of hers is a chain of asynchronous steps (see [A chat turn](chat-turn.md)). Server-side tools (memory, web, bodies, animations)
are functions the turn offers; the nodes' tools join each turn through a listener while the node is connected.

## The nodes

A node is any client of the node WebSocket (`/api/v1/node`). The reference node is Java, so it runs on macOS, Windows and Linux. It
holds no brain: it downloads her body (`.joim` and `.joia`) by hash, draws it, plays her speech, offers its tools, and asks the user
about the tasks that wait. Its harness (files and the shell of its machine) is decided on the node alone, under a policy the server cannot change. A Firefox extension (`joi-browser/`) reaches the node on its machine through native messaging, so her tools can work in the user's browser. See [Writing a node](nodes.md). A phone is planned as another node, a native Swift app (`joi-ios`) whose protocol types are generated from the
schemas (see [The protocol](protocol.md#the-swift-package)); it cannot be Java, and it brings the OS's own tools (Shortcuts, App Intents).

## The renderer

Her body is drawn by a web page, `joi-model-viewer.js` (three.js and `@pixiv/three-vrm`, `@pixiv/three-vrm-animation`), the same code the
server's UI uses for its previews. In the node the page is served on `127.0.0.1` (a random port and a secret path), driven over
server-sent events, and shown by Chromium (JCEF) in the JavaFX pane. On a Looking Glass the page runs in its Looking Glass mode in the
user's own Chrome, full screen on the display. The iOS app shows the same page in a `WKWebView`, with no server: it calls the page and the page tells it back (see [The iOS app](ios-app.md)). Her mouth is read from her voice by the page itself (`joi-lipsync.js`; see *Her mouth* in [Writing a node](nodes.md)).

## The protocol

The node protocol is a public API: JSON Schemas and an AsyncAPI document are the source of truth, and the Java records follow
them (see [The protocol](protocol.md)). The server treats every node the same.

## Marginalia underneath

Joi uses Marginalia's platform module: entity base classes, users and permissions, the AI / inference layer and its OpenAI
compatible implementation, function and tool definitions, templates, GPU locks, jobs, trash, backups and the generic UI widgets.
Marginalia is a submodule pinned at a commit on its `dev` branch. Bump it on purpose and never edit it from this repository.
`MARGINALIA.md` lists what Joi uses and what it provides itself.

## Data

One data folder (`JOI_DATA`, default `~/.joi`) holds the SQLite database, `secret.key` (AES-GCM for stored secrets) and the files of the
users as platform `Resource`s. Files go through the disk, never through memory: uploads stream into a file, downloads stream
from a file, exports and imports are streams through temp files. A file that is on the disk becomes a `Resource` with `ResourceService.saveFile`
(the platform hashes it, shares the file of an equal one and picks the folder); a service never builds a `Resource` by hand, and an upload handler writes into
`ResourceService.newUploadFile()`.
