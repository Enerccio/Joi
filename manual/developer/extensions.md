---
label: Extensions
icon: plug
order: 743
covers:
  - joi-server/src/main/java/com/github/enerccio/joi/extensions
  - joi-server/src/main/java/com/github/enerccio/joi/domain/extensions
  - joi-server/src/main/java/com/github/enerccio/joi/ui/extensions
  - joi-server/src/main/java/com/github/enerccio/joi/ui/workspace/ExtensionTab.java
  - joi-server/src/main/resources/META-INF/spring/extensions-config.xml
  - joi-extensions
verified: 7c29655
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

## The API of Joi's extensions

`com.github.enerccio.joi.extensions` (in `joi-server`; the bundle sees it by boot delegation, so there is no API JAR yet):

| Type | What it is |
|---|---|
| `JoiExtension` | extends `MarginaliaExtension`; the bundle registers an instance as that service. `onJoiLoad(bundle, context)` registers what it adds, `onJoiUnload` lets go of what it holds |
| `JoiExtensionContext` | `owner()`, `log()`, `bean(Class)` (any bean of the server), `offerTools`, `addWorkspacePart`, `onTurnEvent` |
| `ToolProvider` | `tools(Joi)` returns `FunctionDefinition`s (`FunctionDefinition.dynamic`), asked at the start of every turn |
| `ExtensionPart` | a tab of the workspace: `key`, `caption`, `create`, `shown` |

`domain/extensions/JoiExtensions` keeps what was registered, by owner (the symbolic name of the bundle): it is the `ExtensionRegistrations` bean, so
`unregisterAll(owner)` removes the providers, the tabs and the turn listeners when the extension is unloaded, stopped, replaced or fails in
`onJoiLoad`. It keeps one `COLLECT_TOOLS` listener, which adds the functions of every provider (one that throws is left out, a name already taken
is not offered). `JoiExtensionLoader` is the `OsgiService.ExtensionListener` that calls `onJoiLoad` / `onJoiUnload` for an extension that is a
`JoiExtension`, with a context bound to the owner. `JoiWorkspace.create` adds the tabs of the loaded extensions after the user's settings
(`ExtensionTab`, key `ext:<owner>:<key>`); one loaded later shows in the next workspace. The services check their user as usual: call them in
a request scope (a turn, a page).

An extension that needs more (tabs of the assistant's dialog, settings, prompt sections) is not served yet: those extension points are added when
one is needed, and are additions to the context.

## Tests

`JoiExtensionsTest` runs an extension (a proxy of `JoiExtension` with a proxy bundle) through the loader: a function she calls, a tab, a turn
listener, removal on unregister, an extension that fails to load, one that breaks another's functions. The framework itself is not started in the
tests; the three providers have their own tests in their modules (fake servers). Loading a JAR into a running server is checked by hand: see the
Docker guide.
