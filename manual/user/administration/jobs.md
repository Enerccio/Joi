---
label: Jobs
icon: pulse
order: 86
---

# Jobs

Everything Joi asks of an **external AI** is a *job*: a chat turn, a summary, a task of an assistant, the description of a model, a
speech made for her. A conversion of a model or a clip with Blender is a job too. Each job is logged with the user it ran for, when it
started and ended, how it ended, and every *call* it made to a model, a speech backend or Blender, again with its own start and end.
The **Jobs** tab of the Administration page shows the log; the page `/jobs` shows what runs right now.

Only metadata is kept: who, when, how long, which AI or server (the name of the AI, or the host of the speech server; never the address
path or the query), the model, how it ended and the short error message of a failure. **No prompt, no answer, no text** is ever stored.

## What is one job

| Kind | What it is |
|---|---|
| `chat` | one **turn** of an assistant: the reply, with a call for each round of the model (a round that asked for a tool and the one after its result are two calls) and for the speech made for it. Named after the assistant |
| `summary` | one summary job (after a pause, a day, a week...), with a call for each summary written. Named after the assistant and the kind of summary. See [Summaries](../jois/summaries.md) |
| `web-task` | a task of an assistant that reads the web, with a call for each round of the stronger model |
| `appearance` | the description of how a model or an outfit looks, by a model that takes images |
| `conversion` | the conversion of a model or a clip, with one call for Blender |
| `joi-tts` | a speech made outside of a turn (a test of a voice) |
| `untagged` | a request made by code that did not name its work |

## What runs now: `/jobs`

Open `/jobs` on the address of your server (for example `http://localhost:8080/jobs`; the **Open the live view** link of the tab does it).
It asks for the login like every other page and shows nothing to users who are not administrators. The lists refresh every two seconds.

- **Active jobs** shows, for each running job, the user, the kind, how long it runs, how many of its calls are done and what is in flight
  now (the AI or server and how long the call waits). It is the quick look when the server seems busy: a long list of calls waiting on
  one model tells you why everything is slow. (The *Book* column is the platform's: it stays empty for Joi.)
- **What the assistants do now** says whose work it is: for each assistant her turn that runs, or waits for the one before it (the turns of
  one assistant run one at a time), her summary jobs (running, or waiting in the queue of their model) and her tasks (running, queued, or
  waiting for your answer).

## The log: the Jobs tab

| | |
|---|---|
| **User, Kind, Status** | Filters. |
| **Older than (days)** | Only the jobs that started that many days ago or earlier. |
| **Refresh** | Reads the log again. |
| **Delete these jobs** | Deletes for good the jobs the filter shows and their calls, after a question that says how many. With no filter set it deletes the whole log. |
| **Open the live view** | Opens `/jobs` in a new tab. |

Click a row to see the calls of the job: the type (language model, audio, other), the AI or server, the model, start, end, duration,
status and the error of a failed one. A job is shown as failed when it made a call that failed, or ended with an error. A cancelled job
is a turn you stopped.

**Nothing is deleted by itself.** The log grows with every request, so clear it from time to time: set *Older than (days)* (for example
30), look at what is listed and delete. The [cleanup](index.md#cleanup) removes the jobs of a user together with the user.

A job is written to the log when it ends; a job that is still running is only on `/jobs`. If the server stops, the jobs that were running
are lost. A job that nobody ended and that makes no call for half an hour is ended as completed.
