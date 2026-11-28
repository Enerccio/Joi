---
label: Extensions
icon: plug
order: 920
---

# Extensions

Extensions add to Joi without changing the server: model providers, and features that bring their own tools for her or tabs of their own.
They are JARs that an administrator loads, stops and unloads at runtime, see [Managing extensions](../administration/extensions.md). Four
come with Joi:

| Extension | What it adds |
|---|---|
| [Anthropic Claude](#anthropic-claude) | Anthropic's Claude models as a provider, over Anthropic's own API. |
| [Google Gemini](#google-gemini) | Google's Gemini models as a provider, over Google's own API. |
| [xAI Grok](#xai-grok) | xAI's Grok models as a provider, over xAI's own API. |
| [Marginalia's books](marginalia-books.md) | She reads your books in Marginalia, the writing application, through its API. |

!!!warning What she says goes to the provider
Every request of a model that uses one of these providers (her prompt with what she remembers, the summaries, what you say and what she
answers, pictures she is sent) goes to that company's servers. Their terms decide what happens to it there. Do not use such a provider for
a conversation that must stay in the house. An administrator who shares a model of such a provider sends what everybody who uses it says.
!!!

## A provider, once it is loaded

Load the extension (the JAR is large: it contains the provider's own library). The **Type** list of the text model's dialog then has an
entry **Extension**, and the **Provider** list at the top of its *Per Type Settings* tab offers the provider. Add a text model as usual
(see [Text models](../models/text-models.md)): choose the type **Extension** and the provider, fill in the general settings (context size,
response size, reasoning) and the settings below, and use **Test Connection**.

Each provider has these fields:

| Field | |
|---|---|
| **Api Key** | The key of the provider's console. It is stored protected and never shown again; click **Reset** to enter another one. |
| **Model** | The model to use. **Refresh** lists the models your key can use; you can type a name that is not in the list. |
| **URL** | Optional. Empty uses the provider's own API; set it to send the requests through a proxy. |
| **Timeout (seconds)** | How long to wait for the provider to answer, and for more text once it started. The default is 300. |
| **Retries** | How often a request is repeated after a rate limit (429), a server error (5xx) or a connection that could not be made. The default is 2, 0 turns it off. |

Models of an extension stay in the list when the extension is unloaded (the type shows *Extension: <name>*) and work again when it is
loaded. While it is not loaded, an assistant that uses such a model cannot chat: the error says to load the extension or to choose another
model.

## Anthropic Claude

Claude models over the Claude API. Get a key in the [Claude Console](https://platform.claude.com); the model is for example
`claude-sonnet-5-5`. With reasoning on, Claude's summaries of its thinking are the reasoning she shows; for the models of the 4.6 series and
later the **Reasoning Effort** is Claude's own (low, medium, high), for earlier ones a budget of 1 024, 8 192 or 24 576 thinking tokens.
Thinking tokens count as generated tokens and are taken from the response size: if the model spends it all on thinking the answer is
empty and the error says so. Temperature, top p and the penalties are not sent (the current API refuses them). Not supported: Amazon
Bedrock, Google Vertex AI, Microsoft Foundry, prompt caching.

## Google Gemini

Gemini models over the Gemini API. Get a key in [Google AI Studio](https://aistudio.google.com/apikey); the model is for example
`gemini-2.5-flash`. The thinking summaries are the reasoning she shows. **Turn off the safety filters** sends `BLOCK_NONE` for the
categories Google lets you adjust; Google still blocks some content whatever is sent.

## xAI Grok

Grok models over the xAI API. Get a key in the [xAI Console](https://console.x.ai); the model is for example `grok-4`. **Send the
reasoning effort**: *Automatic* sends it to `grok-3-mini` only, *Always* to every model, *Never* to none (the other Grok models refuse it).
Grok also works through the *OpenAI Compatible* type and the URL `https://api.x.ai/v1`; the extension adds the reasoning shown as such, the exact
token counts of xAI and a model list and test made for the service.

## Other extensions

An extension can also bring tools for her (under the same [permissions](../tools/permissions.md) as the server's, or as [tasks](../jois/tasks.md)),
its own settings (in the Tools tabs), tabs of the workspace and listeners of what she does in a turn; how to write one is in the
[developer guide](../../developer/extensions.md).
