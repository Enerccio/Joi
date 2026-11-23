---
label: Chats
icon: comment-discussion
order: 880
---

# Chats

The **Chats** tab of her dialog shows the conversation as a transcript, oldest at the top. You can talk to her here, below the
transcript, or in a [node](../node/index.md) or the phone app; this tab is where you read it back and clean it up.

## Saying something

Below the transcript is a field for your words. **Enter** sends them, **Shift+Enter** starts a new line. Your message shows dimmed while she
answers, and is replaced by the exchange when her reply is stored.

### Showing her an image

Press the picture button next to the field and choose an image, or paste one (**Ctrl+V** / **Cmd+V** while the field is focused) or drop
one on it. At most four go with one message, each at most 20 MB; PNG, JPEG, GIF, BMP and WebP are read. The images wait under the field as small
pictures, with a button to take one away. Send them with words ("What is this error?"), or alone.

She looks at them with your words, in the same turn, and the image stays part of the conversation: she sees it in later turns too, and it
shows in the transcript, small (click it to open it). A photo is turned upright and scaled to at most 1568 pixels on its longest side before it is
kept, so a large one costs no more than a small one. Deleting the message deletes the image.

She can only look at images when a model of hers takes them:

- her **chat model** is marked **Multimodal (accepts images)** in the [Models tab](../models/text-models.md): she looks at the image herself; or
- her **deep reasoning model** is marked **Multimodal** and her chat model is not: it describes each image once, when you send it, and she
  gets that description in place of the picture (`[The user attached an image. You cannot see it, but this is what it shows: ...]`). The texts
  of both prompts are in the Templates tabs and can be changed.

When neither is the case the attach button is off, and a message with an image is refused with a note that says why.

## What a card shows

![Her Chats tab: two exchanges, with reasoning and the functions she used](../../images/joi-chats.png)

- **What you said** on the right, with the images you sent, or an **event** in the middle with its kind (a part of her was switched, a task finished;
  see [Events](events.md)).
- **Her reasoning**, folded, when the model thought.
- **Her reply** as formatted text, with when it was said, which model answered and which node it came through.
- **Tools used (3): ...**, folded: every tool she used in the reply, in the order it happened, with the arguments she wrote and
  what it answered. A failed call is marked.
- A **play button** when the reply was spoken: the pieces of speech are joined into one clip kept on the message.
- **Notes** of the system (for example *approved automatically*): these are for you. She is
  never given them, they do not count as a conversation, and they are not in her summaries.

## Reading and loading

The tab opens at the latest 30 messages at the bottom. Scroll to the top and the 30 before them load, with what you were reading
kept in place. Nothing is dropped.

The tab follows the conversation while it is open: a message you just sent shows dimmed ("waiting for her reply") and is
replaced by the exchange when her reply is stored. The turns of one assistant run one at a time.

## Deleting

A message has a trash button. A message with images takes them with it: the files are deleted. Or check messages and delete them together; shift-click checks a range. A deleted message is
gone from what she is shown next time: the prompt is built from the stored history on every turn.

## Summarizing

Check a message and press **Summarize** to have the messages up to it written down as a summary. All the messages since the last
summary are checked too, so there is no gap, and you are asked first. See [Summaries](summaries.md).

## What she is shown

Each turn she gets her system prompt (with her [summaries](summaries.md) of what is older), the latest messages no summary covers, and, in front of a message after a pause or on a
new day, a line with the date and time it was said. She is told the time that way and not in the system prompt, so the
prompt stays the same from turn to turn and the model can reuse its work on it.
