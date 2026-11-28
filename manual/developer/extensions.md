---
label: Extensions
icon: plug
order: 743
covers:
  - joi-server/src/main/java/com/github/enerccio/joi/extensions
  - joi-server/src/main/java/com/github/enerccio/joi/domain/extensions
  - joi-server/src/main/java/com/github/enerccio/joi/ui/extensions
  - joi-server/src/main/java/com/github/enerccio/joi/ui/workspace/ExtensionTab.java
  - joi-server/src/main/java/com/github/enerccio/joi/ui/tools/ExtensionSettingsPanel.java
  - joi-server/src/main/java/com/github/enerccio/joi/domain/tools/ApprovableTool.java
  - joi-server/src/main/java/com/github/enerccio/joi/domain/templates/ExtensionTaskTemplateData.java
  - joi-server/src/main/resources/META-INF/spring/extensions-config.xml
  - joi-extensions
verified: ad275e2
---

# Extensions

The user side is in [Extensions](../user/extensions/index.md) and [Managing extensions](../user/administration/extensions.md). The mechanism is the
platform's, as in Marginalia: OSGi (Felix) bundles from the `extensions` folder of the data folder, loaded at the start and at runtime. Joi wires
it and adds an API of its own.

## What the platform does

`OsgiServiceImpl` starts the framework when the root context is refreshed, installs every JAR of the folder, verifies it (`ExtensionVerifier`,
a report `<jar>.valid` or `.invalid` next to it), starts the bundle and looks for the `MarginaliaExtension` it registered as an OSGi service. It
gives each extension its own log (`createLogger`), a `.STOPPED` flag, a SHA-256 of the JAR, and calls the beans that implement
`ExtensionRegistrations` to remove what the extension registered when it is unloaded. The bundle sees every class of the server (the framework is
started with boot delegation `*` and the web application's class loader as parent), so it imports nothing and embeds only the libraries it alone
needs.

Joi's part: `extensions-config.xml` (the `OsgiServiceImpl` bean, in the profile `!test`: the pom sets `spring.profiles.active=test` for the tests,
which have no framework), `ExtensionServiceImpl` (in `services-config.xml`) and the tab `ui/extensions/ExtensionsPanel` (Administration →
Extensions, after Marginalia's).

## The extensions that come with Joi

`joi-extensions/` holds the three model providers of Marginalia's plugins (`anthropic`, `google-genai`, `grok`), copied and built against
`marginalia-platform` only (their `provided` dependency), not against Marginalia's application: they use nothing above the platform. Each is a
Maven module of packaging `bundle` (`maven-bundle-plugin`: the activator, `Embed-Dependency` for the provider's SDK). The copies may drift from the
plugins; nothing in Marginalia changes for them. `mvn package -pl joi-extensions/anthropic` builds one (and runs its tests); the Docker build makes
all of them (`docker compose -f joi-extensions/docker-compose.yml run --rm extensions`, see [Building](building.md)).

A provider is a `MarginaliaExtension` that registers an `ExtensionAIFactory` with `InferenceServices.registerExtensionFactory`: the AI dialog then
offers the type *Extension* (`ExtensionAI`), see the platform's `extensions` package.

The fourth, `marginalia-books`, is an extension of Joi (see [below](#marginalias-books)). It compiles against the server: `joi-server` attaches its
classes as `joi-server-<version>-classes.jar` (`attachClasses` of the WAR plugin), which `mvn install -pl joi-server` puts into the local
repository, and the module takes it as a `provided` dependency (classifier `classes`). So build it after installing the server:
`mvn install -DskipTests -pl joi-server && mvn package -pl joi-extensions/marginalia-books`. The Docker build and `server.yml` do that order.

## The API of Joi's extensions

`com.github.enerccio.joi.extensions` (in `joi-server`; the bundle sees it by boot delegation, so there is no API JAR yet):

| Type | What it is |
|---|---|
| `JoiExtension` | extends `MarginaliaExtension`; the bundle registers an instance as that service. `onJoiLoad(bundle, context)` registers what it adds, `onJoiUnload` lets go of what it holds |
| `JoiExtensionContext` | `owner()`, `log()`, `bean(Class)` (any bean of the server), `offerTools`, `addWorkspacePart`, `onTurnEvent`, `declareTool`, `offerTask`, `declareSettings`, `settings(Joi)` |
| `ToolProvider` | `tools(Joi)` returns `FunctionDefinition`s (`FunctionDefinition.dynamic`), asked at the start of every turn |
| `ExtensionPart` | a tab of the workspace: `key`, `caption`, `create`, `shown` |
| `ExtensionTool` | a tool under the chain of permissions: `function` (`[a-z][a-z0-9_]*`, the key of its settings), `label`, `description`, `fallback` (its built-in default). An `ApprovableTool`, as `ServerTool` is |
| `ExtensionTask` | a function that runs as a task: `tool()`, `description()`, `parameters()` (the JSON Schema of her call), `offered(joi)`, `check(arguments)`, `question` / `title`, and `prepare(joi, arguments)`, which gives the `Work`: the guidance for the template, the functions of the stronger model, how many rounds of calls it has |
| `ExtensionSettings`, `ExtensionSetting` | the settings an extension asks for: a caption, a description, fields (`text` or `secret`, by key) |
| `ExtensionConfig` | the settings in force for an assistant: `value(key)`, `has(key)`, `from()` (whose place each value came from); secrets in the clear, never in `toString` |

`domain/extensions/JoiExtensions` keeps what was registered, by owner (the symbolic name of the bundle): it is the `ExtensionRegistrations` bean, so
`unregisterAll(owner)` removes the providers, the tabs and the turn listeners when the extension is unloaded, stopped, replaced or fails in
`onJoiLoad`. It keeps one `COLLECT_TOOLS` listener, which adds the functions of every provider (one that throws is left out, a name already taken
is not offered). `JoiExtensionLoader` is the `OsgiService.ExtensionListener` that calls `onJoiLoad` / `onJoiUnload` for an extension that is a
`JoiExtension`, with a context bound to the owner. `JoiWorkspace.create` adds the tabs of the loaded extensions after the user's settings
(`ExtensionTab`, key `ext:<owner>:<key>`); one loaded later shows in the next workspace. The services check their user as usual: call them in
a request scope (a turn, a page).

### Tools under the chain of permissions

`ApprovableTool` (`domain/tools`) is what the chain decides on: `function()`, `fallback()`, `description()`. `ServerTool` is one, `ExtensionTool`
is one; `ToolPermissionService` (`chain`, `effective`, `config`), `ToolMetadataService` (`find` / `edit`, also by a plain key) and
`WebTaskService` (`submit`, `autoApprove`, `autoApproved`, `tool(function)`) take it. Its settings are a `ToolMetadata` under its function name,
as a server tool's, so the permission rows (`ToolPermissionsPanel`: the server's tools, then `JoiExtensions.declaredTools()`), the standing
approval ("approve all", `AutoApprovedDialog`, the API's `auto-approved`) and the task's `TaskInfo.description` (kept on the task as
`toolDescription`, so it is there after the extension is gone) work for both. `declareTool` refuses a name a server tool or another extension has.

In `JoiExtensions.collect` a function of a `ToolProvider` whose name is declared is offered only when `effective` is `ALLOW`: a function of her
turn cannot wait for the user, so Ask leaves it out as Deny does. A function that should ask is a task.

### Functions that run as tasks

`offerTask` declares the task's tool and keeps the task. `ExtensionTasks` (a `COLLECT_TOOLS` listener, as `WebTools`) offers each one that is not
denied and is `offered(joi)`; the description gets the sentence that it runs in the background. Her call is checked (`check`), a call with the same
arguments that is still on its way is answered as such, else `WebTaskService.submit(joi, tool, question, null, title, argumentsJson, null)` makes
the `JoiTask` (`arguments`, `toolDescription`: extended attributes, no migration): `QUEUED` when allowed, `WAITING` when it asks, approved at once
by a standing approval. All of the task's life is the web tasks' (approve, deny, cancel, the transcript, the notes, one event when it ends).

`WebTaskServiceImpl.execute` runs a task whose tool is not a `ServerTool` through `ExtensionTaskRunner`: the extension's `prepare` on the task's
thread (the user's request scope, so `settings(joi)` works), the template `JoiPromptField.EXTENSION_TASK_PROMPT` (`ExtensionTaskTemplateData`:
`today`, `question`, `tool`, `guidance`; a field of `Joi`, `JoiUserSetting` and `JoiDefaults.extensionTaskPrompt`, in both Templates tabs), and the
loop `TaskLoop` (`domain/chat`, shared with `WebResearch`: rounds of the model and its calls, the last round without functions) with
`work.maxCalls() + 1` rounds. Its answer is the result. A task whose extension is not loaded when it runs fails with "not loaded".

### Settings

`declareSettings` keeps the extension's `ExtensionSettings`; `ExtensionSettingsService` (`domain/extensions`) keeps the values as a `ToolMetadata`
under `ext:<symbolic name>` in each place (APP, USER, JOI): the texts in its `config`, the secrets protected in its `secrets`
(`ToolMetadataService.setSecret`), only declared keys. `resolve(joi, owner)` takes each field from the first place that sets it, the assistant's,
the user's, the administrator's (a secret that cannot be revealed, of another installation's key, counts as not set). `save` with a secret of null
keeps it, blank removes it; `clear` empties the place. The checks are a tool's: administrators write APP, a user their USER, an owner her JOI.
`ExtensionSettingsPanel` (in `ToolsPanel`, Administration → Tools and User Settings → Tools) shows a section per extension that was loaded when the
page was made. No page writes the JOI place yet.

An extension that needs more (tabs of the assistant's dialog, prompt sections) is not served yet: those extension points are added when
one is needed, and are additions to the context.

## Marginalia's books

`joi-extensions/marginalia-books` (`com.github.enerccio.joi.extensions.marginalia`, symbolic name `marginalia-books`; user side in
[Marginalia's books](../user/extensions/marginalia-books.md)). It embeds nothing: the JDK's `HttpClient`, the server's Gson and Jackson.

- `MarginaliaBooksExtension.onJoiLoad`: the settings (`url`, the secret `apiKey`), the tools `marginalia_books` and `marginalia_read_part`
  (both Allow) with a `ToolProvider` that offers them only when both settings are set, and the task `ReadBookTask` (`marginalia_read_book`, Ask).
- `ReadPart`: `marginalia_read_part(book, part?, offset?, length?)` in her turn: without a part `list-parts`, with one `get-part`; an offset or a
  length takes the paged form, which names the branch too (blank: the active one); the length is clamped to 6 000 (Marginalia's page). 90 s at most.
- `MarginaliaClient`: Marginalia's API (`<url>/api/v1`, `/api/v1` added when missing, http or https only), the key in the `Authorization` header
  only. `books()` (`GET books`, without `all`: Marginalia leaves out the books marked *Only in the extended API*), `books(true)` (`?all=true`);
  the folder is `folderPath`, else `folderName`, else `folder` (older Marginalia); `book(nameOrUuid)` (uuid, else the name ignoring case, else
  the one name that contains it, in the default list, then in all of them; otherwise an error with the names);
  `readOnlyCommands()` (`GET commands`, only `readOnly`; the arguments' schema from `openapi.json` with the key, `paths["/books/{book}/commands/<name>"]
  .post.requestBody...properties.arguments`, else made from the forms, a `oneOf` for several); `run(book, command, arguments)` (`wait` 30 s, a `202` run followed every
  second with `GET runs/<id>`, stopped with `DELETE` when cancelled or after 5 minutes; the result cut at 40 000 characters). Errors are
  `MarginaliaException` with what Marginalia said (401: a new key, 403: the scope READ).
- `ReadBookTask.prepare`: the book, the read-only commands as functions named after them (`[A-Za-z0-9_-]`, unique, at most 64; a command with
  several forms (`oneOf`, as `get-part` has) gets one object with the properties of all its forms, required only what every form requires, and
  its forms named in the description: with "any arguments" a model guessed the names and wasted calls), each running on that book, and the guidance (the book, the list of commands, "find your way first").
  16 rounds of calls.

Writing (`WRITE`) is not built: the plugin never runs a command that is not `readOnly`.

## Tests

`ExtensionPointsTest` loads a proxy extension through the loader for the new points: settings along the chain with a protected secret (kept,
removed, refused for an unknown key or a user writing the administrator's, kept over an unload), the Tools pages' panels, a declared function
offered only on Allow (the user's Deny and Ask, the administrator's ceiling, a name taken), and a task end to end with the fake model (waits,
approved, the stronger model gets the template, the guidance and the extension's function, she is told once without the chapter; bad arguments,
denied, its extension gone). `marginalia-books` has its own tests against `FakeMarginalia` (a `com.sun.net.httpserver` server on localhost):
`MarginaliaClientTest` and `MarginaliaBooksExtensionTest` (a proxy of `JoiExtensionContext`), no Spring.

`JoiExtensionsTest` runs an extension (a proxy of `JoiExtension` with a proxy bundle) through the loader: a function she calls, a tab, a turn
listener, removal on unregister, an extension that fails to load, one that breaks another's functions. The framework itself is not started in the
tests; the three providers have their own tests in their modules (fake servers). Loading a JAR into a running server is checked by hand: see the
Docker guide.
