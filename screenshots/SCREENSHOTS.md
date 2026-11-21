# Screenshots of the manual

This folder is a **scripted environment that takes every picture of the manual** (`manual/images/`) from the same
dataset: its own Joi server with its own data folder, a fake inference server instead of a model (so no API key and no
network), headless Google Chrome driven by Playwright for the server's UI, and the JavaFX node in a **screenshot mode**
that a script drives through commands. It follows Marginalia's environment (`../marginalia/screenshots`); nothing here
touches `~/.joi` or `~/.joi-client`.

The text is written for an agent (or a person) who has to retake pictures: what exists, how to start it, how to get back
to an earlier state, and what each script does. Read [Rules of the game](#rules-of-the-game) before you change a script.

```
screenshots/
  SCREENSHOTS.md      this file
  package.json        playwright-core (the browser is the installed Google Chrome, nothing is downloaded)
  bin/                env.sh, start-app.sh, stop-app.sh, start-llm.sh, stop-llm.sh, start-node.sh, stop-node.sh,
                      snapshot.sh, restore.sh, rebuild.sh (+ chain.txt), shoot-all.sh, shoot-some.sh, publish.sh, list-images.py
  flows/              the scripts (NN-name.mjs = a step of the setup, shoot-*.mjs = only pictures, node-*.mjs = the JavaFX node)
  lib/                shot.mjs (browser, login, dialogs, snap helpers), node.mjs (the controller of the node)
  llm/                mock-llm.mjs (fake OpenAI compatible server) and rules.mjs (what it answers)
  dataset/            the content that the flows put in (empty so far)
  assets/             pictures that flows upload (not in Git when they are not ours)
  steps/              step backups: <name>.tar.gz of the whole data folder after each step   (not in Git)
  .data/              joi/ is the server's data folder (JOI_DATA), joi-client/ the node's settings (JOI_CLIENT)   (not in Git)
  .run/               pid files, logs of the server, the node and the fake model, request log   (not in Git)
  out/                the pictures that were taken, before they are copied to the manual   (not in Git)
```

## Requirements

* the server builds and runs from this checkout: `bin/start-app.sh` runs `mvn -o -pl joi-server jetty:run` (JDK 25+, set in
  `bin/env.sh`; the first start builds the frontend and takes minutes). Build `joi-protocol` first when it changed
  (`mvn -o -q install -DskipTests -pl joi-protocol`);
* Node.js 20+ (`npm install` in this folder, once), Google Chrome (`/Applications` on a Mac);
* for the node: the `fx` profile builds (OpenJFX and JCEF are in `~/.m2`), and a screen: **OpenJFX has no headless mode**, the
  window of the node opens while a node flow runs. Do not use the machine's mouse and keyboard meanwhile; the harness does not
  need them (it does not click on the screen, it presses the buttons of the scene), but a window that is moved or covered is
  not a problem for it either.

Ports: the server runs on **8099**, the fake model on **8098**, the node's commands on **8097** (`bin/env.sh`), so the
development server on 8090 is not in the way. The URL is `http://127.0.0.1:8099`, not `localhost`: cookies ignore ports, two
`localhost` servers log each other out.

## Accounts and the dataset

| What | Value |
|---|---|
| Administrator | `admin` / `joi-demo` (full name *Administrator*), made by the first start |
| 3D models | *Saiki* (a VRM, step `02-saiki`; its appearance is applied in `06-appearance`) |
| Text models | *Local chat* (Chat, Auxiliary) and *Local reasoner* (Deep reasoning, reasoning on, multimodal), both pointing to the fake server `http://localhost:8098/v1` (placeholder key `demo-key`); they are the user's default models and her own |
| Assistant | *Joi* (default personality *Warm companion*), body *School* of Saiki with the outfits *School uniform*, *Light* and *Cold day* |

The chain (`bin/chain.txt`, each step a flow and a snapshot): the first start (`01-admin`), the Saiki model uploaded (`02-saiki`), two
text models (`03-models`), the assistant (`04-joi`), the user's default models (`05-user-models`), how she looks in Saiki
(`06-appearance`) and her body with three outfits (`07-body`). Planned: a few animations from `tmp/animations`, a conversation, a
task that waits for approval.

The fake model **writes what a multimodal model would**: it never looks at a picture, a rule answers by the text of the prompt
(`llm/rules.mjs`, the words are in `dataset/saiki.mjs`): the appearance (`self-image`: JSON with the description and a name for each
toggle of the table in the prompt) and the outfits (`outfit-image`: the description by the outfit's name).

### The model of the pictures: Saiki

The 3D model of the pictures is **Saiki**, a VRM 0.x (33 443 triangles; the user's own download is `tmp/saiki.zip`: a zip with
`source/saiky_with_tail.zip`, which holds `saiki.vrm`, `saiki_with_bag.vrm`, an FBX and a `.blend`). It has cat ears (part of the
hair), a scarf, a tie, a blazer, a shirt, a skirt, shoes and a shoulder bag, and spring bones for hair and skirt. The pictures use
`saiki_with_bag.vrm`.

* **Licence.** The Sketchfab page says CC BY-SA 4.0 by viktorifuy; the VRM's own meta says author *mdodap*, licence *CC0*, use
  *only the author*. The credits page names both, and the pictures, and the files made from it, are treated as share-alike.
  Confirm the terms before the manual is published.
* **Outfits.** As shipped the model has **no switchable clothes**: Face, Hair and Body are three meshes, and the clothes are ten
  primitives (one per material) of the Body mesh, while Joi's toggles are nodes. `dataset/split_saiki.py` gives each primitive its
  own node (the binary data is not touched, only the glTF JSON), so the model has the toggles *Shoes, Skirt, Body, Shirt, Blazer,
  Bag strap, Bag, Tie, Scarf, Back hair* (+ *Face.001*, *Hair001.001*). The result is a derivative: keep it in `tmp/saiki-work/vrm/`.
  Outfits are presets of those toggles, *School uniform* (all), *Light* (no scarf, no bag) and *Cold day* (scarf, no bag). Not the blazer: its mesh holds the sleeves, so a
  body without the blazer has no arms.
* **Preparing it again:** `unzip -oq tmp/saiki.zip -d tmp/saiki-work`, `unzip -oq tmp/saiki-work/source/saiky_with_tail.zip
  saiki_with_bag.vrm -d tmp/saiki-work/vrm`, `python3 screenshots/dataset/split_saiki.py tmp/saiki-work/vrm/saiki_with_bag.vrm
  tmp/saiki-work/vrm/saiki_split.vrm`, then the step `02-saiki` (`flows/01-model-saiki.mjs`) uploads it through the Bodies tab.
**Never use the purchased test avatars of the spikes (`tmp/ryuu`, the Cherie package) in a picture.**

### The fake model

`llm/mock-llm.mjs` (no dependencies) answers `GET /v1/models` and `POST /v1/chat/completions` (streaming SSE with usage,
reasoning in `reasoning_content`, or plain JSON). **What** it answers comes from `llm/rules.mjs`: the first rule whose
`when(ctx)` is true. The server restarts itself when `llm/` or `dataset/` change (`node --watch`).

Control endpoints: `POST /_queue {"reply": "...", "reasoning": "...", "status": 429, "message": "...", "toolCalls": [{"name": "...", "arguments": {...}}]}`
(a one-shot answer that wins over the rules), `POST /_mode`, `GET /_log` (the last requests, also all in
`.run/llm-requests.jsonl`), `POST /_pace {"delayMs": 3000, "chunkDelayMs": 250}`, `POST /_reset`. When a new kind of request
shows up, look at the log, then add a rule.

A picture that must show a **real** answer (a long conversation, a description of a body) can use a real local model instead:
point the model of the dataset at it and say so in the flow. The default stays the fake one, so the pictures can be retaken
without a model server and look the same every time.

## Commands

```sh
cd screenshots && npm install                     # once
bin/rebuild.sh                                    # the whole setup chain from the empty installation
bin/rebuild.sh 01-admin                           # restore that step and run the rest of the chain
bin/shoot-all.sh                                  # all pictures that the chain does not take itself (web UI and node)
bin/publish.sh --dry ; bin/publish.sh             # compare / copy out/*.png to manual/images/
bin/shoot-some.sh 01-admin shoot-workspace.mjs    # restore a state and run only those flows
bin/restore.sh 01-admin                           # go back to a step (stops the app, replaces the data folder, starts the app)
bin/snapshot.sh <name>                            # save the current data folder as steps/<name>.tar.gz (stops and starts the app)
bin/start-app.sh | bin/stop-app.sh | bin/start-llm.sh | bin/stop-llm.sh | bin/start-node.sh [--fresh] | bin/stop-node.sh
node flows/shoot-workspace.mjs                    # one flow
python3 bin/list-images.py                        # the table of all pictures below
```

`bin/snapshot.sh` and `bin/restore.sh` stop the server first, because SQLite keeps a write-ahead log: a copy of the folder
of a running server is not consistent. `restore.sh 00-empty` gives an empty data folder.

Every web flow starts a fresh browser, logs in and works through the real UI. Pictures are written to `out/<name>.png`;
names starting with `_` are scratch files (`_fail*.png` is the page at a failure). The text of the platform's build
(*Marginalia dev*) under the login button is hidden; `SHOT_TOASTS=1` shows the toasts (hidden in all pictures, except the ones
that are about a toast).

**One heavy thing at a time:** the machine also runs the user's IDE. The server (about 2 GB), Chrome, a Maven build and the
node (JCEF) together are too much: stop what a step is done with (`bin/stop-app.sh`, `stop-llm.sh`, `stop-node.sh`).

## The JavaFX node (`node-*.mjs`)

The node has a **screenshot mode**: `Launcher shots <out dir> <port>` (what `bin/start-node.sh` runs) starts the normal app
(`JoiNodeApp`, in the same process) and a small command server on `127.0.0.1:<port>` (`fx/NodeShots`, `fx/ShotCommands`).
Nothing else starts it. A flow drives the app with the commands of `lib/node.mjs`:

| Command | What it does |
|---|---|
| `click(text)` | presses the button with this text |
| `type(prompt, text)` | sets the field with this prompt text |
| `combo(text)` | sets the editor of the editable combo box (the server address) |
| `wait(text, seconds)`, `waitStarting(start)` | wait until a label or button shows (the second returns its text) |
| `texts()` | the texts on screen, to see where the app is |
| `openMenu(...path)`, `closeMenus()`, `chooseMenu(...path)` | the menu bar (a bar in the window: the macOS system menu is switched off in this mode) |
| `snap(name)` | the window, with the open menus on it, as `out/<name>.png` (2x, `NODE_SHOT_SCALE`) |
| `snapDialog(name)`, `dialogOpen()` | the newest dialog alone (the questions about a task, the list of auto-approved tools) |
| `quit()` | ends the app |

The pictures are `Node.snapshot` of the scene graph, so they need no screen recording permission, and the Chromium pane
that shows her body is part of them (it is painted off screen into the scene). A flow that needs the node in a state (a task
that waits for approval, a notification question) makes the state on the server first, through the HTTP API or the web UI,
then drives the node. The settings of the node are `.data/joi-client`: `bin/start-node.sh --fresh` empties them, so the
first-start screens can be taken again; `shoot-all.sh` starts each node flow fresh.

**The Looking Glass** is not shown: only its controls (the tuning pane, `fx/LookingGlassTuningPane`, and the controls pane)
are taken, with the node's own commands, never the display.

## Pictures

(Generated by `python3 bin/list-images.py`.)

| Picture | Flow | State | Used in |
|---|---|---|---|
| `admin.png` | `shoot-workspace` | any step from 01-admin (the last one) | - |
| `appearance-applied.png` | `05-appearance` | step 05-user-models | - |
| `appearance-proposal.png` | `05-appearance` | step 05-user-models | - |
| `appearance-snapshots.png` | `05-appearance` | step 05-user-models | - |
| `body-dialog.png` | `06-body` | step 06-appearance | - |
| `first-start.png` | `00-first-start` | step 00-empty | manual/user/getting-started/first-start.md |
| `joi-about.png` | `03-joi` | step 03-models | - |
| `joi-bodies.png` | `06-body` | step 06-appearance | - |
| `joi-create.png` | `03-joi` | step 03-models | - |
| `login.png` | `00-first-start` | step 00-empty | manual/user/getting-started/first-start.md |
| `model-dialog.png` | `01-model-saiki` | step 01-admin | - |
| `models-text.png` | `02-text-models` | step 02-saiki | - |
| `node-after-connect.png` | `node-first-start` | 01-admin, a node that was never started | - |
| `node-joi-menu.png` | `node-first-start` | 01-admin, a node that was never started | - |
| `node-server.png` | `node-first-start` | 01-admin, a node that was never started | - |
| `outfit-cold-day.png` | `06-body` | step 06-appearance | - |
| `outfit-light.png` | `06-body` | step 06-appearance | - |
| `outfit-school-uniform.png` | `06-body` | step 06-appearance | - |
| `settings-general.png` | `04-user-settings` | step 04-joi | - |
| `tab-animations.png` | `shoot-workspace` | any step from 01-admin (the last one) | - |
| `tab-bodies.png` | `shoot-workspace` | any step from 01-admin (the last one) | - |
| `tab-bodies-saiki.png` | `01-model-saiki` | step 01-admin | - |
| `tab-clips.png` | `shoot-workspace` | any step from 01-admin (the last one) | - |
| `tab-jois.png` | `shoot-workspace` | any step from 01-admin (the last one) | - |
| `tab-jois-joi.png` | `03-joi` | step 03-models | - |
| `tab-models.png` | `shoot-workspace` | any step from 01-admin (the last one) | - |
| `tab-trash.png` | `shoot-workspace` | any step from 01-admin (the last one) | - |
| `tab-user-settings.png` | `shoot-workspace` | any step from 01-admin (the last one) | - |
| `workspace.png` | `shoot-workspace` | any step from 01-admin (the last one) | manual/user/getting-started/first-start.md |

## Rules of the game

1. **One state per picture.** A flow that changes data must be a step of the chain (and then a snapshot follows). A flow that
   only looks may be run on any state, but the pictures of a page must come from the state the table names.
2. **Never use the real data.** All data is in `.data/`; the scripts use ports 8099, 8098 and 8097. Do not point them to
   `~/.joi` or `~/.joi-client`.
3. **After a flow fails**, the data may be half changed: `bin/restore.sh <step>` and run it again. `_fail*.png` in `out/` shows
   the page (or the node's window) at the failure.
4. **Fields of Vaadin.** `fill()` of `lib/shot.mjs` sends a `change` event and types a password field key by key (a password
   field only synchronizes from real key presses). Combo boxes are picked with `pick()`, Vaadin `Select` with `pickSelect()`.
   Dialogs are `vaadin-dialog[opened]`; `snapDialog()` takes the n-th smallest one.
5. **Coordinates** are for 1920x932. Prefer a control's text or icon to a coordinate: a layout change then does not break a flow.
6. After a flow that was changed: run it from the step before it (`bin/rebuild.sh <step before>`), look at the pictures with
   your own eyes, then `bin/publish.sh`, `tools/retype_build.sh` (see `CLAUDE.md`) and update the table above.
7. A picture that shows the model shows Saiki, and the credits page says so.
