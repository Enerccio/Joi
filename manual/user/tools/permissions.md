---
label: Permissions
icon: shield-lock
order: 590
---

# Permissions

A tool's permission is one of three:

| Permission | What happens when she calls the tool |
|---|---|
| **Allow** | it runs |
| **Ask** | it waits for your answer: the task is *Waits for you* (see [Tasks and approvals](../jois/tasks.md)) |
| **Deny** | it never runs, and she is told |

## The chain

The permission is looked up along a chain, and the first level that is set wins:

1. **the assistant's own**
2. **your** settings
3. **the administrator's**
4. **the built-in default** (Ask)

Each level can also be **Inherit**, which passes the question on.

The administrator's setting is also a **ceiling**. What is set below it can only be as strict or stricter, in the order Deny,
Ask, Allow. An administrator's Ask is a default users can only tighten, Deny locks the tool for everyone, and Allow is only a
default. The panel says when the administrator's setting holds: "The administrator's setting is stricter than Allow, so it
holds."

The permission is read when she calls the tool, not when the turn starts, so a change counts at once.

## Standing approval

When a task asks, you can answer **Approve all of this tool**: this task and every later one of that tool for that assistant run
without asking. The task still goes through approval, but is approved at once, marked *approved automatically*, and a note
in the conversation says so.

Turning it on also approves the tasks of the tool that are waiting now. The standing approvals are listed under **Auto-approved**
(in the Tasks tab, and in the node's **Joi → Auto-approved tools**), where **Ask again** takes each back. A Deny at any level
still wins.
