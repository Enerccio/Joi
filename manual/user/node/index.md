---
label: Nodes
icon: device-desktop
order: 550
expanded: true
---

# Nodes

A node is a program on one of your machines that connects to the server. It is where she appears, where you talk to her, and
what gives her hands on that machine.

| Node | What it is |
|---|---|
| [The JavaFX node](javafx.md) | a desktop app: her body next to a text chat, her voice, her questions to you |
| [Talking to her](voice.md) | the JavaFX node writes down what you say, on your computer, and sends the words |
| [The terminal node](terminal.md) | the same conversation in a terminal, with what her body would play printed |
| [The Looking Glass](looking-glass.md) | the JavaFX node can move her onto a Looking Glass display |

You can run several nodes at once, one per machine (a second node on the same machine and home is refused: only one runs at a time).
The node of the [desktop app](../getting-started/desktop-app.md) joins the server on its own computer over a local socket, with no token;
a node on another computer or a phone still connects over the network. Each connects for one assistant. The same node connecting again for her
replaces its old connection.

## What a node offers

- **Her body**: the node downloads the `.joim` and `.joia` of her active body, keeps them by hash, and draws her with her
  animations: idle while she waits, talking while she speaks, asleep, falling asleep and waking up.
- **Her voice**: the speech the server makes is played back.
- **Your voice**: the JavaFX node can listen when you press **Talk**, and sends the words (see [Talking to her](voice.md)).
- **Tools**: when a node connects it sends a list of the tools it offers, and she sees them as `<node>__<tool>` while it is
  connected. `notify` shows you a notification on this machine, and `offer_to_clipboard` offers you a text for the clipboard; she has
  each only if you allow it. See [Notifications](notifications.md) and [Clipboard offers](clipboard.md). The node's harness lets her read and write files and run commands on this machine, under your policy: see [Files and shell](harness.md). A tool is marked Allow, Ask or Deny by the node itself, which decides again when it runs.
- **Your answers**: when a task of hers waits for your approval, the node asks you. See [Answering her questions](approvals.md).

## Pairing and tokens

A node signs in to the server with a token you made, or by the device grant, or with nothing on a development server. See
[Your first node](../getting-started/first-node.md) and [Access tokens](../access.md). The administrator decides which of the
ways the server offers, and you can revoke any node's credentials in **User Settings → Access**.

## When it is connected, and when it is not

On connect, if she has never talked she wakes up for the first time; otherwise she greets you. If the connection drops (the server
restarts, the network goes), the node connects again after 1, 2, 4... seconds, up to 30, and does not greet you again. While it
is not connected the chat, Send, Talk and Stop, her parts and the Looking Glass button are disabled, and she stands still and gray. A bar
above the input counts down to the next try, with **Reconnect now**.

A reply that she was already writing is kept in the conversation when the connection is lost after she wrote something or
changed something; one that had only thought so far is dropped. After the server was down, she is told when the first node comes
back.
