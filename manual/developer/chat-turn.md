---
label: A chat turn
icon: sync
order: 750
covers:
  - joi-server/src/main/java/com/github/enerccio/joi/domain/chat
  - joi-server/src/main/java/com/github/enerccio/joi/domain/service/impl/ChatImageServiceImpl.java
verified: 197abef
---

# A chat turn

A turn follows the design of Marginalia's story generation, simplified and without its UI.

A turn is in a **profile** (`TurnInput.profile`, see [Bodies and models](bodies.md)): a node's turn gets the profile of its node, `ChatServiceImpl.chat` gives the one of her last turn to a turn
from no node, and `PrepareStep` renders her body section for that profile (`BodyService.prompt(joi, profile, last)`, with `{{profile}}` and `{{profileFrom}}` when she moved) and remembers it. Her
body functions (`BodyTools`, `AnimationTools`) read the profile from the turn's input. It is in `domain/chat/turn`, run by `ChatServiceImpl`.

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

`INFERENCE` and `CALL_TOOLS` loop until a round has no calls. After the platform's `MAX_FUNCTION_ROUNDS` the model must answer in text. The exception is a round that has said its words and made only calls whose result says nothing (`ToolEffects.needsNoAnswer`: `play_animation`, `set_mood`, a node's
`notify`, all succeeded): `CALL_TOOLS` ends the turn at `STORE` for it, since the next round would have nothing to add (a small model fills it with nothing until its
token budget is gone).

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

## A turn that fails after some rounds

A round can fail (the model's server answers an error, a round uses its whole token budget without a word) after earlier rounds answered and called functions. Those
words had streamed to the clients and the functions had run, so the engine does not drop them: before the cleanup, `TurnEngine.keepWhatHappened` sends a failed turn that has
words or calls to `STORE` once (never one that was stopped, or failed before it did anything). The message is stored with the reply and tool calls so far,
`TurnProperties.PARTIAL` is set, the speech is skipped (`StoreStep` goes to `CLEANUP`), and a note of the type `LOG` and the kind `turn.failed` carries the reason into the
conversation: the web chat and the nodes (as a `log` frame) show it and the model never sees it. The turn still ends as a failure: the caller's future fails, the node gets `error`,
the job and the outcome are `FAILED`. `ChatTurnTest` has the case (the second round uses its budget after a call).

Who hooks in: her memories in the prompt, the server tools (`remember`, `recall`, `play_animation`, `set_mood`, `change_body_part`, `list_outfits`,
`set_outfit`, `list_bodies`, `switch_body`, `web_search`, `fetch_page`, `list_files` and the other file functions, `list_chat_images`, `save_chat_image`, `offer_file_to_clipboard`) and the nodes' tools and approvals (`NodeService` registers a
`COLLECT_TOOLS` listener).

**The tools are the same every turn.** The model renders the functions into the prompt before the history, so a function that comes and goes makes the server's cache of the prompt start over (a part
request that opens, a body with no outfit, a file in the workspace, a node that connects). Joi's own functions (`decide_part_change`, `change_body_part`, `play_animation`, `set_mood`, the outfit and
body functions, `list_files` and the other file functions, `offer_file_to_clipboard`) are therefore offered in every turn and fail with a message when there is nothing to work on; a schema carries an `enum` of names only
when it has a name (an empty `enum` is no schema). What the user or a node decides stays conditional, and so does `view_image`, which depends on the model (it is there when her chat model is multimodal; the pictures it asks for come in a message after the results of the round, added by `CallToolsStep`, which adds the pictures of the nodes' tool results the same way, from `ToolImages`, described for a model that cannot see them: see *Pictures in a tool result* in [The protocol](protocol.md)): a denied server tool, a search with no source, the tools of a node's manifest.
`NodeServiceTest.theToolsAreTheSameWhetherARequestIsOpenOrNot` has the case.

## Her mood

A mood changes in one place, `ChatService.changeMood(joi, key, byUser)`: it saves the mood (`JoiService.saveMood`), writes a note (`log`, never given to the model), tells
the watchers (`watchMoods`: `NodeService` sends the `mood` frame to her nodes, a watcher as for `watchLogs` so that no service needs the other), and, when the user
changed it and she has chatted, queues `mood.changed`. The day's roll tells the watchers too. `MoodTools` offers `set_mood` to every turn through `COLLECT_TOOLS`: her
own choice, so no event. The face of a mood is `Mood.face` (`MoodFace`, the built-in value changed by the user's `MoodSetting`), sent with the frame.

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

## Images

A message may carry images (`TurnInput.images`, the uuids of uploads: see *Images* in [The protocol](protocol.md)). `ChatServiceImpl.chat` checks them first (`checkImages`: the user's own, at most 4, and
`ChatImageService.canSee`, else `NoVisionException`) and lets the words be empty when there is an image; an event never has images. `StoreStep` writes their uuids into the message's
extended attribute `images` and `ChatImageService.attach` ties the uploads to it (they are no longer swept); its prompt record keeps the attachments' name, type and size, never the bytes.

`PayloadStep.withImages` gives every message of the history and the current one the same form **in every turn**, because the form is part of the cached prompt: a multimodal chat model
(`AI.isMultimodal`) gets each image as an `LLMAttachment` (the stored JPEG, so the same data URL every time) after the words; any other model gets the template `IMAGE_NOTE` once for each image,
with the text the describer wrote. The describer is her deep reasoning model, else her chat model, when it is multimodal (`ChatImageService.describer` = `AppearanceService.model`); for the current message of a chat model that cannot see, `PayloadStep` has it
describe the images (`describe`: one blocking round of `IMAGE_DESCRIPTION_PROMPT` with the image and the user's words, five minutes at most) and keeps the notes in the turn's properties
(`TurnProperties.IMAGE_NOTES`); `StoreStep` stores them as the message's `imageNotes`, so the next prompts read the note and never ask the describer again. A message of the history that has
images and no words is in the history like any other. The summarizer's transcript has "[attached an image: note]"; deleting a message deletes its files (`ChatMessageServiceImpl.deleteMessages`
→ `ChatImageService.deleteOf`). `ChatImages` is the pure part (decoding, EXIF orientation, scaling to 1568 pixels, JPEG) and has its own test.

## Inference

`InferenceStep` runs a round. The guard against a call written as text (`CallTextGuard`, a setting of the AI in `joi` attributes) holds back the
first words of a round until they are known to be words; a round that opens with a call is cancelled and asked again, twice at most. The
placement of the function-call rule, images and function definitions are settings of the AI, written into the request by the platform.

## Tasks after the reply

A tool call can start a **task** (`JoiTask`, `WebTaskService`): a call to inference of its own that does not start during the reply. It waits
(`QUEUED`) until the message that started it is finished, spoken included, and then runs on the deep reasoning model, one at a time per
assistant. Its result comes back to the conversation as an event message that the server starts as a turn, so she tells it in her own voice. The event also carries what she asked for (the whole question, when the title is cut short) and the
message of the user she started the task for (`WebTaskServiceImpl.origin`: the latest message of the user before the task was made), so what
that message asked besides the answer (the result on the clipboard, say) is still in front of her when the task is done.
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
