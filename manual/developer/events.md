---
label: Events
icon: bell
order: 730
covers:
  - joi-server/src/main/java/com/github/enerccio/joi/domain/events
verified: b6f812e
---

# Events

Everything that changes around her reaches her as an event. An edit made in the UI is stored as a `QueuedEvent` (kind, time, text, data) and waits;
`JoiEvents` is where events are made.

## Queued events

When a node of hers connects, the waiting events go to her in the order they happened, in one turn after the greeting (cause `events_waited`, an Event
message with the text of all of them; a turn of only a task's result keeps `task_ended`). `QueuedEventPlan` is pure and tested on its own: it
merges them first.

- a part switched again and again is one event from the first state to the last (the last text, which says how it looks now); one that ended in the
  state it began in is dropped;
- a name, what she calls the user, her personality, her voice or her body changed again and again: the texts of the steps in a row, nothing when the value
  is back where it began;
- a body described again, an outfit put on again: the last one;
- an event about a body that is gone, and any older than a week (`MAX_AGE`, except a task's result), are marked handled without a message;
- the rest as it came.

Every event that went in a merge is marked handled with the one that is told.

## The kinds

`persona.renamed`, `persona.user_renamed`, `persona.personality`, `persona.voice` (only after she has chatted: before that it is the initial setup);
`body.switched`, `body.part_switched`, `body.outfit_changed`, `body.added`, `body.described`; `task.finished`, `task.failed`, `task.denied`; `summary.created`
(a summary was made, see [Summaries](summaries.md)); and the
events a node starts: `node.first_boot`, `node.connected`, `node.looking_glass_entered`, `node.looking_glass_exited`, `node.server_restarted`,
`node.part_switched`.

## Causes of a turn started by the server

A turn the server starts has a cause, sent to the node in the `turn` frame: `first_boot`, `connected`, `looking_glass_entered`,
`looking_glass_exited`, `part_switched`, `server_restarted`, `events_waited`, `task_ended`, `summary_created`. Each has a template that the user can change
(`JoiPromptField.FIRST_BOOT_EVENT`, `CONNECTED_EVENT`, `LOOKING_GLASS_ENTERED_EVENT`, `LOOKING_GLASS_EXITED_EVENT`, `SERVER_RESTARTED_EVENT`, `PART_REQUEST_EVENT`,
`TASK_EVENT`, `SUMMARY_EVENT`) and is stored as an event message. The message of a `summary_created` turn is stored with the kind `node.summary_created`: the
summaries skip it when they decide whether a conversation has anything to summarize, or her reaction to a summary would be summarized, and
that reaction told again, for ever.

Changes made on a node reach the server's `NodeSession`, which gathers what it has not told her yet: after the last event it waits 1.5 s, and while a turn of
hers runs it waits for the turn to end, then starts one turn whose input has the text of every event, one per line, in order.

## Notes

A note (`ChatMessageType.LOG`) is not an event: it is for the user and she never sees it.
