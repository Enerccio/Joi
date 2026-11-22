---
label: Desktop app
icon: desktop-download
order: 995
---

# Desktop app

The simplest way to run Joi on one computer: one installation with the server and the node in it, no Java to install, no
database to set up, no token to make. You get two programs:

- **Joi**, the server. It runs in the background with an icon in the tray (the menu bar on a Mac): its web page is where you make
  your account, your assistants, her body and her voice.
- **JoiNode**, the node: her body next to the chat, her voice, and what she can do on this computer. The tray icon starts it, and
  it starts with Joi unless you turn that off.

The node and the server on the same computer talk over a **Unix socket** in the data folder instead of the network. Nothing needs
a key or a login for that: whoever can open the folder may use the socket, and that is you alone. The web page is still behind its
login, like a server's. Other nodes, such as a phone or the node of another computer, connect over the network with a token or by
pairing as usual: see [Access tokens](../access.md).

## Install

Take the file for your system from the release page of the project (`joi-<version>-<system>-<cpu>`):

| System | Installer | Without installing |
|---|---|---|
| macOS (Apple silicon or Intel) | `.dmg`: drag Joi to Applications | `.tar.gz`: the portable folder; `-app.tar.gz`: Joi.app |
| Windows 10 or newer (x64) | `.msi`: installs for your user, with a Start menu entry | `.zip` |
| Linux (x64 or arm64) | `.deb` | `.tar.gz` |

The app is **not signed** with a developer certificate. macOS (and Windows SmartScreen) therefore warn on the first start: on a Mac,
open it with a right click and **Open**, or allow it in System Settings → Privacy & Security. The microphone is asked for the
first time the node listens.

The portable folder runs from where you unpack it: `bin/joi` (`bin\joi.bat` on Windows) starts the server, `bin/joi-node` the node.

## First start

1. Start **Joi**. The first start takes a while (the web application unpacks itself); the tray icon says when it is running, and
   the web page opens in your browser at `http://127.0.0.1:8766`.
2. Make the first account (the administrator): see [First start](first-start.md).
3. The node starts by itself and joins the server over the socket. While there is no account yet it says so and waits. Then it
   lists your assistants; [make the first one](first-assistant.md).

There is no third step for the node: it is not asked for a server address or a token.

## The tray icon

| Item | What it does |
|---|---|
| Open Joi in the browser | opens the web page (a click on the icon does the same) |
| Open the Joi node | starts the node; if one already runs it says so: only one runs at a time |
| Start the node with Joi | whether the node starts together with Joi (on by default), kept for next time |
| Quit Joi | stops the server and the node that Joi started (a node you started yourself is left alone) |

On a desktop with no tray, a small window with the same buttons takes its place.

## Options

`joi --help` lists them:

| Option | Meaning |
|---|---|
| `--port <port>` | the port of the web page (default 8766; a free one is picked when it is taken, unless you gave it) |
| `--host <address>` | the address it listens on (default `127.0.0.1`; anything else shows the page to your network without HTTPS) |
| `--data <folder>` | the data folder (default `JOI_DATA`, else `~/.joi`) |
| `--no-browser` | do not open the browser |
| `--no-node` | do not offer or start the node |
| `--no-tray` | no tray icon or window; stop it with Ctrl+C |

Extra Java options (for example `-Xmx4g`) go in `JOI_JAVA_OPTS` for the portable scripts.

## Where things are

- **The data folder** is the same as the server's: `~/.joi`, with the database, your uploads and the key that protects your stored
  secrets. The desktop app adds `desktop/` to it: the log (`desktop/logs/joi.log`, and `node.log` for the node), the unpacked
  web application and the socket `desktop/api.sock`. Back the folder up as a whole while Joi is stopped.
- **The node's home** is `~/.joi-client`, as for any node: its settings, her body's cache and the unpacked Chromium. If you also use
  the node against another server, it keeps one login per server there.
- A very long data folder path does not fit a Unix socket (about 100 characters). The socket is then made in the system's temporary
  folder instead, in a folder only you can open; the log says where.

## What is not in it

- **Blender.** Converting a model (FBX, a Unity package) or an animation clip (FBX, BVH) needs Blender with the VRM add-on, which is
  too big to ship: install it and set `JOI_BLENDER`, or put Blender.app in Applications on a Mac. Models that are already VRM, and
  `.joim` files, need nothing. See [Bodies](../bodies/index.md).
- **ffmpeg** is optional, for some speech formats: install it and it is found on the path (`JOI_FFMPEG` names it otherwise).
- **A model server** for her thinking and her voice: see [Your first assistant](first-assistant.md).

## Updating and removing

Install the new version over the old one. The data folder is kept, and the server upgrades its database when it starts. To remove
Joi, uninstall it (or delete the folder) and, if you want everything gone, delete `~/.joi` and `~/.joi-client`.

## If it does not start

- Look at `~/.joi/desktop/logs/joi.log`.
- *Port 8766 is used by another application*: you gave `--port`; leave it out, or pick another.
- *Another Joi runs with the data folder*: it is already running, look in the tray, or quit it first.
- The tray icon is missing on Linux: some desktops hide tray icons without an extension; the window or `--no-tray` still works.
- The node says it waits for an account: open the web page and make the first one.
