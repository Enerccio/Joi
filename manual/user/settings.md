---
label: User settings
icon: gear
order: 440
---

# User settings

The **User Settings** tab is your defaults for every assistant. It has a **Save** button and **Discard changes**; while changes
are unsaved the other parts of the page wait.

![The General tab](../../images/settings-general.png)

## General

The same fields as an assistant's About tab: **Assistant name**, **Your name**, **Personality**, **Chat model**, **Deep reasoning
model**, **Auxiliary model**, **TTS model**, **Voice actor**, plus:

- **New day starts at**: when the day changes for her (04:00 by default). The daily mood is rolled at the first chat after it,
  so a late night still counts as the same day.
- **Conversation pause (minutes)**: minutes without talking after which the live conversation steps back to the open ones.

A field she leaves empty uses these; her dialog shows them greyed out.

## Prompts

Every prompt sent to a model is a template: the master system template, the conversation, day and memory prompts, the
appearance, outfit and part prompts, the web task and task event prompts, and the texts of the events she is told. An empty
field uses the built-in text. See [Persona and personality](jois/persona.md).

## Personalities and Moods

The lists of built-in and your own personalities and moods, with **Add personality** and **Add mood**, edit, **Clone** and **Reset to
the default**. A row says whether it is built-in, changed or your own. See [Persona and personality](jois/persona.md).

## Access

Your tokens and devices. See [Access tokens](access.md).

## Tools

Your permission for every [server tool](tools/index.md) and your web search setup.
