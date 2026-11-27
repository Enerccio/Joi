---
label: The JavaFX node
icon: browser
order: 540
---

# The JavaFX node

The JavaFX node is a desktop app. It needs JDK 25 or newer; the build fetches OpenJFX and an embedded Chromium that draws her body.

```bash
mvn -Pfx compile exec:exec@run -pl joi-node
```

Settings are in the node's home, `~/.joi-client` (`--joi-home-dir <folder>` or `JOI_HOME_DIR` name another one; `JOI_CLIENT` is the old name of the variable): `node.json` holds the node's id, name, the server and the assistant
chosen last, and `credentials.json` holds the credentials for each server, readable by you alone. Her body is cached in
`cache`.

## Started by the desktop app

The [desktop app](../getting-started/desktop-app.md) starts this node with `--local <socket> --web <url>` (or the variables `JOI_LOCAL_SOCKET`
and `JOI_WEB_URL`): the node then has no server screen. It asks the server of its own computer for a token over the socket, so the first
screen is a status line: *waiting for an account* until you have made one in the web page, then your assistants. Two buttons stay on it:
**Open Joi in the browser** and **Another server...** (the usual first screen). Only one node runs per home: starting a second says so and
ends.

## The screens

1. **The server.** The address as `http://host:8080` (a list of the servers you used before, the last first), a field for a token
   (leave it empty to pair), **Connect** and **Forget this server**. See [Your first node](../getting-started/first-node.md).
2. **Pair this node**, when pairing: the code and where to enter it.
3. **Who?** your assistants, and **Open**. The one chosen last is opened straight away while she exists.
4. **Loading**: her body downloads (checked by hash before it is kept), unpacks, and her renderer starts.
5. **Her**: the chat next to her body.

## Her screen

![The node with her](../../images/node-chat.png)

- **Her body** is drawn by the embedded Chromium. A line shows her state (waiting, talking, asleep...) and the animation that
  plays. **Open in browser** shows the same page in your browser.
- **The chat** starts with her history from the server, so a conversation goes on where it was. Type, press **Send**; **Stop**
  interrupts her. Her reply streams in; when she has a voice, the text of a reply waits for its first piece of speech so the
  chat does not run ahead of her. Her voice plays as it arrives. **Talk** listens to you instead of the keyboard: see [Talking to her](voice.md).
- **Images.** The **Image…** button next to the field chooses an image file; **Ctrl+V** / **Cmd+V** pastes one from the clipboard (when it
  holds a picture and no text). The images wait as small pictures with a ✕ to take one away; **Send** (or **Talk**) sends them with your words, or alone (at most
  four, 20 MB each). The button is off when she cannot look at images: see [Chats](../jois/chats.md#showing-her-an-image).
- **The controls** panel between the chat and her body (see below).
- **On the Looking Glass** moves her onto a Looking Glass display: see [The Looking Glass](looking-glass.md).
- Messages of the system show as notes in the chat.

Her mouth follows her voice: while a piece of speech plays she opens and shapes it to the vowels in the sound (the five visemes of the
VRM, `aa`, `ih`, `ou`, `ee` and `oh`), and it closes when she stops or is talked over. This needs a body whose model has those expressions
(a model converted from a VRChat avatar has them); one without them speaks with a still mouth. The mouth is read from the sound itself,
so it needs nothing from the speech server and works with any voice.

Her face is alive too: she blinks every few seconds and looks at you a little unsteadily. Her eyes follow what she does: shut while she
sleeps (slowly as she falls asleep, opening slowly as she wakes up), open the rest of the time. Her mood shows as a soft expression
(happy: a smile, grumpy: a frown, sleepy: half closed eyes, excited: wide eyes and a big smile...), taken from the mood's settings, so it changes with the day's mood, as soon as the mood changes. The mood's expression
never closes her eyes (on many models a happy or relaxed face does): only her sleep and the mood's own eyes setting do.
An animation can set her face itself (see [Animations](../animations/animations.md)); while it plays that wins.
She can also show a feeling of the moment herself, with her function `set_expression`: a smile at a joke, surprise at news. She picks
one of the expressions her model has and holds it for a few seconds (4 unless she says otherwise, at most a minute), over her mood's
face and over an animation's expression; then her face goes back to her mood's. It does not change her eyes.

When she waits she plays one of her idle animations, another each time; while she speaks, one of her talking ones. She plays
the animation she picks herself once on top.

### Her parts

![The controls panel](../../images/node-controls.png)

**Her parts** in the controls panel has a box for a part that is shown or hidden, or a choice for a group's states. Your changes are
held back until you leave the parts alone for about four seconds, then sent together, and the controls are locked until the server
answers. The server keeps the state on her body, so it is the same on every node and at the next start, and she is told.

If you typed words in the chat input when the change is about to be sent, the node asks **Send your message with the change?**:
**Send together** sends your message with the change as one thing to her, **Change only** sends just the change.

If her personality gives her a say in it, she may refuse a change: the control goes back to how it was.

**Her parts: when to show** is the checkbox **Show changes with her reaction**. Off, the change shows as soon as the server has it.
On, it shows when she reacts to it.

If her body has no parts to take off or put on, the panel says so. A change made in the server's UI shows on the node as she is
read again by it.

## The menus

| Menu | Items |
|---|---|
| **Edit** | Cut, Copy, Paste, Select all |
| **Joi** | **Auto-approved tools…** (see [Answering her questions](approvals.md)) **Notifications** (see [Notifications](notifications.md)) **Clipboard offers** (see [Clipboard offers](clipboard.md)) **Files and shell…** (see [Files and shell](harness.md)) and **Browser…** (see [Firefox](browser.md)) |

## While she is away from the server

When the connection is lost the chat and controls are disabled, she is frozen and gray, and a bar says when the next try is, with
**Reconnect now**. When it is back she goes on.
