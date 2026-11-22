---
label: Developer guide
icon: code
order: 800
expanded: true
covers:
  - DESIGN.md
verified: 203475b
---

# Developer guide

For people who build Joi from source, change it, write a node, or call its API. The pages are written against the code and
carry the commit they were last checked against (`tools/check_dev_docs.py` lists the pages whose code changed since).

`DESIGN.md` at the root of the repository is the design and the source of truth for decisions; these pages describe what the
code does.

## The modules

| Module | What it is |
|---|---|
| `joi-protocol` | the public API: JSON Schemas, `openapi.yaml`, `asyncapi.yaml`, the `.joim` and `.joia` containers, and the Java records that follow them. Gson only |
| `joi-server` | the server (the WAR): the LLM tool loop, memory, moods, bodies, events, the API and the Vaadin UI |
| `joi-node` | the reference node: server client, node socket, avatar director, the terminal node and the JavaFX app with its renderer |
| `joi-desktop` | the desktop app: the server with a tray icon and the node in one installation (jlink, jpackage), behind the root POM's profile `desktop` |
| `joi-ios` | the iOS app (planned): so far the protocol in Swift, `Packages/JoiProtocol`, generated from the schemas. Outside the Maven reactor for now |
| `marginalia/` | a git submodule of Marginalia; only its platform module is used |
| `manual/`, `screenshots/` | this manual and the scripted environment that takes its pictures |

## Where to start

| You want to | Read |
|---|---|
| build and run it | [Building](building.md) |
| package it for one computer | [The desktop app](desktop.md) |
| see how the pieces fit | [Architecture](architecture.md) and [Project structure](project-structure.md) |
| change the UI | [The UI](ui.md) |
| change how she answers | [A chat turn](chat-turn.md) |
| add a server tool | [Server tools](tools.md) |
| touch models, outfits or animations | [Bodies and models](bodies.md) |
| change what she is told | [Events](events.md) |
| see what the server does and for whom | [Jobs](jobs.md) |
| add features without changing the server | [Extensions](extensions.md) |
| call the API or write a node | [The protocol](protocol.md) and [Writing a node](nodes.md) |
| work on the iPhone app | [The iOS app](ios-app.md) |
| write tests | [Testing](testing.md) |
| retake the pictures of the manual | [Screenshots and the manual](screenshots-and-manual.md) |
