---
label: Notifications
icon: bell
order: 510
---

# Notifications

The first tool a node offers is `notify`: she shows you a notification on this machine, for something that matters when you may
not be looking, such as a task that finished. Whether she gets it is your choice, on the node:

| Choice | What it means |
|---|---|
| **Off (she does not get the tool)** | the default. The node lists no such tool |
| **Ask me each time** | each notification is asked about first: *She wants to notify you*. No answer in two minutes is a no |
| **Allow** | she can notify without asking |

In the JavaFX node it is **Joi → Notifications**, with **Show a test notification** to see what it looks like. In the terminal node
it is `/notify off|ask|allow` and `/notify test`; an asked notification is answered with `/yes` or `/no`.

Changing the choice sends the server a new list of tools, so she gets the tool, or loses it, at once, and is told that her tools
changed. Her description of the tool says which of the three applies, so she uses it for what matters and not as a way to chat.

## What the notification looks like

The node cleans the text first: one line, no control characters, cut to 500 characters, with a title of up to 100. It is shown in
the system's notification area: on macOS through the system's notifications, elsewhere with the Java tray icon. The terminal node
prints it.
