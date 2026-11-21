---
label: Project structure
icon: file-directory
order: 770
covers:
  - joi-server/src/main/java/com/github/enerccio/joi/domain
  - joi-server/src/main/resources/META-INF/spring
verified: ed86d8f
---

# Project structure

## Modules

See the [Developer guide](index.md). The server's Java is under `com.github.enerccio.joi`.

## Packages of the server

| Package | What is in it |
|---|---|
| `api` | `JoiApiServlet` and `JoiApi` (the HTTP API), `NodeEndpoint` (the node WebSocket), `ApiConfiguration`, `ApiAccess` |
| `defaults` | built-in texts and values: `JoiDefaults` (every prompt), `PersonalityDefault`, `MoodDefault` |
| `domain.model.impl` | the entities (below) |
| `domain.repository`, `.impl` | repository interfaces and their `Jpa*Repository` classes |
| `domain.service`, `.impl` | service interfaces and `*ServiceImpl` |
| `domain.chat`, `.turn` | the chat service, the steps and events of a turn, her functions (`MemoryTools`, `BodyTools`, `AnimationTools`, `WebTools`) |
| `domain.persona` | `JoiPromptField`, personalities, moods and the attributes of an assistant |
| `domain.templates` | the data of every prompt template (`*TemplateData`) |
| `domain.bodies`, `.animations` | models, capabilities, appearance, outfits, spring bones, animation steps |
| `domain.tools`, `domain.web` | server tools and their permissions; web search and page reading |
| `domain.events`, `domain.node` | queued events and their merge; a node's session |
| `extensions`, `domain.extensions` | the API of Joi's extensions and what they registered (see [Extensions](extensions.md)) |
| `domain.jobs` | the names of the work logged as jobs (see [Jobs](jobs.md)) |
| `domain.summary` | the pure logic of the summaries: her days, what is due, the queue (see [Summaries](summaries.md)) |
| `tts` | speech backends (Chatterbox, Higgs in ComfyUI), TTS models, voice actors, audio |
| `ui` | the Vaadin UI (see [The UI](ui.md)) |
| `loc` | the texts: `J_L` and `JoiLocalizationBundleEN` |

## The entities

All are owned by the user, and each belongs to an assistant where it makes sense. They follow the platform's `BaseEntity`,
`OwnedEntity` and `ExtendableEntity`: an `id`, a stable `uuid`, soft deletes, and `@ExtendedAttribute` for fields nobody queries in SQL.

| Entity | What it is |
|---|---|
| `Joi` | an assistant; her settings (`PersonaAttributes`), wake word and today's mood |
| `ChatMessage` | one exchange: an input of type User, Event or Log, and her response with reasoning, tool calls, model, tokens and timings |
| `MessagePrompt` | the prompt sent for a message, deflated |
| `Summary` | a summary of a time: a day, a week, a month or a year, or a part of a day (see [Summaries](summaries.md)) |
| `Memory` | a fact with importance, keywords, approval and the message it came from |
| `JoiTask`, `JoiTaskMessage` | a task she started and its transcript |
| `QueuedEvent` | an event that waits for a connected node |
| `Model`, `Body` | a 3D model and a body made of models |
| `Animation`, `AnimationPack` | a clip and an animation (steps of clips) |
| `AccessToken` | a token or a device of the API |
| `ToolMetadata` | the settings of one tool in one place of the chain |

## Wiring

Spring XML in `src/main/resources/META-INF/spring`: `application-config.xml`, `container-config.xml` (the configuration, the API),
`datasources-config.xml` and `services-config.xml`. A new service or repository needs its bean there. Transactions are `@CommonTx` and
`@CommonTxReadOnly`. Ownership is checked through `findForUser`, never by raw ids.

## Style

Follow Marginalia (see its `CLAUDE.md` and `manual/developer`): 4 spaces, no Lombok, records for small result types, javadoc that explains
intent. The UI strings are in an enum plus bundles, styles are CSS classes, scripts are in one JavaScript library class.
