# Pictures the manual still needs

The list of pictures to take for the manual, with the page that shows each, the flow that takes it and the state it needs.
The pictures that exist are in `SCREENSHOTS.md` (the table made by `bin/list-images.py`); this file is the plan for the others.
Take each one with the scripted environment (`SCREENSHOTS.md`), look at it, then `bin/publish.sh`. Mark it done here.

Status: `[ ]` to take, `[x]` taken and published, `[-]` dropped (why).

## Without a new state (a flow that only looks, on `07-body`)

| | Picture | Page | Flow | Shows |
|---|---|---|---|---|
| [x] | `settings-prompts` | settings.md | `shoot-settings` | User Settings, Prompts tab |
| [x] | `settings-personalities` | settings.md, jois/persona.md | `shoot-settings` | Personalities tab with the built-in ones |
| [x] | `settings-moods` | jois/persona.md | `shoot-settings` | Moods tab |
| [x] | `settings-access` | access.md | `shoot-settings` | Access tab |
| [x] | `settings-tools` | tools/index.md | `shoot-settings` | Tools tab: permissions and the web search panel |
| [x] | `admin-gpus` | administration/index.md | `shoot-settings` | Administration, GPUs |
| [x] | `admin-api-access` | administration/index.md | `shoot-settings` | Administration, API access |
| [x] | `admin-backups` | administration/index.md | `shoot-settings` | Administration, Database Backups |
| [x] | `tts-models` | models/speech.md | `shoot-settings` | Models → TTS Models |
| [x] | `voice-actors` | models/speech.md | `shoot-settings` | Models → Voice Actors |
| [x] | `text-model-dialog` | models/text-models.md | `shoot-settings` | the dialog of a text model |
| [x] | `joi-templates` | jois/persona.md | `shoot-joi` | her dialog, Templates tab |
| [-] | `joi-memories-empty` | jois/memory.md | `shoot-joi` | her dialog, Memories tab, one memory added by hand and open | (dropped: `joi-memories` in the conversation step shows the tab with memories)
| [-] | `joi-animations` | animations/animations.md | `shoot-joi` | her dialog, Animations tab | (dropped: `joi-animations-roles` shows the tab with the roles chosen)
| [x] | `model-toggles` | bodies/models.md, bodies/appearance.md | `shoot-model` | the model dialog: expressions and the toggle rows |
| [-] | `model-spring-bones` | bodies/spring-bones.md | `shoot-model` | the Spring bones panel | (dropped: the panel is shown for a converted model only, the demo model is an uploaded VRM)
| [-] | `outfit-dialog` | bodies/outfits.md | `shoot-model` | the outfit dialog | (dropped: `outfit-school-uniform` and the other outfit pictures show the dialog)

## With a new state

| | Picture | Page | Flow (step it makes) | Shows |
|---|---|---|---|---|
| [x] | `tab-clips-library` | animations/clips.md | `07-animations` (`08-animations`) | the Clips tab with a few clips (converted from `tmp/animations`) |
| [x] | `clip-dialog` | animations/clips.md | `07-animations` | a clip playing on the body |
| [x] | `animation-dialog` | animations/animations.md | `07-animations` | an animation with its steps |
| [x] | `joi-animations-roles` | animations/animations.md | `07-animations` | her Animations tab with the roles chosen |
| [x] | `joi-chats` | jois/chats.md | `08-conversation` (`09-conversation`) | her Chats tab with an exchange, reasoning and tools used |
| [x] | `joi-memories` | jois/memory.md | `08-conversation` | her Memories tab with a memory she wrote |
| [x] | `joi-tasks-waiting` | jois/tasks.md | `08-conversation` | her Tasks tab with a search that waits for approval |
| [x] | `node-chat` | node/javafx.md | `node-chat` (needs `08-conversation`) | the JavaFX node with her body and the chat |
| [x] | `node-controls` | node/javafx.md | `node-chat` | the controls panel, Her parts |
| [x] | `node-approval` | node/approvals.md | `node-chat` | the dialog of a task that waits |
| [ ] | `joi-summaries` | jois/summaries.md | `08-conversation` | her Summaries tab: a part of a day, a day, a week and the one a week covers dimmed (needs a rule for the summary prompts in `llm/rules.mjs`) |
| [ ] | `admin-jobs` | administration/jobs.md | `shoot-settings` | Administration, Jobs, with a chat turn and a summary in the log (queue answers for the fake model) |
| [ ] | `jobs-live` | administration/jobs.md | `shoot-settings` | the page /jobs with a turn that runs |
| [ ] | `admin-extensions` | administration/extensions.md | `shoot-settings` | Administration, Extensions, with the grok extension loaded (the flow needs the JAR in the data folder) |
| [ ] | `node-clipboard-offer` | node/clipboard.md | `node-chat` | the dialog that shows a text she offers for the clipboard (the node flow queues a `desk__offer_to_clipboard` call) |
| [x] | `node-looking-glass-tuning` | node/looking-glass.md | `node-chat` | the tuning pane of the Looking Glass |

## Rules for these

* The fake model writes what the flow needs: add rules to `llm/rules.mjs`, or queue an answer with `POST /_queue`.
* A picture of the node needs her body in the node's cache: the node flow connects first, then waits for her body.
* Do not take a picture that would show something that is not there: the Looking Glass display itself is not taken.
