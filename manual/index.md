---
label: Home
icon: home
order: 1000
---

# Joi

Joi is a self-hosted personal AI assistant. A Java server is her brain (the conversation, memory, moods, bodies, events
and tools), and a node on each of your machines is where she can be seen, heard and useful: it shows her avatar, plays her
voice, and offers the machine's tools to her.

You run the server yourself, on your own machine or network, and bring your own models: any OpenAI compatible server, a
local one or a hosted one. Nothing is sent anywhere you did not set up.

## What it does

- **Assistants** with their own name, personality, moods, memory, voice, bodies and conversation. You can have several.
  See [Assistants](user/jois/index.md).
- **Bodies**: 3D models (VRM) with outfits, parts she can take off and put on, and spring bones that sway as she moves. See
  [Bodies](user/bodies/index.md).
- **Animations**: clips you upload (VRMA, FBX, BVH) put together into animations that she plays when she waits, talks,
  falls asleep or wakes up, and when she decides to. See [Animations](user/animations/index.md).
- **Speech**: she talks through a speech server of yours (Chatterbox or Higgs Audio) with a voice you give her. See
  [Speech](user/models/speech.md).
- **Server tools** with a permission chain (administrator, you, her), such as web search and reading pages. See
  [Server tools](user/tools/index.md).
- **Nodes**: the JavaFX node shows her next to a chat, the terminal node chats from a shell, and the Looking Glass shows her
  as a hologram. See [Nodes](user/node/index.md).
- **An open API** for your own nodes and scripts. See [The protocol](developer/protocol.md).

## Which guide do you need?

|                                           |                                                                                 |
|-------------------------------------------|---------------------------------------------------------------------------------|
| **[User guide](user/index.md)**           | You want to run Joi and live with her. Start with [Getting started](user/getting-started/index.md). |
| **[Developer guide](developer/index.md)** | You want to build Joi, change it, write a node or call the API.                 |
