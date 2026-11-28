---
label: Summaries
icon: archive
order: 745
covers:
  - joi-server/src/main/java/com/github/enerccio/joi/domain/summary
  - joi-server/src/main/java/com/github/enerccio/joi/domain/service/SummarizerService.java
  - joi-server/src/main/java/com/github/enerccio/joi/domain/service/impl/SummarizerServiceImpl.java
  - joi-server/src/main/java/com/github/enerccio/joi/domain/service/impl/SummaryServiceImpl.java
  - joi-server/src/test/java/com/github/enerccio/joi/domain/service/impl/SummaryTokensTest.java
verified: dce9fc6
---

# Summaries

The user side is in [Summaries](../user/jois/summaries.md). This page is how it is built.

## The pieces

| Piece | Where | What it is |
|---|---|---|
| `Summary` | `domain/model/impl` | a time (`coverFrom` inclusive to `coverTo` exclusive), a `SummaryType` (`DAY`, `WEEK`, `MONTH`, `YEAR`; `ROLLING` is not made yet), the text, and the extended attributes `partial`, `origin`, `messageCount` |
| `SummaryCoverage` | `domain/memory` | which summaries a prompt shows: a longer one covers the shorter ones in its time |
| `SummaryDays` | `domain/summary` | her day, week, month and year: they begin at the day boundary, a week on Monday. Pure |
| `SummaryPlan` | `domain/summary` | decides what is due now from the summaries and the messages no summary covers. Pure, tested on its own |
| `SummaryQueue` | `domain/summary` | the queue: lanes, limits, the order of one assistant's jobs. Pure |
| `SummaryText` | `domain/summary` | the words about a time, for the prompt and the events. Pure |
| `ModelRound` | `domain/summary` | one request to a model that answers in text; a job's requests go with the cache key of `JoiInference.SUMMARIZE` (see [A chat turn](chat-turn.md)) |
| `SummarizerService` | `domain/service` | the tick, the jobs, the manual summary and the event she is told |
| `SummaryService.lane` | `domain/service` | which lane a model's summaries wait in, and its limit |

## A partial and a whole day

A summary made from messages (after a pause, when the conversation grew, by hand) is a **part of a day**: `partial`, covering from its first
message to its last (+1 ms), never across the day boundary (the messages are split by day, and by what the model can read at once: half of its
context). When a day ends, `DAY_ROLL` summarizes what is left of it and then:

- one part only: it takes the whole day for its time, no model is asked;
- several: the day summary prompt (`DAY_SUMMARY_PROMPT`) writes one summary of the whole day, and the parts are soft-deleted.

"Covered up to" is the largest `coverTo` of the summaries that are not `ROLLING` (`SummaryPlan.coveredUpTo`). The prompt's history starts there.

## The rules of `SummaryPlan`

- `QUIET`: no message for the pause time, the unsummarized messages hold something other than the answers to summaries
  (`node.summary_created`), and no task of hers is `QUEUED`, `RUNNING` or `WAITING`.
- `AUTO`: on, and the tokens of the unsummarized messages pass the limit; the oldest are summarized until about half of the limit is left,
  never the newest message. When her chat model says how long its context is, the limit is at most `historyContext` (60 % of it,
  `JoiDefaults.HISTORY_SUMMARY_PERCENT`), and `AUTO` is on at that limit even when the user did not turn it on: the history has no count
  of messages, so the context bounds it (`SummarizerServiceImpl.config`).
- `CONTEXT`: never planned. A turn that would overfill the context asks for it (`SummarizerService.makeRoom`, see Chat turn). It is not put in
  the queue, because the queue's jobs first wait for her turn to end (`awaitIdle`) and that would never happen. It runs on the summarizer's
  executor as an administrator's job, under the lock per assistant that the queue's jobs take after their wait (`SummarizerServiceImpl.lock`),
  so the two never write side by side, and a queued job that wakes up after it finds itself not due. It summarizes like `AUTO` (origin
  `auto`) but does not tell her: the future completes with the texts of `SUMMARY_EVENT`, which the turn gives her with its words.
