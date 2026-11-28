---
label: Marginalia's books
icon: book
order: 910
---

# Marginalia's books

With this extension she can read your books in Marginalia, the writing application: list them, read you a part,
and answer questions about them ("what happens to Mara in chapter 3?", "where does the ferryman first appear?", "sum up the second part").
She only reads. Nothing she does changes a book.

Joi and Marginalia share nothing but a key: they can run on different servers. Joi talks to Marginalia's HTTP API with a key you make
there.

## 1. Make a key in Marginalia

In Marginalia, open **Settings → API keys** and click **New API key**:

- **Name**: something that tells you what uses it, for example *Joi*.
- **Access**: **Read** only. That is the books and the commands that only read. Do not give *Write*: the extension never writes, and a key
  that cannot write cannot be made to.
- **Expires**: as you like. When the key expires, she cannot read until you set a new one.

Marginalia shows the key (`mrg_...`) once. Copy it.

Only administrators and users with the permission *API access* can make keys in Marginalia; ask your Marginalia administrator if the tab says
you cannot.

## 2. Set it in Joi

The administrator loads the extension (the JAR `marginalia-books`, see [Managing extensions](../administration/extensions.md)). Then, in
**User Settings → Tools**, the section **Marginalia's books** has two fields:

| Field | |
|---|---|
| **Marginalia's address** | The address you open Marginalia at, for example `https://marginalia.example.org`. `/api/v1` is added for you. |
| **API key (READ)** | The key from step 1. It is stored protected and never shown again: the field then says *Saved*. Leave it empty to keep it; type a new one to replace it. |

Click **Save**. **Remove these settings** takes yours away.

An administrator can set both in **Administration → Tools** for everybody, for example only the address when all users share one Marginalia.
Each field you set yourself takes the place of the administrator's.

Until the address and a key are set, she is not offered the functions at all.

## What she can do

| Function | What it does | Permission by default |
|---|---|---|
| `marginalia_books` | Lists your books: name, folder and id. It runs at once, in her reply. The books you marked **Only in the extended API** in Marginalia (a book's About tab) are left out unless you ask her for all of them; she still reads one when you name it. | Allow |
| `marginalia_read_part` | Lists the parts of a book, or reads one part, a page at a time. It runs at once, in her reply, so what she reads goes into her conversation. | Allow |
| `marginalia_read_book` | Reads a book to answer a question. It is a [task](../jois/tasks.md): her stronger model reads the book with Marginalia's commands that only read (the outline, a chapter, a search...) and brings back the answer. | Ask |

Ask her to read you a scene and she reads it herself; ask about the whole book and she gives it to the task. With the task, a whole chapter never goes into her conversation: she gets the answer, once, when the task ends. While the task waits for you, it is in her
dialog's **Tasks** tab: **Approve**, **Deny**, or approve all of this tool from now on.

All three are on the [permission](../tools/permissions.md) rows of the Tools tabs and her Tasks tab, under the server's tools: *Marginalia:
list the books*, *Marginalia: read a part of a book* and *Marginalia: read a book*. Deny one to take it away. `marginalia_books` and
`marginalia_read_part` run during her reply, where nobody can be asked: *Ask* takes them away as *Deny* does.

What she reads with `marginalia_books` and `marginalia_read_part` **stays in her conversation**: in her next replies she still has the
text of the part, not only what she said about it, until a [summary](../jois/summaries.md) covers it. A long part makes her
conversation longer, so a summary comes sooner.

The commands she reads with are the ones your Marginalia offers to a key with *Read*, taken from its API when the task runs: a newer Marginalia
with more commands gives her more, without a new extension.

## When it does not work

| What she says | Why |
|---|---|
| *Marginalia is not set up* | The address or the key is missing in User Settings → Tools (or the administrator's). |
| *Marginalia does not take the key (401)* | The key is wrong, revoked or expired, or its user lost *API access*. Make a new one. |
| *The key may not do that (403)* | The key has no *Read*. |
| *Marginalia could not be reached* | The address is wrong, or Marginalia is down, or the Joi server cannot reach it (a firewall, a container network). |
| *No book "..."* | No book has that name. She is told the names of your books. |

!!!warning What she reads goes to the model
The text of your book goes to the model that does the task (her deep reasoning model, else her chat model). With a model of an outside
provider, the book goes to that provider. Use a local model if a book must stay in the house.
!!!
