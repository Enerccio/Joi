---
label: Testing
icon: beaker
order: 690
covers:
  - joi-server/src/test
  - joi-node/src/test
verified: 446f8b5
---

# Testing

Tests boot the real Spring configuration on SQLite with a fake model server: no network and no API key. Run the classes you touched, not the whole
suite.

The build sets the platform's `StaleSaves` to fail in tests: a save of an entity from a copy read before the row was saved again
throws there (in production it only logs). Change such a copy through its service's `update(entity, change)`.

```bash
mvn test -pl joi-server -Dtest=ChatTurnTest
mvn test -pl joi-node
mvn -Pfx test -pl joi-node -Dtest=LineOutputTest
```

The tests in `joi-node`'s `fx` package compile and run only with `-Pfx`, as the JavaFX app they test (the pom excludes them
otherwise, `testExcludes`). CI runs `mvn test` without the profile, so they do not run there.

The test classes share one application (the same database and the same in-memory state), so a test that looks at everything (the administrator's list of what the
assistants do, a count of all animations) can fail depending on which classes ran before it: on CI the order is the file system's, here it is another. Run the whole suite
in alphabetical order to find such tests (`mvn test -pl joi-server -Dsurefire.runOrder=alphabetical`), and let a test look only at what its own user created.

## The base class

Tests extend the platform's `PlatformTestBase` (from the platform's test-jar, so install it after each submodule bump) and start
`src/test/resources/META-INF/spring/test-application-config.xml`. It gives `login()`, `loginAdmin()`, `createUser()`, `loginAs(user)`, `createAI()`, and
`llm`, a `MockLLMScenario`: queue the replies the fake model sends with `MockLLMResponse` (text, reasoning, tool calls) and read what it was sent with
`MockLLMRequest`.

A test of a chat turn creates an assistant (`joiService.create`), gives her a chat model from `createAI()`, queues replies and calls `chatService.chat`. Listeners
registered on the turn must be unregistered after the test: the chat service is shared.

## What is tested where

| Folder | What |
|---|---|
| `crud` | every entity's repository and ownership |
| `api` | the HTTP API (`JoiApiTest`, `BodyApiTest`), every answer checked against the schemas; the pairing offers are in `JoiApiTest` |
| `ui/access` | `PairPhoneDialogTest`: the link of the QR code, the check that approves the device of the offer |
| `domain` | services, the turn (`ChatTurnTest`, also a turn that fails after some rounds and a round skipped after a gesture), the web tools (against fake servers: `FakeWeb`), events, conversion (`ModelConversionTest` and `AnimationConversionTest` need Blender), the speech call's one more try (`service/impl/TtsLeaseRetryTest`: which failures of a lock step are tried again) |
| `domain/node` | `NodeServiceTest` drives the node WebSocket through a fake transport, checking every frame against the schemas (also the `mood` frame after the manifest, a mood change by the user and `set_mood`) |
| `ui/jobs` | `AssistantsRunsTest`: what the assistants do now (the second list of `/jobs`); the job log is checked in `ChatTurnTest` and `SummarizerTest` with `Jobs.addSink` (never `Jobs.reset()`) |
| `domain/workspace` | `AssistantWorkspaceTest`: a workspace per assistant, the file functions, the administrator's limits, ownership, the admin panel; the task writing a file is in `WebTasksTest`, the clipboard offer through a fake node in `NodeServiceTest` |
| `domain/extensions` | `JoiExtensionsTest`: an extension of Joi (a proxy) through the loader; no OSGi framework in tests (profile `test`) |
| `domain/summary` | the pure logic of the summaries (`SummaryDaysTest`, `SummaryPlanTest`, `SummaryQueueTest`): no database, no clock. `SummarizerTest` in `domain/service` runs the whole thing with the fake model (also: a summary is a job); the minute tick is off in tests (`joi.summary.tick-seconds` 0), so a test calls `check(joi)` |
| `ui` | dialogs and parts, driven without a browser |
| images | `ChatImagesTest` (pure: formats, EXIF, scaling, limits), `ChatImageApiTest` (the upload, a multimodal model gets the same data URLs in later prompts, the describer's note once, `no_vision`, limits, deleting), the say with an image in `NodeServiceTest`, the compose row in `JoiChatsPartTest`; the node's `NodeSocketTest`, `ServerClientTasksTest` and `UnixSocketTest`; the app's `ImagePreparerTests` and `ChatModelTests` |

`joi-node`'s tests: `renderer/RendererServerTest` runs the renderer's server against a fake page (the play message with its face, her eyes and mood, her voice for the mouth), `fx/LineOutputTest` runs her voice's output line on fake lines (a healthy one, one on a device that is gone, a stop while it is stuck), `local/UnixSocketTest` runs `UnixHttp` and `UnixWebSocket` against a server of its own on a Unix socket, `ServerClientLocalTest` the local sign-in against a fake one,
`LocalModeTest` and `NodeInstanceTest` (with a second JVM that holds the lock). `joi-desktop`'s `DesktopLauncherTest` (the launcher's options, places and Jetty base) runs with
`mvn test -Pfx,desktop -pl joi-desktop`, which needs `joi-server` (the WAR) and `joi-node` installed in `~/.m2` (it is part of `package` in the reactor, and `-DskipTests` there skips it).

`SchemaContractTest` in `joi-protocol` checks the records, the enums and the AsyncAPI document against each other.

The platform's own tests belong to Marginalia: do not run them from here.
