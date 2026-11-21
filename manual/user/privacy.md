---
label: Privacy
icon: lock
order: 410
---

# Privacy

Joi runs on your machine, and what it sends out is what you set up.

## Where the data lives

- **The data folder** (`~/.joi`, or `JOI_DATA`) holds everything the server keeps: the database with her conversation, memories,
  tasks and settings, and your uploaded files. Nothing is stored anywhere else.
- **Secrets** (model keys, search keys, tokens of page readers, proxy passwords) are stored encrypted with the key in
  `secret.key` in that folder. A secret is never shown again, logged, or returned by the API; a field says it is saved and an
  empty field keeps it.
- **A node** keeps its settings and its cache of her body in its home folder (`~/.joi-client`, or the one you name with `--joi-home-dir`), its credentials
  readable by you alone.

## What leaves the machine

- **What you say and what she remembers** go to the text models you configured. Point her at a local model server and it stays
  on your network.
- **Speech** is made by the speech server you configured.
- **Web search and reading pages** go to the sources you configured. In the web search options, **Only sources and readers on my
  own network** allows only the sources and page readers you marked **On my own network**; others are refused.
- **Pictures for descriptions** (snapshots of a body, your images) go to your multimodal model.

## What she may do

Her tools are under a permission chain. A server tool asks you before it runs unless you allowed it, and an administrator can
forbid it altogether. A page she fetches is treated as data, not as instructions to her. See [Server tools](tools/index.md).

## Access

The API takes tokens and device pairings that you make and can revoke at any time. See [Access tokens](access.md).
