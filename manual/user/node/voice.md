---
label: Talking to her
icon: unmute
order: 535
---

# Talking to her

The JavaFX node can listen to you. You press **Talk**, say what you want, and stop talking: the words are written down by the node and
go to her like a message you typed. Her answer comes as usual, in text and in her voice.

Speech is turned into text **on your computer**. Only the words go to the server; no recording leaves the node, and none is kept.

## The first time

The node needs a speech model, and it does not come with it. Under **Voice** in the controls there is a button, **Download the speech
model**. It tells you what it fetches and how much: English (Moonshine tiny), about 30 MB, from the sherpa-onnx project on GitHub. The node
checks the download against a checksum before it keeps it, and a download that does not match is thrown away. The files go to the `voice`
folder in the node's home (`~/.joi-client`). It is done once.

Without the model, **Talk** only tells you to download it.

## Talking

1. Press **Talk** below the chat, or **Shift+Cmd+Space** (Shift+Ctrl+Space on Windows and Linux). The button says *Listening… send*.
2. Say what you want. When you pause for a moment (a second and a bit), the node stops listening, writes the words down (*Writing it
   down…*) and sends them.
3. Or press the button again when you are done: what you said so far is sent at once, with no wait.

**Esc** gives up: nothing is sent. If you say nothing for about eight seconds, the node stops by itself and says so in the chat.

If she is talking when you press **Talk**, she stops, the same as with **Stop**, and the node listens.

Images you attached with **Image…** go with your words, the same as with a typed message, also the ones you attach while you talk. What you
typed in the field stays there.

## The pause

Under **Voice** in the controls, **Pause that ends what I say** is how long you have to be quiet before the node thinks you are done. The
default is 1.2 seconds. If it cuts you off while you think, make it longer; if it waits too long, shorter.

## The microphone

The node uses the default microphone of the computer, and it is open only while the button says *Listening…*, so the indicator of the
system is on only then.

On macOS the first time asks for your permission. The desktop app asks for itself; a node started from a terminal or an IDE is asked for
that program. If you said no, allow it in **System Settings → Privacy & Security → Microphone**.

## What does not work yet

- **Only English.** Models for other languages come later, with a choice of the language for the node.
- **No "Hey Joi".** You press the button; she does not listen on her own yet.
- **You cannot talk over her by voice**, only by pressing **Talk** (which stops her).
- The recording is not kept with the message.

## If it does not work

| You see | Why |
|---|---|
| *This node needs a speech model* | download it under **Voice** in the controls |
| *The microphone cannot be opened* | no microphone, or the system did not allow it (see above) |
| *I heard nothing* | no speech reached the microphone: is it the one the system uses? |
| *I could not make out any words* | the model found no words; speak closer, or a little slower |
| *The download failed* | the node could not reach GitHub, or the file did not match its checksum; try again |
| *The speech model could not be loaded* | the files in `voice` are damaged: delete the folder and download again |
