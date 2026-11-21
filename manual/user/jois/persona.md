---
label: Persona and personality
icon: person
order: 890
---

# Persona and personality

## About

The **About** tab of her dialog sets who she is:

| Field | Meaning |
|---|---|
| **Assistant name** | what she is called; empty is *Joi*. Her prompt uses it as `{{char}}` |
| **Your name** | what she calls you; empty is the name of your account. Used as `{{user}}` |
| **Personality** | who she is, see below |
| **Chat model**, **Deep reasoning model**, **Auxiliary model** | the [model roles](../models/index.md) |
| **TTS model**, **Voice actor** | her [voice](../models/speech.md) |

Empty fields use your [User Settings](../settings.md), shown greyed out. After you have talked, a change of her name, your name
or her personality is an [event](events.md): she is told, and can react in her own words.

## Personality and mood

**Personality** is who she is, and stays. **Mood** is how she is today.

The built-in personalities are *Neutral assistant*, *Warm companion*, *Cheerful*, *Sarcastic*, *Kuudere*, *Dandere*, *Tsundere* and
*Yandere*. A personality changes her tone and banter, never what she does: it does not make her refuse an approval or hold
back help.

The built-in moods are calm, happy, playful, curious, affectionate, focused, sleepy, grumpy, melancholic and excited. At the
first chat after the day starts (**New day starts at** in User Settings, 04:00 by default) one is rolled by weights, and it
stays for the day. The mood's text comes into her prompt as it sounds in her personality, and her voice picks the actor's voice
for that mood.

You manage both in **User Settings → Personalities** and **Moods**:

- Change a built-in one, or add your own (**Add personality**, **Add mood**). An empty field keeps the default. **Reset to the
  default** brings the built-in text back.
- **Clone** copies a personality (built-in or yours) under a new key and opens it, so you can vary one without losing it.
- For a mood: its **Roll weight** (0 never rolls it) and its **Voice delivery**. A personality can say how each mood sounds
  in it (**How the moods sound in this personality**); a mood it says nothing about uses the mood's own text.
- A personality can have a text under **When she is asked to change a part of her body**. Empty: the change is made and she
  only reacts. With a text she decides whether to allow it, and says why if she refuses ("If the user begs, consider
  allowing it."). It covers the look of her body only, never a tool, an approval, a task or help.

## Templates: every prompt can be changed

Every prompt sent to a model is a template you can edit: her **master system template**, the memory prompts, the body section,
the appearance, outfit and part prompts, the web task prompt, and the texts of the events she is told. They are in two
places: **User Settings → Prompts** (yours, for all assistants) and her **Templates** tab (hers alone). Empty falls back to
the next one in the chain.

- Templates are Handlebars. Sections such as `{{#memories}}...{{/memories}}` are left out when there is nothing to put in
  them. The variables of each template are described next to it.
- **Preview the system prompt** shows what her system prompt is now.
- An invalid template is reported and not stored.
- The server adds what she needs around your text (tools, approvals, her body state), so editing the persona cannot break them.
