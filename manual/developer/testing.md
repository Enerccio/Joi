---
label: Testing
icon: beaker
order: 690
covers:
  - joi-server/src/test
  - joi-node/src/test
---

# Testing

Tests boot the real Spring configuration on SQLite with a fake model server: no network and no API key. Run the classes you touched, not the whole
suite.

```bash
mvn test -pl joi-server -Dtest=ChatTurnTest
mvn test -pl joi-node
```

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
| `api` | the HTTP API (`JoiApiTest`, `BodyApiTest`), every answer checked against the schemas |
| `domain` | services, the turn, the web tools (against fake servers: `FakeWeb`), events, conversion (`ModelConversionTest` and `AnimationConversionTest` need Blender) |
| `domain/node` | `NodeServiceTest` drives the node WebSocket through a fake transport, checking every frame against the schemas |
| `ui` | dialogs and parts, driven without a browser |

`SchemaContractTest` in `joi-protocol` checks the records, the enums and the AsyncAPI document against each other.

The platform's own tests belong to Marginalia: do not run them from here.
