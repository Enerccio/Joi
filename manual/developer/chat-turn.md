---
label: A chat turn
icon: sync
order: 750
covers:
  - joi-server/src/main/java/com/github/enerccio/joi/domain/chat
verified: 61104d2
---

# A chat turn

A turn follows the design of Marginalia's story generation, simplified and without its UI. It is in `domain/chat/turn`, run by `ChatServiceImpl`.

## The steps

Each step (`TurnStepType`) is a bean listed under the chat service in `services-config.xml`:

| Step | What it does |
|---|---|
| `PREPARE` | today's mood (a new one is written with `JoiService.saveMood`, never by saving the turn's copy of her), the data of her system prompt |
| `PAYLOAD` | the prompt and the latest messages |
| `TOOLS` | what she may call |
| `INFERENCE` | one round of the model |
| `CALL_TOOLS` | the calls of that round |
| `STORE` | the `ChatMessage` and its prompt |
| `SPEECH` | the speech of the reply |
| `CLEANUP` | closes the turn |

`INFERENCE` and `CALL_TOOLS` loop until a round has no calls. After the platform's `MAX_FUNCTION_ROUNDS` the model must answer in text.

## Everything is asynchronous

A step hands over through callbacks and never waits on a thread. A listener holds the turn until it calls `chain.next()`, and a function
answers through its continuation, so a node's tool or an approval can answer minutes later. `ChatService.chat` returns a future; the turns of
one assistant still run one at a time. The work runs on virtual threads in the caller's request scope, so it acts as the caller.

A turn is also one **job** in the administrator's log (`TurnJob`, kind `chat`): opened on the thread of the first step, entered by every other
thread of the turn next to its request scope, so every request to the model and her speech is a call of it; it ends completed, failed or
cancelled with the turn. `ChatService.running()` lists the turns that wait or run (`RunningTurn`), for the page `/jobs`. See [Jobs](jobs.md).

## Events

`TurnEvents` has two kinds of hooks:

- **Listeners** (`addEventListener`) change the turn: the template data, the payload, the functions (`COLLECT_TOOLS`), a call before it runs (a
  listener may answer instead: a refusal, the user's answer), its result, the reply before it is stored, the text she speaks. They can stop the
  turn through its cancellation token.
- **Observers** (`addEventObserver`) only watch, synchronously. The pieces of the reply are for observers only.

The caller's listener (`onReply`, `onAudio`) may fail, for example a stream whose client left. Its error is logged and the turn goes on: her
speech and the steps after the store still run.

`AFTER_CLEANUP` comes for every turn with its outcome (`TurnOutcome`).

Who hooks in: her memories in the prompt, the server tools (`remember`, `recall`, `play_animation`, `change_body_part`, `list_outfits`,
`set_outfit`, `list_bodies`, `switch_body`, `web_search`, `fetch_page`, `list_workspace_files`, `offer_file_to_clipboard`) and the nodes' tools and approvals (`NodeService` registers a
`COLLECT_TOOLS` listener).

## The prompt is built for the model's cache

A model reuses the work it did on a prompt only up to the first token that differs from the last one. So the prompt changes as late and as
seldom as it can:

- The **system prompt** is the same from turn to turn: no clock, and of her memories only the important ones, oldest first. Her
  summaries (`SummarizerService.prompt`, the section `{{#summaries}}`) are in it too and change only when a summary is made; the history
  of the prompt is the latest messages **no summary covers** (`ChatMessageService.findLatestExchangesSince` the end of the last summary).
- When a message was said is **in the message**: a line from the template `MESSAGE_STAMP` (made by `MessageStamps`, put only in the prompt, never
  stored) comes before a message of the user or an event for the first message of the prompt, after a pause of 10 minutes or more, and when
  the day changes.
- The memories that fit a message (`MemoryService.recalled`) come **after the message**, from `RECALLED_MEMORIES`.

## Inference

`InferenceStep` runs a round. The guard against a call written as text (`CallTextGuard`, a setting of the AI in `joi` attributes) holds back the
first words of a round until they are known to be words; a round that opens with a call is cancelled and asked again, twice at most. The
placement of the function-call rule, images and function definitions are settings of the AI, written into the request by the platform.

## Tasks after the reply

A tool call can start a **task** (`JoiTask`, `WebTaskService`): a call to inference of its own that does not start during the reply. It waits
(`QUEUED`) until the message that started it is finished, spoken included, and then runs on the deep reasoning model, one at a time per
assistant. Its result comes back to the conversation as an event message that the server starts as a turn, so she tells it in her own voice.
This matters with one GPU that swaps models: the reply holds the conversation and TTS models, and only after she has said "I'm on it" does the GPU
switch.

## Streaming

The HTTP chat returns the whole reply or a server-sent stream; the node WebSocket streams the same turn as frames that refer to the `say`: `reasoning`,
`text`, `tool`, `reply`, `audio`, `done`. See [The protocol](protocol.md). Speech is cut into pieces and sent as one TTS job per piece: the first piece
alone, each next one at most twice the one before up to the model's chunk size, so piece 2 renders while piece 1 plays. When a reply was spoken,
`ClipWriter` joins the pieces into one clip while they are rendered (wav pieces under one header, mp3 one after another; never all in memory), stored as a
resource of the user and kept on the message (`ChatMessage.clipResourceId`).

## Notes

A `ChatMessage` of the type `LOG` is a note for the user (`ChatService.log`). The prompt reads `findLatestExchanges`, which leaves notes out;
`hasAny`, `findLastSentAt` and `findBetween` leave them out too.