- A message counts its words (characters / 4) and the user's images (`MessageTokens.of`, also used by the turn's check). An image counts by its pixels with
  Marginalia's `EstimatedImageTokenizer` (750 pixels a token, as sent). The estimate is worked out once when the message is stored
  (`StoreStep`, `ChatImageService.tokens`) and kept on it (`ChatMessage.imageTokens`, an extended attribute), so the trigger reads no file.
  When notes were written for a model that cannot see, the notes' characters count instead. An older message without the estimate counts
  each image as one of unknown size (1600). Her tools' pictures do not count: the model gets them only in their turn.
- `DAY_ROLL`: a day that ended (now is past its end) that has a part or unsummarized messages.
- `WEEK`, `MONTH`, `YEAR`: a period that ended, past its time of day (`SummaryDays.runAt`), without a summary of its kind or a longer one, with
  summaries of lower rank inside it, and **with a day summary that begins after it ended**: so there is always one day summary, and the week going on is
  never summarized. A period waits while a shorter job inside it is due (the weeks of a month come before the month).
- A job is checked again when it runs (`SummarizerServiceImpl.execute`): what was due when it was queued may not be (the user wrote, a task started). `MANUAL`
  is the exception: the user asked.

## The queue

`SummaryQueue` keeps a **lane** per model: `SummaryService.lane(ai)`.

- A model with a GPU lock (`AI.getGpuLock`): the lane of that **GPU**. Its limit is the least `AI.getConnections()` of every model whose lock is on it,
  and the least positive `maxConcurrent` of the GPU's locks. The platform's `GpuArbiter` and `AIGate` still apply to each request: the queue only keeps
  the summaries from filling them.
- Any other model: the lane `ai:<user>:<model>`, the model's connections.
- The jobs of one assistant never run side by side and go in the order of `Kind.rank()`: the days (`DAY_ROLL` before the others, `priority`), the
  weeks, the months, the years. Between assistants the one whose job came first goes first. A job queued or running is not queued again (`Job.key`).

## The tick and the settings

`SummarizerServiceImpl` looks at every assistant once a minute (`joi.summary.tick-seconds`, 0 in the tests, which call `check`) and after every turn
(`AFTER_CLEANUP`, for the growing conversation). It runs each job in the request scope of the assistant's owner, as a job of the administrator's log (kind `summary`, named after the assistant and the
kind: `JoiJobs`, see [Jobs](jobs.md)).

The settings are a chain (the assistant, the user setting, the default): `pauseMinutes` (the pause), `autoSummary`, `autoSummaryTokens`,
`weeklySummaryTime`, `monthlySummaryTime`, `yearlySummaryTime`, `summaryModel` (else the auxiliary, the deep reasoning, the chat model). The prompts:
`SUMMARY_PROMPT` (messages), `DAY_SUMMARY_PROMPT`, `WEEK_SUMMARY_PROMPT`, `MONTH_SUMMARY_PROMPT`, `YEAR_SUMMARY_PROMPT`, `SUMMARY_EVENT`.

## Images

The model that writes a summary is given no images: `SummarizerServiceImpl.transcript` writes "[attached an image: note]" for a message that had one, with the note the describer made when there is one.

## The event

Each summary a model wrote is told to her through `NodeService.tell` with the cause `summary_created` (see [Events](events.md)), or queued as
`summary.created` when no node is connected. `settle(joi)` queues what is due now and completes when no job of hers waits or runs: a node's
greeting waits for it, so a summary due as the node connects is told in the greeting's turn (see [Nodes](nodes.md)). A part that only took the whole day, and a week made of one summary, are not new: no event.

## The UI

`SummaryFields` (`ui/widgets`) holds the settings, the same in `UserSettingsPart` and her About tab. `JoiSummariesPart` is the Summaries tab; the
Summarize button is in `JoiChatsPart` (`SummarizerService.rangeUpTo`, `summarize`).

## Tests

`SummaryDaysTest`, `SummaryPlanTest` and `SummaryQueueTest` are pure. `SummarizerTest` runs the whole thing with the fake model, `JoiSummariesTest`
the tabs and the settings.
