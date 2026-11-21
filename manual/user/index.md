---
label: User guide
icon: person
order: 900
expanded: true
---

# User guide

This part of the manual is for people who run Joi and live with her: installing the server, making an assistant, giving
her a body and a voice, and connecting a node on the computer she should be on. It explains every screen and setting.

## How Joi fits together

Joi has three parts.

- **The server** is her brain. It keeps the conversation, her memories, her moods, her bodies and animations, runs the models
  you configured, makes her voice, and offers its own tools such as web search. You use it in a browser: everything you
  set up is in the server's workspace.
- **Nodes** are where she appears. A node runs on one machine, connects to the server, shows her body next to a chat, plays
  her voice and offers that machine's tools to her. You can run several nodes; the server tells her which are connected.
- **Her body** is a 3D model (VRM) that the server prepares. A node downloads it once, keeps it by its hash, and draws it
  with her animations.

```
   your browser  ───►  server (brain)  ◄───  node on the desk   (body, chat, voice, tools)
                         │   ▲              node in a terminal  (chat)
        text models ◄────┘   └──── speech server
```

You talk to her in a node. What you change in the server's workspace (her name, her body, her outfit, a part she wears) she
is told about, once you have talked, so she can react to it in her own words.

## Where to start

1. [Getting started](getting-started/index.md): install the server, sign in, add a model, create an assistant, connect a node.
2. [Models and voices](models/index.md): the text models she thinks with and the speech she talks with.
3. [Assistants](jois/index.md): her persona, memory, conversation, events and tasks.
4. [Bodies](bodies/index.md) and [Animations](animations/index.md): how she looks and moves.
5. [Nodes](node/index.md): the JavaFX node, the terminal node and the Looking Glass.
6. [Server tools](tools/index.md): what she may do on the server, and who decides.

The pages [User settings](settings.md), [Access tokens](access.md), [Privacy mode](privacy.md), [Trash](trash.md) and
[Troubleshooting](troubleshooting.md) cover the rest of the workspace.
