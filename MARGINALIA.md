# What Joi needs from Marginalia (roadmap step 1)

Step 1 of the roadmap ([DESIGN.md](DESIGN.md#roadmap)) is joi-protocol, joi-server and a headless joi-node: text chat,
the harness, the manifest, pairing. joi-server is built on Marginalia's platform layer (layer 1 in Marginalia's
`TODO.VN.md`), not on a copy of it. This file lists what has to happen **in the Marginalia project** before that can
start, and what to check there. Written 2026-11-17 against `../marginalia`, branch `dev` (`22f248c31`); updated 2026-11-18 after the platform refactor (`e92a4800c`) and the platform's tests and test-jar (`a6a1edc37`).

The rule from `TODO.VN.md` holds for Joi too: **Marginalia never learns about Joi.** Anything only Joi needs is built in
Joi on hooks of the platform; a Joi feature that can't be built without editing Marginalia's code shows a missing
extension point, not a reason to put Joi code there.

## How Joi consumes it

- Marginalia as a **git submodule of the Joi repository, at a pinned commit, built from source** (the same as planned
  for the visual novel): plugins are built from the same tree, the build reads git info, nothing is published.
- joi-server depends on the **platform module** only. It does not need the narrative core (layer 2) or the novel
  application (layer 3).

## What step 1 uses from the platform

| Area | Marginalia today | Joi uses it for |
|---|---|---|
| Entity base | `BaseEntity` / `OwnedEntity` / `ExtendableEntity`, `domain.traits`, `ExtendableEntityListener`, `BaseService` / `OwnedService` / `ExtendableService` | assistants, conversations, nodes, events, bodies |
| Users and security | `domain.security`, login, admin | the owner of assistants and nodes |
| Settings, resources, tags | `Setting`, `Resource`, tags | assistant settings, stored files (later bodies) |
| LLM | `AI` / `Protocol` / `Usage` and `AILimitService`, `InferenceService`, `OpenAICompatibleInferenceService`, `GatedInferenceService`, tokenizers | the conversation and heavy model roles, usage per assistant |
| Extension AIs | `ExtensionAI`, `InferenceServices.registerExtensionFactory`, plugins `anthropic` and `google-genai` | Claude and Gemini as providers |
| Tools | `domain.service.function`, `tool`, `structured`, `FunctionRegistry`, `ToolService` | server tools and the tools nodes send in their manifests |
| Templates | `domain.templates` (Handlebars) | system prompt with persona, mood, body description |
| Cleanup, trash, backup, updates, notifications | as they are | the same jobs in Joi |
| Generic UI | `ui/widgets`, `ui/menu`, `ui/shortcuts`, the AI / protocol / admin dialogs, `DialogFocus`, `DialogShortcuts`, `JavascriptLibrary`, `CssStyles` mechanism, localization mechanism | the Vaadin UI of joi-server |
| Tests | the platform's test-jar (`marginalia-platform-<version>-tests.jar`): `PlatformTestBase`, `MockLLMServer`, `InferenceCollector`, `ExpectedLog`, `ComponentTree`, `TestImages`, the CRUD contracts | joi-server's tests (fake model, no network) |

Also used now: the GPU locks (`GpuService`, the admin page's `GpuPanel`, `GpuLockSelect`), which the TTS models take
(see below). Not needed in step 1: the jobs UI, the OSGi plugin system beyond the provider plugins (see open
questions).

## Work in Marginalia

Tracked in Marginalia's `TODO.REFACTOR.md` (base: tag `pre-refactor`, `22f248c31`; ships in 1.9.0). State on `dev`
at `a6a1edc37` (2026-11-18):

- [x] **Leaks fixed in place** (Phase 1): `LayerDependencyTest` maps every package to `platform` or `app` and its allow
  list is empty. Vaadin in the platform is allowed (it carries the generic UI); only naming a story class is a leak.
- [x] **Platform module extracted**: `marginalia/platform`, artifact `marginalia-platform`. **Packages did not change**
  (the inference DTOs stay in `impl.generation.dto`, now in the platform JAR). Own AspectJ weaving and
  `BuildTimeWeaver`; Felix still sees the classes. The WAR, the Docker image and the macOS desktop build work with it.
- [x] **Localization**: one key enum per layer (`L` is the platform's, `M_L` the app's), bundles registered with
  `Localization.registerTexts`.
- [x] **Styles**: the platform JAR has `META-INF/frontend/styles/platform-styles.css` (constants in `CssStyles`); an
  application imports it first and adds its own file.
- [x] **Platform tests as a `test-jar`**: the platform module has its own tests (673) that run on **the platform
  alone**, with a test-only Spring context, `persistence.xml` and migrations of the platform tables in
  `marginalia/platform/src/test/resources` (see "Templates for the wiring" below). `PlatformTestBase` starts
  `classpath:META-INF/spring/test-application-config.xml`; the platform's own file of that name is left out of the
  test-jar, so the tests of an application start the application's wiring from their own file of that name.
  Marginalia's tests depend on the test-jar the same way Joi's will (`<type>test-jar</type>`, scope `test`).
- [x] **The database check names no Marginalia table**: the migration location and the core tables (checked on
  restore and before a migration) are a `DatabaseCheck.Schema` on the `configuration` bean (`databaseSchema`; default
  `DatabaseCheck.Schema.PLATFORM`, Marginalia's is `MarginaliaDatabase.SCHEMA`). The data folder's default name is
  a property too (`defaultFolderName`, default `.marginalia`).
- [x] Docs: `CLAUDE.md` and `manual/developer` (building, testing, ui, database, project structure) describe the
  module, the styles and the tests. The 41 pages whose `verified` stamp predates the move still need their review
  stamp.
- [ ] Packaging: the macOS desktop build is checked; the Linux / Windows desktop and plugin workflows have not run
  yet.

## What joi-server provides itself

Decided (2026-11-18), accepting a little duplication: the platform ships **no** Spring XML, no `persistence.xml` and no Flyway migrations. Each
application wires what it uses. So joi-server has:

- its own `services-config.xml` with the platform beans it needs (inference, AIs, tools, templates, security,
  settings, cleanup...);
- its own `persistence.xml` listing the platform entities it uses next to its own;
- its own Flyway history, **including the platform tables** (the SQL is duplicated from Marginalia's). When a platform
  entity changes, Joi needs its own migration; `hbm2ddl=validate` catches a missing one;
- a user setting entity extending `BaseUserSetting` (set as `userSettingClass`);
- its chat turn (`domain/chat/turn`): the design of Marginalia's story generation (steps, asynchronous events,
  observers) rebuilt for Joi, since that code is in Marginalia's app layer; it runs the platform's `round` and
  `FunctionSet`;
- a session point bean (Marginalia's is `MarginaliaSessionPoint`; the platform `SessionPoint` holds a `BaseWorkspace`);
- the app hooks it needs: `RunScope`, `TrashPolicy`, `SharedResources`, `UsageListener`, `CommandScopeSource`,
  `CommandBarHost`, `ApiTargets` (only those of the features it uses);
- a `Localization` subclass if it has its own enum labels, its key enum and bundles, and a stylesheet that imports
  `platform-styles.css`;
- on the `configuration` bean: its `databaseSchema` (its migration location and core tables) and its
  `defaultFolderName` (for example `.joi`, so it does not share `~/.marginalia`);
- for its tests: a dependency on the platform's test-jar and its own `META-INF/spring/test-application-config.xml`
  in its test resources (its wiring plus `TestContextPostProcessor`), so `PlatformTestBase` and the CRUD contracts
  start joi-server's context.

Templates for the wiring, in Marginalia's `marginalia/platform/src/test/resources`: the platform alone, as small as an
application on it can be.

| File | Copy for |
|---|---|
| `META-INF/spring/test-application-config.xml` | the Spring XML: the platform's beans, data sources, Flyway, localization, session point |
| `platform-test/persistence.xml` | the persistence unit: the 20 platform entities and a minimal `BaseUserSetting` subclass |
| `platform-test-migration/V1__platform.sql` | the first migration: the platform tables at Marginalia's schema V31 (Marginalia's `PlatformSchemaTest` keeps it in step with Marginalia's migrations) |

Done in Joi (2026-11-18): the submodule `marginalia/` (URL `../marginalia`, so it follows Joi's remote:
`github.com/Enerccio/marginalia`) pinned at `a6a1edc37` (now `0b29644d`), and `joi-server` built from these templates:

- `services-config.xml` is the platform's part of the test wiring (43 beans), with `JoiUserSetting`. **No OSGi and no
  runtime instrumentation** at first; since 2026-11-21 OSGi runs in production (`extensions-config.xml`, not in the tests) and
  the Claude, Gemini and Grok providers are `joi-extensions/` (see DESIGN.md → Extensions). Still no instrumentation. No `BuildTimeWeaver` step either: Joi has no `@Extendable` classes.
- `V1__platform.sql` is the platform's test migration copied. Joi's own tables go into the same V1 until the first
  production install (then V2 and on).
- `JoiDatabase.SCHEMA` (`classpath:migration`, the platform's core tables for now), data folder `~/.joi` (`JOI_DATA`).
- The platform's `SessionPoint` as it is (no Joi session state yet).
- `Main` extends `LoginCheckRoute`: the platform's login and first-user dialog, then `JoiWorkspace` (Joi's own
  `BaseWorkspace`, since Marginalia's `DesktopWorkspace`, `WorkspaceComponent` and `AIPart` are in the app layer) with
  a Models part on the platform's `AIService` and `AIDialog`.

Since 2026-11-18 the submodule is pinned at `0b29644d` (GitHub's `dev`): the AI dialog fix below (`0f63cc8c2`), and the
GPU locks' **steps before every job** and step variables (`{{model}}`, `{{lock}}`, with filters). Joi's `V1__platform.sql`
has the platform's `gpu_lock_always_steps` table, and a TTS model takes its lock with `GpuService.model(model)`.

Also in Joi (2026-11-18):

- **Administration part** (`ui/workspace/parts/AdminPart`, administrators only; opened by the button under the
  workspace's tabs, like Marginalia's Admin): a Users tab after Marginalia's admin
  page (the platform's `UserDialog` and `PermissionsDialog`) and the platform's `GpuPanel`. Whether the user is an
  administrator is asked of `AdminGuard` (the database): the session's `User` does not carry the flag after a login.
- **The platform's texts with Joi's name** (`loc/JoiLocalizationOverride`): a `LocalizationOverride` bean of the platform replaces "Marginalia" by
  "Joi" in every text of every language (the GPU's "Also used outside Marginalia") and gives a few texts Joi's wording. It was a subclass of each
  language's bundle (`PlatformTexts`) before the platform had overrides.
- **Two applications on one host:** Joi sets `cookieNamespace` (`joi`) for its saved-login cookies, names its session cookie `JOISESSIONID`, or `JOI_SESSION_COOKIE`
  (`SessionCookie.configure`) and installs its own `BuildInfo` (name, version), which the login footer shows.
- **TTS models and voice actors, copied from Marginalia's audiobook plugin** (an app-layer plugin, not the platform),
  in `com.github.enerccio.joi.tts`: the Chatterbox and Higgs (ComfyUI) clients, `SpeechRequest`, `ServerCheck`,
  `ComfyClient`, the audio helpers Higgs needs (`Ffmpeg`, `WavJoiner`, `BreathTrim`), `Delivery` / `Tags` /
  `HiggsText`, and the models `BackendConfig` (as `TtsModel`), `Actor`, `Role`. The small HTTP helpers of the
  `http-hooks` library (`Http`, `UrlGuard`, `BackendException`...) are copied too, since that library depends on the
  Marginalia app. Left out: the requests before and after a batch (the GPU lock's steps do that), the batch checks,
  the cast, the clip library (folders, versions, cleanup, shifts) and the studio. The copies may diverge from the
  plugin; nothing in Marginalia changes for them.
- **The desktop app's packaging, copied from Marginalia's `desktop` profile** (`marginalia/src/desktop`, its `desktop.yml`): jlink, jpackage, the
  portable folder, the installers and `DesktopLauncher` (jetty-home started in-process, the tray icon) are in `joi-desktop`, changed for Joi: a second
  launcher for the node, the Unix socket of the API (the Jetty module `unixdomain-http`), the node's single-instance lock. Marginalia's
  plugin bundling ("full" installers) is left out. The copy may diverge; nothing in Marginalia changes for it.

## Gaps to check in the platform for Joi

Checked in the code on 2026-11-18 (`e92a4800c`), by reading, not by running. Where one is missing, the fix is a general
improvement of the platform (useful to Marginalia too), not Joi code; Marginalia's refactor plan puts each in its own
change after the split.

- [x] **Images in messages**: all three paths send `LLMAttachment` images: OpenAI-compatible as `image_url`, Anthropic
  as `Base64ImageSource`, Google as `Part.fromBytes`.
- [x] **Cancelling a running generation** (barge-in): all three cut the HTTP call, not only the callbacks.
  OpenAI-compatible interrupts the waiting thread, Anthropic closes the stream, Google cancels the call through an
  interceptor that finds it by a request header.
- [x] **Tools defined at runtime**: `FunctionDefinition.dynamic(name, description, schema, handler)`, bound with
  `FunctionSet.bind`. The handler answers through a `FunctionContinuation` from any thread at any time, so the result
  can come from a node over the WebSocket, after a user approval. The handler checks the arguments itself (the schema
  is not checked against them).
- [~] **Several tool calls in one turn**: handled. All results go back together before the model is asked again. But
  the calls run **one after another**, not in parallel. For Joi: two calls to slow node tools add up. Fine for step 1,
  a candidate improvement later.
- [~] **Thinking off**: per AI, not per call. `AI.enabledReasoning` + `reasoningEffort` drive Claude (adaptive or a
  budget) and Gemini (`thinkingLevel`). For llama.cpp, the AI's `additionalParameters` are merged into the request
  body, so `chat_template_kwargs: {"enable_thinking": false}` works per AI. Joi's model roles are separate AI entries
  anyway, so "conversation without thinking" and "heavy with thinking" are two AIs, which needs no change. Per call
  is missing.
- [~] **Tool loop with approvals**: the inference services already run the loop (call, result, ask again, up to
  `MAX_FUNCTION_ROUNDS`, cancellable). An approval fits inside a handler, which simply answers later. What is in the
  app layer is the agent runner and `AgentApprovalService` (approval UI, policies). Joi probably needs no lifted loop,
  only its own approval flow in the handlers.
- [~] **Usage per assistant and per role**: `UsageContext.open(BaseEntity)` takes any entity, so an assistant works,
  with `UsageListener` beans for counting. No role or kind next to the id yet (the parameter is still named `book`).
  Since roles are separate AIs, usage per AI may already answer "per role".

- [ ] **`Setting` ignores `@DiscriminatorValue`** (found 2026-11-18): its constructor writes
  `getClass().getSimpleName()` into `DTYPE`, so a subclass whose `@DiscriminatorValue` differs from its simple name is
  saved but can't be read back ("Unknown discriminator value"). Marginalia's `UserSetting` matches by chance; the
  platform's `TestUserSetting` (`"UserSetting"`) has the mismatch, which no platform test hits because none reads a
  saved user setting back in a new transaction. Joi works around it: `JoiUserSetting` declares `"JoiUserSetting"`. Fix in
  the platform: take the value from the annotation when there is one.

- [x] **`AIDialog` hook for an application's fields, image flag not named after lore** (fixed in `0f63cc8c2`): the
  flag is `AI.isMultimodal()` / `isImagesInSystem()` with neutral texts (the stored attribute names stay
  `sendLoreImages` / `loreImagesInSystem`), and `AIDialog.createApplicationFields(ai)` / `applyApplicationFields(ai)`
  let a subclass add fields that are saved in the dialog's one save. `JoiAIDialog` uses them for the `TextLLMType`
  multi-select (in the AI's attributes under `"joi"`).

## Open questions

- ~~**Provider plugins without the whole OSGi system?**~~ — the platform keeps OSGi and Joi loads the same kind of bundle: the
  providers (`anthropic`, `google-genai`, `grok`) are copied into `joi-extensions/` and built against the platform only.
- **`@Extendable` and the plugin API in Joi:** does joi-server want plugins at all in step 1? If not, the weaving still
  has to work for the platform's own `@Extendable` classes.
- ~~**Localization**~~: answered by the refactor, one key enum per layer (Joi adds its own enum and bundles).
