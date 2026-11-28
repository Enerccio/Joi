---
label: Summaries
icon: archive
order: 865
---

# Summaries

A conversation grows without end, and a model reads only so much. So she does not carry it all: what is old is written down as
a **summary**, and the summary stands in her prompt in place of the messages. The messages stay in the [Chats](chats.md) tab;
nothing is deleted.

## What she remembers of the past

Her system prompt has a section **What happened before** with her summaries, the oldest first, and the messages that follow
start where the summaries end. A summary covers a **time**, not a list of messages: a day, a week, a month, a year. A longer
summary covers the shorter ones inside it, so a week shows as one week, not as seven days.

## When a summary is made

| When | What is summarized |
|---|---|
| **After a pause**: nobody wrote for the *Summarize after a pause* time (45 minutes by default) and no task of hers is open | the conversation since the last summary. A day can have several of these, the parts of the day. |
| **When the conversation grows**, if you turn on *Summarize on her own when the conversation grows* | when the messages no summary covers pass the tokens you set, the oldest of them are summarized, so about half of the limit is left. The limit is at most 60 % of the context of her chat model. Your pictures count too, by their size: a full-size picture is about 2,500 to 3,300 tokens. |
| **Before her context fills up**, always, when her chat model's settings say how long its context is | the same, at 60 % of the context, even with the setting above off. If a message would make her prompt pass 80 % of it, her answer waits: the oldest messages are summarized first, and she is told about the summary together with your message. |
| **A day ends** (at the time the day changes, 04:00 by default) | what is left of the day is summarized, and all the parts of the day become **one** day summary. A day with one part only takes that part for the whole day. |
| **A week, a month or a year ends** | the summaries of its time become one, at the time of day you set (*Week summary at*, *Month summary at*, *Year summary at*; 05:00 by default). |
| **You ask for it** | the messages you checked, see below. |

A week, a month or a year is summarized only **after it ended**, never the one that is going on, and only when a **day
summary of the time after it** exists. So at any time there is at least one day summary, and a long time away from the chat does not
swallow the last days. A longer time waits for the shorter ones inside it: a month is made from its weeks, a year from its months.

A task of hers that is still open (running, queued or waiting for your answer) holds the pause summary back, because its result is
still to come into the conversation.

## She is told

Every summary a model wrote is told to her as an **event**, in her own voice and with the summary text, so she can react: something
she promised, something still open. The event is the *Summary made (event)* prompt. If no node of hers is connected, she is told when one
connects. A summary that is due as you come back (the day rolled over while the server was off, or the pause before) is made first, and she
is told it together with her greeting, in one reply (her greeting waits up to three minutes for it). What she says in reply never makes a summary of its own, so it is not an endless chain.

## The Summaries tab

The list of her summaries, the latest start first. A card says what time it covers (a day, a part of a day, a week...), why it was
made (after a pause, the conversation grew, by hand, rolled up), how many messages it came from and which model wrote it. A summary
that a longer one covers is dimmed: it is no longer in her prompt. Above the list is what is being written now and what waits.

The trash button deletes a summary. She forgets what it says, and the messages it covered are summarized again after the next pause.

## Summarizing by hand

In the **Chats** tab check a message and press **Summarize**. A summary never leaves a gap: it starts where the one before it ended,
so every message from there up to the last one you checked is checked too (the ones already summarized are not), and you are asked
before it is queued. Cancel and your selection is as it was. She is told about it like about any summary.

## Settings

In [User Settings](../settings.md) and in her About tab (hers comes first):

- **Summary model**: which model writes all the summaries. Marked *Summary* in the model's dialog. Empty: the auxiliary model, else the deep reasoning one, else the chat model.
- **Summarize after a pause (minutes)**: 0 turns the pause summary off.
- **Summarize on her own when the conversation grows** and **Summarize after (tokens)**.
- **Week summary at**, **Month summary at**, **Year summary at**.

Every prompt of the summaries is a template you can change, in User Settings and in her Templates tab: the conversation summary, the
day, week, month and year summaries, and the event she is told.

## The queue

A summary is a request to a model, and models have limits, so the summaries wait in a queue:

- A model that takes a lock on a GPU shares the queue of that **GPU** with every model on it, and as many summaries run at once as the
  model with the fewest connections (and the lock's own limit) allows.
- Any other model has a queue of its own for you, with as many at once as its connections.
- The summaries of one assistant never run side by side, and always in the order **days, then weeks, then months, then years**.

The model's own limits and its GPU lock are taken for each request as always, so a summary never takes the card from her voice
in the middle of a reply: it waits until she is not speaking.
