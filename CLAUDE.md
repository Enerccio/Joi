# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Joi: a self-hosted personal AI assistant. A Java **server** (the brain: LLM tool loop, memory, moods, bodies, events,
a Vaadin UI) and Java **nodes** (one per machine: microphone, wake word, speech-to-text, harness, OS tools, avatar
cache), with an avatar shown on a Looking Glass Go. **`DESIGN.md` is the design and the source of truth**: read it
before working on anything, and when the user shares a new idea or decision, write it into the matching section of
`DESIGN.md` (and into Roadmap / Open decisions when it changes them).

**Status:** design, spikes, and the first module: `joi-server` boots on Marginalia's platform (login, then a
workspace with a Bodies part (3D models: `Model`, uploaded as a resource (a model file, a zip or a .unitypackage), converted to VRM with Blender (`domain/bodies`, `ModelConversionService`), what it can do read from the VRM (`ModelCapabilities`: expressions, toggles, bones; named and tried in the dialog), the bodies of an assistant (`Body`: her models, appearance, the active one; her dialog's Bodies tab `ui/dialogs/joi/JoiBodiesPart`, `ui/dialogs/BodyDialog`, `BodyService` also writes the prompt's `{{#body}}`; how she looks is the model's: snapshots and close-ups of the toggles from the viewer, the user's images and notes, the description, the toggle names and a sentence per toggle state proposed by a multimodal model, `AppearanceService`, `ui/bodies/AppearancePanel`, the default state and the user's groups of toggles with named states, `ToggleGroup`, `ui/bodies/ToggleGroupsPanel`; the parts described apart, the only ones she can switch, are `DescribedPart`; put together for a state by `Appearance`; the body's state of its parts is `BodyState`, switched in the body dialog, an event for her), the spring bones guessed from the bones' names and written into the converted VRM (`SpringBones`, `SpringChain`, `ui/bodies/SpringBonesPanel`), the model and body dialogs split between the fields and a three.js preview (`ui/widgets/EditorSplit`) in `frontend/joi-model-viewer.js`; `ui/workspace/parts/BodiesPart`, `ui/dialogs/ModelDialog`, `ui/bodies/ModelViewer`), a Models part: text models (the platform's AIs), TTS models and voice actors; User Settings: the
assistant's name, the user's name, personality, model roles, voice, the prompts and the personalities and moods; and, for administrators, an
Administration page behind a button under the tabs, as in Marginalia: users and GPU locks). The data model of the assistant is built (no UI or inference yet): `Joi`, `ChatMessage`, `MessagePrompt`, `Summary`,
`Memory`, `JoiTask`, `JoiTaskMessage`, `QueuedEvent` (see `DESIGN.md` → Several assistants), with the Jois tab and the Joi dialog (its Chats tab shows the transcript, lazily; `ui/workspace/parts/JoisPart`,
`ui/dialogs/JoiDialog` and its parts in `ui/dialogs/joi`). The HTTP API is built (`joi-protocol` 1.0: list, info,
chat with text and speech, whole or streamed; tokens, OAuth device grant, no auth for development; see `DESIGN.md` →
The node protocol): `api/` (`JoiApiServlet`, `JoiApi`), `domain/chat/ChatService`, `ui/access`. `spikes/` holds throwaway experiments for the avatar renderer.

## Build

Maven reactor at the root (`joi-parent`): `marginalia/marginalia/platform` (from the submodule), `joi-protocol` and
`joi-server`.
JDK 25+, Maven 3.9. Clone with `git clone --recurse-submodules` (or `git submodule update --init`).

```bash
mvn install -DskipTests -pl marginalia/marginalia/platform   # the platform and its test-jar into ~/.m2, after each submodule bump
mvn -N install && mvn install -DskipTests -pl joi-protocol   # the parent POM and the protocol into ~/.m2 (for -pl joi-server alone)
mvn test -pl joi-server -Dtest=JoiContextTest                 # joi-server's tests (the classes you touched)
mvn jetty:run -pl joi-server                                  # dev mode on http://localhost:8080, data in ~/.joi (JOI_DATA)
mvn package -pl joi-server -Pproduction                       # the WAR with the production frontend bundle
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

- `joi-protocol/` — the public API: JSON Schemas and `openapi.yaml` in `src/main/resources/joi-protocol` (the source of
  truth; `SchemaContractTest` checks the records against them), records in `com.github.enerccio.joi.protocol`, Gson
  only. A change of the API changes the schema, the record and `DESIGN.md` → The node protocol together.
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
- **Files go through the disk, not memory:** uploads stream into a file (`UploadHandler.toFile`), downloads and what
  the client or a node gets stream from a file (`DownloadHandler.forFile`), exports and imports are written and read
  as streams through temp files on disk. Models, images and archives can be large: never read a whole file into a
  byte array or a string when a stream or a file will do.
