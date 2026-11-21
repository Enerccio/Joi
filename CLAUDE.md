# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Joi: a self-hosted personal AI assistant. A Java **server** (the brain: LLM tool loop, memory, moods, bodies, events,
a Vaadin UI) and Java **nodes** (one per machine: microphone, wake word, speech-to-text, harness, OS tools, avatar
cache), with an avatar shown on a Looking Glass Go. **`DESIGN.md` is the design and the source of truth**: read it
before working on anything, and when the user shares a new idea or decision, write it into the matching section of
`DESIGN.md` (and into Roadmap / Open decisions when it changes them).

**Status:** design, spikes, and the first module: `joi-server` boots on Marginalia's platform (login, then a
workspace with a Bodies part (3D models: `Model`, uploaded as a resource (a model file, a zip or a .unitypackage), converted to VRM with Blender (`domain/bodies`, `ModelConversionService`), what it can do read from the VRM (`ModelCapabilities`: expressions, toggles, bones; named and tried in the dialog), the bodies of an assistant (`Body`: her models, appearance, the active one; her dialog's Bodies tab `ui/dialogs/joi/JoiBodiesPart`, `ui/dialogs/BodyDialog`, `BodyService` also writes the prompt's `{{#body}}`; how she looks is the model's: snapshots and close-ups of the toggles from the viewer, the user's images and notes, the description, the toggle names and a sentence per toggle state proposed by a multimodal model, `AppearanceService`, `ui/bodies/AppearancePanel`, the default state and the user's groups of toggles with named states, `ToggleGroup`, `ui/bodies/ToggleGroupsPanel`; the parts described apart, the only ones she can switch, are `DescribedPart`; put together for a state by `Appearance`; the body's state of its parts is `BodyState`, switched in the body dialog, an event for her), the body's outfits (`Outfit`: presets of the model's toggles with a description, put on in the body dialog or by her; `ui/dialogs/OutfitDialog` with the photoshoot that has a multimodal model describe it; her functions `list_outfits`, `set_outfit`, `list_bodies`, `switch_body` in `domain/chat/BodyTools`; a new body or outfit is one `body.added` event, `BodyService.announce`), the layers of her look (*Auto description*: the full shots and her description are of the bare model, `Model.bareShown`), the tree of toggles (a shape key is part of its mesh, the user can change it; what is seen is the effective state: `ToggleTree`, `Model.toggleParents`, `BodyToggle.parent`), the spring bones guessed from the bones' names and written into the converted VRM (`SpringBones`, `SpringChain`, `ui/bodies/SpringBonesPanel`), the model's `.joim` (a processed model in one file: the VRM and what was decided on it) written on the dialog's Exit when it changed and imported by its upload, and the cleanup of the files a converted model no longer needs (`ModelService.cleanUp`), the animation library: a library of clips (VRMA, or FBX / BVH converted with Blender by `convert_animation.py` in `domain/animations`, `AnimationConversionService`; the Blender process and its one-at-a-time lock are `domain/bodies/Blender`) and animations (what she plays: steps of clips, each looping or not, with its speed, in place, reversed or not, turned by a quaternion (`StepRotation`) and blended in from the step before, the whole animation looping or not; an "animation" in the UI is an `AnimationPack`, its steps `domain/animations/AnimationStep`), played on a chosen body (`Animation`, `AnimationPack`, `domain/animations/VrmaFiles`, `AnimationService`, `AnimationPackService`, the Clips and Animations tabs `ui/workspace/parts/AnimationsPart` and `AnimationPacksPart`, `ui/dialogs/AnimationDialog` and `AnimationPackDialog`, `ui/bodies/ClipPreview`; the viewer plays them with `@pixiv/three-vrm-animation`; animations are exported with their clips as a `.joia` and imported, clips linked by uuid: `JoiaService`, the container `protocol/joia/JoiaFile` in `joi-protocol`; a body plays all of the library or the animations chosen in its dialog, written into the body's `.joia` on Exit: `Body.animations`, `JoiaService.exportBody`) (`JoimService`, `domain/bodies/joim/JoimMetadata`; the container `protocol/joim/JoimFile` is in `joi-protocol`), the model and body dialogs split between the fields and a three.js preview (`ui/widgets/EditorSplit`) in `frontend/joi-model-viewer.js`; `ui/workspace/parts/BodiesPart`, `ui/dialogs/ModelDialog`, `ui/bodies/ModelViewer`), a Models part: text models (the platform's AIs), TTS models and voice actors; User Settings: the
assistant's name, the user's name, personality, model roles, voice, the prompts and the personalities and moods; and, for administrators, an
Administration page behind a button under the tabs, as in Marginalia: users and GPU locks). The data model of the assistant is built (no UI or inference yet): `Joi`, `ChatMessage`, `MessagePrompt`, `Summary`,
`Memory`, `JoiTask`, `JoiTaskMessage`, `QueuedEvent` (see `DESIGN.md` → Several assistants), with the Jois tab and the Joi dialog (its Chats tab shows the transcript, lazily, and deletes messages, one or checked with shift-click ranges; its Memories tab lists, adds, edits and deletes her memories, `JoiMemoriesPart`; its Animations tab chooses what she plays asleep, falling asleep, waking up, idle and talking, `AnimationRoles`, `JoiAnimationsPart`; `ui/workspace/parts/JoisPart`,
`ui/dialogs/JoiDialog` and its parts in `ui/dialogs/joi`; its Tasks tab answers the tasks that wait for approval, `JoiTasksPart`; its Summaries tab `JoiSummariesPart` lists what she remembers of the past, and the Chats tab's Summarize button writes down the messages up to the last one checked). The summaries are built: after a pause, when the conversation grows, a day, a week, a month, a year, written by the Summary-role model through a queue per GPU or per model (`SummarizerService`, `domain/summary`: `SummaryPlan`, `SummaryQueue`; her prompt shows them in `{{#summaries}}` and the history starts where they end; each one is told to her as an event; see `DESIGN.md` → Memory, Summaries, built). The server's own tools are built: a permission along the chain assistant → user → administrator (`ToolPermission`, `ServerTool`, `ToolMetadata` with its options and protected secrets, `ToolPermissionService`, `ui/tools`), `web_search` and `fetch_page` as tasks of the stronger model after her reply (`domain/web`, `WebTaskService`, `WebResearch`; sources DuckDuckGo, SearXNG, Google, Brave, Tavily, Perplexity; page readers direct, crawl4ai, the user's wrapper; a standing "approve all of this tool"), the tasks in the API (protocol 1.6) and notes of the system in the conversation (`ChatMessageType.LOG`, never given to the model, the node's `log` frame); see `DESIGN.md` → Server tools and their permissions, Web search and reading pages, Notes in the conversation). The HTTP API is built (`joi-protocol` 1.0: list, info,
chat with text and speech, whole or streamed (a turn is asynchronous steps and events in `domain/chat/turn`, as Marginalia's generation: listeners, observers, tool rounds; see `DESIGN.md` → A chat turn); 1.1: her body (models, the state of their parts, animations) and the
`.joim` / `.joia` downloads, ETag by hash; tokens, OAuth device grant, no auth for development; 1.2: her animation roles in the info and the node WebSocket (`api/NodeEndpoint`, `domain/service/NodeService`, `domain/node/NodeSession`: handshake, manifest of tools offered as `<node>__<tool>`, say and her streamed answer, tool calls, her first waking up and the greeting as server-started turns from templates); see `DESIGN.md` →
The node protocol): `api/` (`JoiApiServlet`, `JoiApi`), `domain/chat/ChatService`, `ui/access`. `spikes/` holds throwaway experiments for the avatar renderer.

## Build

Maven reactor at the root (`joi-parent`): `marginalia/marginalia/platform` (from the submodule), `joi-protocol`,
`joi-server` and `joi-node`.
JDK 25+, Maven 3.9. Clone with `git clone --recurse-submodules` (or `git submodule update --init`).

```bash
mvn install -DskipTests -pl marginalia/marginalia/platform   # the platform and its test-jar into ~/.m2, after each submodule bump
mvn -N install && mvn install -DskipTests -pl joi-protocol   # the parent POM and the protocol into ~/.m2 (for -pl joi-server alone)
mvn test -pl joi-server -Dtest=JoiContextTest                 # joi-server's tests (the classes you touched)
mvn jetty:run -pl joi-server                                  # dev mode on http://localhost:8080, data in ~/.joi (JOI_DATA)
mvn package -pl joi-server -Pproduction                       # the WAR with the production frontend bundle
mvn test -pl joi-node                                         # the node's core and its terminal client (JoiNodeCli)
mvn -o -Pfx compile exec:exec@run -pl joi-node               # the JavaFX node (OpenJFX 25 and JCEF are in ~/.m2)
mvn -o -Pfx compile exec:exec@run -pl joi-node -Dnode.args=preview   # her cached body in Chromium, no server
tools/retype_build.sh                                         # the manual into docs/ (needs `retype`)
screenshots/bin/shoot-all.sh                                  # retake the pictures of the manual (screenshots/SCREENSHOTS.md)
```

`-pl joi-server -am` also builds the platform first (its tests are skipped by `-Dtest=...` with
`-Dsurefire.failIfNoSpecifiedTests=false`). Don't run the platform's own 673 tests from here; they belong to Marginalia.

## Coding style: follow Marginalia

`../marginalia` is the template for structure and style (see its `CLAUDE.md` and `manual/developer`). When the server
and node code is written, it follows the same rules unless `DESIGN.md` says otherwise:

- Java 25, Maven; Vaadin Flow UI with no REST API / separate frontend for the UI; Spring **XML** wiring without Spring
  Boot (a new service / repository needs its bean in `services-config.xml`); Hibernate + SQLite + Flyway
  (`hbm2ddl=validate`, every entity change has a migration).
- `BaseEntity` / `OwnedEntity` / `ExtendableEntity` with `id` + stable `uuid`, soft deletes, `@ExtendedAttribute` for
  fields nobody queries in SQL; `Jpa*Repository`, `*ServiceImpl`, `@CommonTx` / `@CommonTxReadOnly`; ownership checked
  through `findForUser`, never raw ids.
- UI classes created with `new`, `@Configurable` with compile-time AspectJ weaving (plain javac leaves injected fields
  `null`); UI updates from other threads through `ui.access(...)`.
- UI strings in a localization enum + bundles, CSS classes instead of inline styles, scripts in one JavaScript library
  class, dialogs with focus + keyboard submit.
- Tests boot the real Spring config on SQLite with a fake model server: no network or API key. Run the classes you
  touched, not the whole suite.

The node ↔ server protocol (`joi-protocol`) is a **public, documented API**: JSON Schemas + AsyncAPI are the source of
truth and the Java records follow them (see `DESIGN.md` → The node protocol). Change the schema and its docs together
with the code.

## Layout

- `joi-protocol/` — the public API: JSON Schemas, `openapi.yaml` (HTTP) and `asyncapi.yaml` (the node WebSocket, 1.2,
  served by `api/NodeEndpoint` and `NodeService`; records in `protocol/node`) in `src/main/resources/joi-protocol` (the source of
  truth; `SchemaContractTest` checks the records against them), records in `com.github.enerccio.joi.protocol`, Gson
  only. A change of the API changes the schema, the record and `DESIGN.md` → The node protocol together.
  The `.joim` and `.joia` containers (`protocol/joim`, `protocol/joia`, documented in `joim.md` and `joia.md` there;
  their sections share `joim/Sections`) are part of it too: the format and its doc
  change together.
- `marginalia/` — git submodule of Marginalia, pinned at a commit on its `dev` branch; joi-server uses only its
  platform module (`marginalia-platform`). Bump it on purpose, never edit it from here (see `MARGINALIA.md`).
- `joi-server/` — the server WAR. Packages under `com.github.enerccio.joi`. Its own Spring XML
  (`src/main/resources/META-INF/spring`), persistence unit (`src/main/webapp/config/persistence.xml`) and Flyway history
  (`src/main/resources/migration`, the platform's tables included: a platform entity change needs a Joi migration too).
  **V1 stays open:** until the user says Joi runs on their production server, every schema change is edited into
  `V1__platform.sql` instead of a new version. Don't add V2 before that. A dev database from an older V1 fails
  Flyway's checksum: never delete it, upgrade it with `python3 tools/upgrade_dev_db.py` (server stopped; it backs the
  database up into `~/.joi/db-backups`, rebuilds it from the current V1, copies the data by shared columns and sets
  the checksum; `--dry-run` reports only). Run it after each V1 change, and say so to the user.
  The page after the login is `ui/workspace/JoiWorkspace` (tabs of `WorkspacePart`s, state kept by
  `WorkspaceAutosave`; parts in `ui/workspace/parts`). Speech is in `tts/` (servers, voices; copied from Marginalia's
  audiobook plugin, see `MARGINALIA.md`), with `TtsService` keeping the user's TTS models and voice actors as JSON in
  the user setting; ask `AdminGuard`, not the session's `User`, whether someone is an administrator.
  Built-in texts and values are in `defaults/JoiDefaults` (like Marginalia's `Defaults`); a setting resolves along the
  chain assistant → `JoiUserSetting` → `JoiDefaults` (`domain/persona/JoiPromptField`, `PersonaService`).
  **Every prompt sent to a model is such a template**, so the user can change it: a `JoiPromptField` (fields on `Joi`,
  `JoiUserSetting` and `PersonaAttributes`, the default in `JoiDefaults`, its variables in a `*TemplateData` with
  `@JoiTemplateDescription`, a label in `PromptAreas`), shown in both Templates tabs and rendered with
  `PersonaService.render(joi, field, data)`. Never build a prompt from hard-coded strings in a service.
  The API's configuration is `api/ApiConfiguration` (`joi.api.no-auth`, `joi.api.no-auth-user`, `joi.api.public-url`;
  no auth is on in `jetty:run` through the pom); what the administrator offers is `ApiAccess` (in the app settings).
  Template data extends `domain/templates/JoiTemplateData` (`{{char}}` / `{{user}}`); don't name a variable after a
  SillyTavern macro (`personality`, `persona`, `description`, `scenario`...): the platform swallows it. Text areas for
  prompts expand (`ui/widgets/PromptAreas`, the platform's `ResizableTextArea`). UI texts in `loc/J_L` + `JoiLocalizationBundle*`, styles in `frontend/styles/joi-styles.css` + `JoiCssStyles`. Tests
  extend the platform's `PlatformTestBase` and start `src/test/resources/META-INF/spring/test-application-config.xml`.
- `joi-node/` — the reference node (`com.github.enerccio.joi.node`): `ServerClient` (HTTP API, device grant, her
  body's files cached by hash), `NodeSocket` (the node WebSocket), `AvatarDirector` (what her body plays: idle,
  talking, asleep through her first turn, waking up), `NodeSettings` (`~/.joi-client`, `JOI_CLIENT`), `Notifications` (the node's first tool, `notify`, off until the user allows it), `TaskQuestions` (the tasks of hers that wait for the user's answer), `JoiNodeCli`
  (the node in a terminal), `renderer/` (her body's files unpacked, `BodyFiles`; the renderer page served on localhost and
  driven by server-sent events, `RendererServer`; `RendererPreview` shows cached files without a server). The page is
  `src/main/renderer` (Vite; the server's `joi-model-viewer.js` copied in at build time), built by the `fx` profile
  (`npm install --offline`, the packages are in the npm cache). The JavaFX app is `node/fx`, compiled only with `-Pfx`
  (OpenJFX 25, mac-aarch64, is in `~/.m2`). Her body is shown in the pane by Chromium (JCEF 152, `jcefmaven` with the
  macOS arm64 natives jar, unpacked into `~/.joi-client/jcef` on the first start, never downloaded at run time):
  `fx/Chromium` starts it, `org/cef/browser/JoiOsrBrowser` renders off screen into `fx/ChromiumPane`; on the Looking
  Glass the page is shown in the user's own Chrome instead (`fx/LookingGlassWindow`; see `DESIGN.md`). Run with
  `exec:exec@run` (`fx/Launcher`), not `javafx:run`, which leaves JCEF's jars off the class path.
- `manual/` — the source of the manual (Retype, `retype.yml`): `manual/user`, `manual/developer`, pictures in `manual/images`.
  `docs/` is its generated output: never edit or read it, it is not in Git. Build it with `tools/retype_build.sh`. The pages
  are stubs first; developer pages carry `covers` and `verified` (`python3 tools/check_dev_docs.py` lists the ones whose code
  changed). The `Manual` workflow (`.github/workflows/manual.yml`) publishes it from `master`.
- `screenshots/` — the scripted environment that takes the pictures of the manual (see `screenshots/SCREENSHOTS.md`): its
  own server (8099), a fake model (8098), Playwright for the UI, and the JavaFX node's screenshot mode (8097,
  `Launcher shots`, `fx/NodeShots`). Run one heavy thing at a time.
- `tools/upgrade_dev_db.py` — upgrades a dev database to the current V1 without losing its data (see above).
- `MARGINALIA.md` — what Joi uses from Marginalia's platform and what it provides itself.
- `DESIGN.md` — the design (architecture, server, node, avatar, spikes and their results, roadmap, open decisions).
- `joi-server/src/main/resources/com/github/enerccio/joi/domain/bodies/convert_avatar.py` — FBX → VRM 1.0 converter,
  headless Blender (the "VRM format" add-on), run by `ModelConversionService` (Blender: `JOI_BLENDER`, else
  Blender.app). By hand: `/Applications/Blender.app/Contents/MacOS/Blender -b --factory-startup --python
  <that file> -- <config.json>` (the config keys are in its docstring). When converting the test avatar, leave its
  adult parts out through the model's settings (shape keys to delete / bake), as the spike config did.
- `spikes/avatar-vrm/viewer/` — three.js + three-vrm + Looking Glass WebXR viewer (Vite, port 5181). Started with the
  `avatar-viewer` entry of `.claude/launch.json`. Keys and the benchmark are in `DESIGN.md` → Spikes.
- `tmp/` — a symlink to `/Volumes/Annex/joi/tmp`, excluded through `.git/info/exclude` (not `.gitignore`).

## Never commit test avatars or their assets

The VRChat avatar used for the spikes (in `tmp/`, and the Unity project on Annex) is a purchased asset for local tests
only: never commit, upload or paste its files anywhere, and keep converted outputs (`.vrm`, snapshots, reports) in
`tmp/`. Users bring their own avatars. The same goes for downloaded packages (Poiyomi, the Looking Glass plugin).
Don't add adult content from avatar packages to anything (the converter drops it by config).

## Machine notes (macOS, Apple silicon)

- **Disk:** the internal disk is nearly full. Big files (avatars, builds, Unity projects, caches) go to
  `/Volumes/Annex` (an APFS sparsebundle on a USB NVMe, mounted by a LaunchAgent; may be absent: check before use).
  Don't copy large files into the scratchpad.
- **Downloads** (packages, models, plugins) need the user's approval first; say the name, source and size.
- **Looking Glass Go:** Bridge crashes at start when the Go is already connected (hidapi on macOS 26): start Bridge
  first, then plug in the display. The Bridge REST API is on `localhost:33334`.
- **Unity:** use `2022.3.62f3` (the `76f1` install is broken). Batchmode (`Unity -batchmode -quit -projectPath ...`)
  only runs with the sandbox disabled, and takes minutes: run it in the background and wait for the notification.
  The test project is `/Volumes/Annex/unity/JoiLKG`; its setup script is `Assets/Joi/Editor/JoiSetup.cs`
  (`-executeMethod JoiSetup.All`). Looking Glass Unity plugin 3.2.8 (built-in pipeline; 4.x needs Unity 6 + URP).
- **JDK:** `mvn` on the PATH runs on Homebrew's JDK 24, which can't compile for 25. Set
  `JAVA_HOME=~/Library/Java/JavaVirtualMachines/corretto-26.0.2.1/Contents/Home` for Maven. Build with `-o` (offline)
  when the dependencies are already in `~/.m2`, so nothing is downloaded without asking.
- Run one heavy thing at a time (a Unity build, Blender, a Maven build): the machine also runs the user's IDE.

## Models

- During development the conversation role uses Claude Haiku 5.5 and the heavy role Claude Sonnet 5.5, with a local
  model tested on purpose (see `DESIGN.md` → Model roles). Never hard-code a model id or API key in code; they are
  settings.
- Never print, log or commit API keys.

## Other

- When creating new files, add them to git (`git add`), except `tmp/` and generated or downloaded assets. Commit or
  push only when the user asks.
- Write prose (docs, `DESIGN.md`) in plain, short sentences; keep `DESIGN.md` sections current rather than appending
  history.
- **Who verified (and who wrote):** `verification.md` is mainly a log of verifications (see `DESIGN.md` → Who wrote and
  who verified the code). A commit that is not in it is simply unverified. Verify only when the user asks: review every
  commit after the "Verified up to" line (the whole code while it says "nothing yet"), reading the code around each diff,
  not only the diff; fix what you find, add a row to Verifications (date, `<your model id>` as the environment reports it,
  e.g. `claude-sonnet-5-5`, the commits checked, notes: what you read, whether you ran the tests, what you fixed) and
  move "Verified up to" to the newest of them. Your own fix commits belong to that row and may be included in "Verified
  up to". Never mark a commit as verified without really reviewing it. Never rebase or squash verified history. "Written by"
  is secondary and optional: when you make a commit, you may add a row with its hash and your model id in your next change
  (a commit cannot contain its own hash), only for commits you made yourself. Do not put the model id into commit messages.
- When you implement a feature or fix, update the docs that describe it: user docs in `manual/user`, developer docs in
  `manual/developer` (and the picture flows in `screenshots/flows` when a screen changed).
- **Files go through the disk, not memory:** uploads stream into a file (`UploadHandler.toFile`), downloads and what
  the client or a node gets stream from a file (`DownloadHandler.forFile`), exports and imports are written and read
  as streams through temp files on disk. Models, images and archives can be large: never read a whole file into a
  byte array or a string when a stream or a file will do.
