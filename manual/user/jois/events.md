---
label: Events
icon: bell
order: 860
---

# Events

Whatever changes around her reaches her as an **event**: a line in the conversation, shown in the middle of the transcript with
its kind. She reads it as something that happened, and may answer in her own words.

## The kinds

| Kind | When |
|---|---|
| `persona.renamed`, `persona.user_renamed`, `persona.personality`, `persona.voice` | you changed her name, your name, her personality or her voice (after you have talked) |
| `body.switched` | she got her first body, was put into another, or lost hers |
| `body.part_switched` | a part of her body was switched |
| `body.outfit_changed` | you put an outfit on her |
| `body.added` | a new body or outfit exists (told once, when you close the body dialog) |
| `body.described` | you used a new description for a model of hers |
| `task.finished`, `task.failed`, `task.denied` | a task she started ended |
| `node.first_boot` | she woke up for the first time on a node |
| `node.connected` | you came back to her on a node |
| `node.looking_glass_entered`, `node.looking_glass_exited` | you moved her onto the Looking Glass or back |
| `node.server_restarted` | a node connected again after the server was down |
| `node.part_switched` | you changed a part of her on a node |

Changes in her setup are staged: you save, and she gets one event of what changed, not one per field.

## When no node is connected

An event that happens while she cannot take it (every node is shut down and you add an outfit in the browser) is kept. When a
node of hers connects, the waiting events go to her after the greeting, in the order they happened, in **one turn**, so she
can still react ("oh, a new outfit?"). They are merged first:

- a part switched again and again is one event from the first state to the last; one that ended where it began is dropped;
- a name, her personality or her voice changed several times is the steps in a row, or nothing if the value came back;
- a body described again, an outfit put on again: the last one;
- an event about a body that is gone, and any older than a week, is dropped (a task's result is always told).

## The texts

What she is told is made from templates you can change: see [Persona and personality](persona.md). The greeting when you come
back, her first waking up, the Looking Glass events and the server-restarted event each have one.
