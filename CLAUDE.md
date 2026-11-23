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
`ui/dialogs/JoiDialog` and its parts in `ui/dialogs/joi`; its Tasks tab answers the tasks that wait for approval, `JoiTasksPart`; its Summaries tab `JoiSummariesPart` lists what she remembers of the past, and the Chats tab's Summarize button writes down the messages up to the last one checked). The summaries are built: after a pause, when the conversation grows, a day, a week, a month, a year, written by the Summary-role model through a queue per GPU or per model (`SummarizerService`, `domain/summary`: `SummaryPlan`, `SummaryQueue`; her prompt shows them in `{{#summaries}}` and the history starts where they end; each one is told to her as an event; see `DESIGN.md` → Memory, Summaries, built). The server's own tools are built: a permission along the chain assistant → user → administrator (`ToolPermission`, `ServerTool`, `ToolMetadata` with its options and protected secrets, `ToolPermissionService`, `ui/tools`), `web_search` and `fetch_page` as tasks of the stronger model after her reply (`domain/web`, `WebTaskService`, `WebResearch`; sources DuckDuckGo, SearXNG, Google, Brave, Tavily, Perplexity; page readers direct, crawl4ai, the user's wrapper; a standing "approve all of this tool"), the tasks in the API (protocol 1.6) and her own workspace, a platform virtual file system per assistant with the administrator's limits, that her file functions, her tasks and the Files tab share (`JoiWorkspaceService`, `WorkspaceTools`, `JoiFilesPart`), notes of the system in the conversation (`ChatMessageType.LOG`, never given to the model, the node's `log` frame); see `DESIGN.md` → Server tools and their permissions, Web search and reading pages, Notes in the conversation). The HTTP API is built (`joi-protocol` 1.0: list, info,
chat with text and speech, whole or streamed (a turn is asynchronous steps and events in `domain/chat/turn`, as Marginalia's generation: listeners, observers, tool rounds; see `DESIGN.md` → A chat turn); 1.1: her body (models, the state of their parts, animations) and the
`.joim` / `.joia` downloads, ETag by hash; tokens, OAuth device grant, no auth for development; 1.2: her animation roles in the info and the node WebSocket (`api/NodeEndpoint`, `domain/service/NodeService`, `domain/node/NodeSession`: handshake, manifest of tools offered as `<node>__<tool>`, say and her streamed answer, tool calls, her first waking up and the greeting as server-started turns from templates); see `DESIGN.md` →
The node protocol): `api/` (`JoiApiServlet`, `JoiApi`), `domain/chat/ChatService`, `ui/access`. `spikes/` holds throwaway experiments for the avatar renderer.

## Build

Maven reactor at the root (`joi-parent`): `marginalia/marginalia/platform` (from the submodule), `joi-protocol`,
`joi-server` and `joi-node`.
JDK 25+, Maven 3.9. Clone with `git clone --recurse-submodules` (or `git submodule update --init`).

```bash
mvn -N install && mvn -N install -f marginalia/pom.xml       # Joi's and Marginalia's parent POMs into ~/.m2 (a new machine needs both)
mvn install -DskipTests -pl marginalia/marginalia/platform,joi-protocol   # the platform (and test-jar) and the protocol, after each submodule bump
mvn test -pl joi-server -Dtest=JoiContextTest                 # joi-server's tests (the classes you touched)
mvn jetty:run -pl joi-server                                  # dev mode on http://localhost:8080, data in ~/.joi (JOI_DATA)
mvn package -pl joi-server -Pproduction                       # the WAR with the production frontend bundle
mvn test -pl joi-node                                         # the node's core and its terminal client (JoiNodeCli)
mvn -Pfx compile exec:exec@run -pl joi-node               # the JavaFX node (OpenJFX 25 and JCEF come from Maven)
mvn -Pfx compile exec:exec@run -pl joi-node -Dnode.args=preview   # her cached body in Chromium, no server
mvn -B -DskipTests -Pfx,production,desktop package -pl joi-desktop -am   # the desktop app (server + node, jlink + jpackage) in joi-desktop/target/desktop
mvn -Pswift,ios verify -pl joi-ios -am                     # the iOS app: renderer page (npm), Swift packages' tests, xcodegen, xcodebuild for the Simulator (Xcode, XcodeGen)
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
  fields nobody queries in SQL (the default for a new field: no migration, see the frozen database below); `Jpa*Repository`, `*ServiceImpl`, `@CommonTx` / `@CommonTxReadOnly`; ownership checked
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
- `joi-ios/` — the iOS app (`DESIGN.md` → The iOS app), in the reactor only with the root POM's profile `ios`: Swift packages in `Packages/` (`JoiProtocol`: the protocol **generated
  from the JSON Schemas** by `SwiftGenerator` in `joi-protocol/src/test`, checked in, kept current by `SwiftGeneratorTest`: after a schema change run
  `mvn -Pswift process-test-classes -pl joi-protocol`; `JoiClient`: the HTTP API, the pairing, the node WebSocket and the `.joim` / `.joia` readers; `JoiApp`: the models, her body (`Body/`: the node's renderer page in a `WKWebView`, `AvatarDirector`) and the SwiftUI views) and a thin Xcode
  project written by XcodeGen from `project.yml` (`.xcodeproj` and `App/Renderer`, the renderer page Maven builds into the app, are not in Git). `mvn -Pswift,ios verify -pl joi-ios -am` does all of it (needs Xcode and `brew install xcodegen`);
  `swift test` runs a package alone. See `manual/developer/ios-app.md`.
- `joi-extensions/` — the extensions that come with Joi (OSGi bundles: the model providers `anthropic`, `google-genai`, `grok`, built against the platform only), loaded from the
  extensions folder of the data folder (Administration → Extensions); the API of Joi's own extensions is `joi-server/.../extensions` (`JoiExtension`).
- `joi-desktop/` — the desktop app, in the reactor only with the root POM's profile `desktop`: `DesktopLauncher` (JDK only: runs jetty-home with the WAR, the
  web UI on a loopback port with its login, the API on a Unix socket in the data folder where the node of this machine needs no key
  (`POST /oauth/local`, `joi.api.local-socket`), a tray icon that starts the node, one node per home by the lock `node.lock`), and the POM that
  makes the portable folder, the jpackage app with two launchers (`Joi`, `JoiNode`) and the installers (`src/main/installer.xml`); the icons are
  drawn by `tools/MakeIcons.java` from the same shapes as the SVG `joi-server/.../webapp/icons/joi.svg` (the master, also the page's favicon);
  `tools/upgrade_mac_client.sh` builds and installs the app as `~/Applications/Joi.app` (the scheduled task `upgrade-joi-mac-client` runs it). The node side of the socket is `joi-node/.../node/local` (`UnixHttp`, `UnixWebSocket`), `LocalMode`, `NodeInstance`.
  See `manual/developer/desktop.md`. Only macOS arm64 is tried locally; the other platforms are built by `desktop.yml`.
- `.github/workflows/` (`test.yml`, `server.yml`: the WAR, the extension JARs and the multi-arch image on ghcr.io; `desktop.yml`: the desktop app per platform; `manual.yml`), `dependabot.yml`, `tools/version_from_tag.sh` (a `v*` tag is the version of Joi's POMs, not the platform's); see `manual/developer/building.md`.
- `Dockerfile`, `docker-compose.yml`, `docker-entrypoint.sh` — the server's image (the platform, the protocol and the server built into the WAR,
  run by Jetty, with ffmpeg and headless Blender; the data folder `JOI_DATA` is the volume). Not the node. See `manual/user/getting-started/docker-server.md`.
- `marginalia/` — git submodule of Marginalia, pinned at a commit on its `dev` branch; joi-server uses only its
  platform module (`marginalia-platform`). Bump it on purpose, never edit it from here (see `MARGINALIA.md`).
- `joi-server/` — the server WAR. Packages under `com.github.enerccio.joi`. Its own Spring XML
  (`src/main/resources/META-INF/spring`), persistence unit (`src/main/webapp/config/persistence.xml`) and Flyway history
  (`src/main/resources/migration`, the platform's tables included: a platform entity change needs a Joi migration too).
  **The database is frozen (2026-11-23):** never edit `V1__platform.sql` again, not even a comment or whitespace
  (Flyway's checksum would fail on every existing database). **Prefer extended attributes to a schema change:** a new
  field on an `ExtendableEntity` that no SQL query, index, join, uniqueness or foreign key needs is an `@ExtendedAttribute`
  (stored in the entity's extended content), with no migration. Add a column only when the database must see the value
  (filtering, sorting, joining, constraints, a relation) and say why in the commit or the migration's comment. Every real schema change is a new Flyway migration
  (`V2__<what>.sql`, then V3, ...) next to it: a platform entity change too. Write it so it runs on a database that already has
  data (defaults for a new NOT NULL column, `ALTER TABLE`, backfills; SQLite rebuilds a table to drop or retype a column),
  and add a test that migrates a database from the previous version when the change moves data. Never delete a user's
  database to apply a change.
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
  talking, asleep through her first turn, waking up), `NodeSettings` (the home `~/.joi-client`, moved by `--joi-home-dir`, `JOI_HOME_DIR` or `JOI_CLIENT`), `NodeTools` (the tools the user switches on, in one manifest), `Notifications` (the tool `notify`, off until the user allows it), `ClipboardOffers` (the tool `offer_to_clipboard`: off, ask (the user sees the text and decides) or allow), `TaskQuestions` (the tasks of hers that wait for the user's answer), `JoiNodeCli`
  (the node in a terminal), `renderer/` (her body's files unpacked, `BodyFiles`; the renderer page served on localhost and
  driven by server-sent events, `RendererServer`; `RendererPreview` shows cached files without a server). The page is
  `src/main/renderer` (Vite; the server's `joi-model-viewer.js` copied in at build time), built by the `fx` profile
  (`npm install --prefer-offline`: the npm cache first, the network for what is missing). The JavaFX app is `node/fx`, compiled only with `-Pfx`
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
- `tools/upgrade_dev_db.py` — retired with the V1 freeze: it rebuilt a dev database from an edited V1. Don't use it on a database that Flyway can migrate.
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
- **Downloads of big things** (avatars, models, Unity and Looking Glass packages, anything over about 100 MB) need the user's approval first; say the name, source and size.
  Ordinary build dependencies (Maven, npm) download as the build needs them.
- **Looking Glass Go:** Bridge crashes at start when the Go is already connected (hidapi on macOS 26): start Bridge
  first, then plug in the display. The Bridge REST API is on `localhost:33334`.
- **Unity:** use `2022.3.62f3` (the `76f1` install is broken). Batchmode (`Unity -batchmode -quit -projectPath ...`)
  only runs with the sandbox disabled, and takes minutes: run it in the background and wait for the notification.
  The test project is `/Volumes/Annex/unity/JoiLKG`; its setup script is `Assets/Joi/Editor/JoiSetup.cs`
  (`-executeMethod JoiSetup.All`). Looking Glass Unity plugin 3.2.8 (built-in pipeline; 4.x needs Unity 6 + URP).
- **JDK:** `mvn` on the PATH runs on Homebrew's JDK 24, which can't compile for 25. Set
  `JAVA_HOME=~/Library/Java/JavaVirtualMachines/corretto-26.0.2.1/Contents/Home` for Maven.
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
  e.g. `claude-sonnet-5-5`, your reasoning level as the environment reports it (`CLAUDE_EFFORT`, e.g. `medium`; say so if it is
  not reported), the commits checked, notes: what you read, whether you ran the tests, what you fixed) and
  move "Verified up to" to the newest of them. The commits you make during the verification (fixes, docs, stamps, and work
  the user asks for meanwhile that you reviewed) are verified with it: list them in its row and move "Verified up to" to the
  newest of them yourself, without being asked. Never mark a commit as verified without really reviewing it. Never rebase or squash verified history. "Written by"
  is secondary, but follow it every time: **always write one commit behind.** A commit cannot contain its own hash, so the row of
  a commit you made goes into your **next commit**, together with that commit's own change, never into a commit made only to hold
  the row (that one would need a row of its own, for ever). So before each commit, add rows (hash, your model id) to "Written by"
  in `verification.md` for every commit of yours since the last row (`git log` shows them), and commit that file with the rest. The row
  of the commit you are making now waits for the next one. Only commits you made yourself; skip a commit that only touched
  `verification.md`. Do not put the model id into commit messages.
- **Docs are part of the feature:** when a feature or fix is implemented and its tests pass, write the docs before you
  report it done or commit it: user docs in `manual/user`, developer docs in `manual/developer` (and the picture flows
  in `screenshots/flows` when a screen changed). Then run `python3 tools/check_dev_docs.py` and review every page it lists
  for the code you touched: update what changed, add the new code to a page's `covers`, and write a page for a new
  subsystem. Stamp the pages (`--stamp`) in a commit of their own after the commit that holds the code, never before the
  review. A feature without its docs is not finished.
- **Files go through the disk, not memory:** uploads stream into a file (`UploadHandler.toFile`), downloads and what
  the client or a node gets stream from a file (`DownloadHandler.forFile`), exports and imports are written and read
  as streams through temp files on disk. A file already on the disk becomes a `Resource` with `ResourceService.saveFile` (upload file from
  `ResourceService.newUploadFile()`; unused uploads swept with `deleteUnlinked`), never by building the `Resource` by hand. Models, images and archives can be large: never read a whole file into a
  byte array or a string when a stream or a file will do.
