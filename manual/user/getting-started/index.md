---
label: Getting started
icon: rocket
order: 1000
expanded: true
---

# Getting started

The way from nothing to talking with her, in order:

1. [Install the server](server.md) and start it.
2. [First start](first-start.md): make the administrator account and look around the workspace.
3. [Your first assistant](first-assistant.md): add a text model, create her and choose her models.
4. [Your first node](first-node.md): connect the node and say hello.

Then give her a body in [Bodies](../bodies/index.md), a voice in [Speech](../models/speech.md), and animations in
[Animations](../animations/index.md).

## What you need

| You need | For |
|---|---|
| Java 25 or newer | running the server and the nodes |
| Maven 3.9 | building from source |
| A text model server | her thinking: any OpenAI compatible endpoint (llama.cpp, LM Studio, Ollama, vLLM, OpenRouter, a hosted API) or an Anthropic or Google model |
| A speech server (optional) | her voice: Chatterbox-TTS-Server, or ComfyUI with Higgs Audio v3 |
| Blender with the VRM add-on (optional) | converting FBX avatars and FBX or BVH animations; not needed for models that are already VRM |
| Google Chrome (optional) | showing her on a Looking Glass |

A model that takes images (a multimodal model) is needed for the [appearance](../bodies/appearance.md) of a body and for
the outfit photoshoot.
