---
label: Text models
icon: comment
order: 640
---

# Text models

Open **Models → Text Models** and press **Add model** (the pencil edits, the copy button clones, the trash deletes).

## The fields

| Field | Meaning |
|---|---|
| **Type** | *OpenAI Compatible*: any server that speaks the OpenAI chat protocol |
| **Name** | what you call it in lists |
| **OpenAI URL** | the server's address, ending with `/v1` |
| **Api Key** | the key, if the server needs one. It is stored protected and never shown again; leave the field empty to keep it |
| **Model** | the model's id on that server |
| **Used for** | the roles: Chat, Deep reasoning, Auxiliary (several allowed) |
| **Max Context Size**, **Max Response Tokens** | the limits of the model |
| **Enable Reasoning**, **Reasoning Effort** | whether and how much the model thinks, for models that support it |
| **Send the thinking back** | whether the model's thinking goes back to it in the next request (none, between tool calls, always); most models want none |
| **Multimodal (accepts images)** | the model takes images. Needed to describe a body and an outfit |
| **Image placement**, **Function definitions** | where images and function definitions go in a request, for models that care |
| **Function call rule**, **Rule placement** | a sentence added to every request that offers functions, such as *You act only through function calls*, for models that write a call as text instead of making it |
| **Ask again when a reply opens with a call written as text** | holds back the first words of a reply and asks again when it opens with a written call |
| **Connections** | how many requests may be on the way at once (1 for a server with a single slot) |
| **Timeout**, **Retries** | request limits |
| **GPU lock** | the GPU lock the model runs under, when models and speech servers share a card |
| **Shared with all users** | administrators only |

**Test Connection** asks the server and shows the answer or the error.

## Which role needs what

- The **Chat** role needs a model that calls functions. Her tools (animations, outfits, memory, tasks) are functions.
- The **Deep reasoning** role runs the tasks she starts (searching, reading a page) with loops of function calls. A model
  with strong reasoning is better here. It is also the first choice for describing images when it is **Multimodal**.
- For [appearance](../bodies/appearance.md) and the [outfit photoshoot](../bodies/outfits.md) one of your deep reasoning or chat
  models must be marked **Multimodal**.

## Thinking and chat

A model that thinks before it answers is slower to the first word. For the conversation role many people turn thinking off in
the model's settings and leave the thinking to the deep reasoning model.
