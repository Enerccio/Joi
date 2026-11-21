---
label: Clipboard offers
icon: paste
order: 505
---

# Clipboard offers

A node can have a second tool, `offer_to_clipboard`: she offers you a text (a script a task of hers wrote, a command, a snippet) and
you decide whether it goes on the clipboard of this machine (or, if you allow it, she puts it there), to paste where you want. Whether she gets the tool is your choice, on the
node:

| Choice | What it means |
|---|---|
| **Off (she does not get the tool)** | the default. The node lists no such tool |
| **Show me each offer, I decide** | each offer is shown to you with its whole text and asked about: *She offers you a text*, **Copy** or **Don't copy**. No answer in two minutes is a no |
| **Allow (she copies without asking)** | what she offers is put on the clipboard at once, and she tells you in the chat |

!!!warning Think before you choose Allow
What is on your clipboard is pasted into a terminal or a form later, and a text she offers may come from a web page a task read.
With **Allow** you do not see it first: look at what is on the clipboard before you paste it into a shell. **Show me each offer** is
the safe choice.
!!!

In the JavaFX node it is **Joi → Clipboard offers**. In the terminal node it is `/clipboard off|ask|allow`; with *ask* an offer is
printed (its first 1500 characters) and answered with `/yes` or `/no`.

Changing the choice sends the server a new list of tools, so she gets the tool, or loses it, at once.

## What you see

The dialog shows the title (normally the name of the file), how many characters the text has, and the text itself in a box you can scroll
(the first 20 000 characters; the node copies all of it). A text can have up to 100 000 characters. **Copy** puts it on the system
clipboard, **Don't copy** tells her you did not want it.

The terminal node copies with `pbcopy` on macOS and with `wl-copy`, `xclip` or `xsel` on Linux (one of them must be installed), else with
the Java clipboard of the desktop.

## Where the texts come from

She can offer a file from her [workspace](../tools/workspace.md): the server reads the file and sends its text to your node, so it does not
pass through her own words. She can also put a short text there herself, directly, with the tool of the node.
