---
label: Memory
icon: database
order: 870
---

# Memory

What she remembers beyond the latest messages: short facts she keeps and you can read, edit and delete.

## The Memories tab

A grid with one row per memory: name, type, who it is from (**You** or **Her**) and when it was created, newest first.

- Click a row to open it. A memory has a **Name** (for you only: it never goes into a prompt), a **Type**, **Keywords** and
  **What she remembers**, a text that grows as you type. Changes are stored as typed.
- **Add memory** adds an approved memory of medium importance at the top, open for you to fill in.
- The trash button deletes one. Or check rows (shift-click checks a range) and delete them together. She forgets what you
  delete.

## Types

| Type | When it reaches her |
|---|---|
| **Important** | always, when it matches |
| **Medium** | when it matches |
| **Trivial** | last, and the first to go |

## How memories reach the prompt

Her system prompt carries the **important** memories every turn, oldest first, so a new one goes at the end. The others come
when what you just said contains one of their **keywords**: those are added to your message for that turn, at most 20, one line
each. Separate keywords with commas.

## What she writes herself

She has two functions: **remember** (the fact, its keywords and its importance) and **recall** (search her memories by words).
What she writes herself shows in the grid as from *Her*, and is recalled like any other. Review it here: edit what is wrong,
delete what she should not keep.
