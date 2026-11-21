---
label: Server tools
icon: tools
order: 740
covers:
  - joi-server/src/main/java/com/github/enerccio/joi/domain/tools
  - joi-server/src/main/java/com/github/enerccio/joi/domain/web
verified: b6f812e
---

# Server tools

A server tool is a function the server itself offers her. `ServerTool` lists them: `web_search` and `fetch_page`, each with a built-in default
permission (`ASK`) and a description a client can show.

## The permission chain

`ToolPermission` is `ALLOW`, `ASK` or `DENY`. It is resolved along a chain: the assistant's own, then the user's, then the administrator's, then the
built-in default. The first one set wins. The administrator's value is also a **ceiling**: what is set below it can only be as strict or stricter
(`DENY` over `ASK` over `ALLOW`). The permission is read when she calls the tool, not when the turn starts. `ToolPermissionService.chain` answers the
three values, the effective one and where it came from, so the UI can say "Ask (from the administrator)".

## Where it is stored

`ToolMetadata` (`tool_metadata`, an `ExtendableEntity`) is the settings of one tool in one place of the chain. The real columns only find it: `scope`
(`ToolScope`: `JOI`, `USER`, `APP`), `joi` (for `JOI`) and `tool_key`. Everything else is dynamic JSON, so a new option needs no migration:

| Attribute | Meaning |
|---|---|
| `permission` | absent means inherit |
| `config` | the tool's options, a JSON object |
| `secrets` | keys and tokens by name, protected with `InferenceServices.protectSecret` (AES-GCM with `secret.key`) and read with `revealSecret`. The service does it, never the caller. A secret is never printed, logged, returned by the API or filled into a field again |
| `autoApprove` | the standing approval `{name, description}` (assistant scope only) |

An administrator saves the administrator's only (`AdminGuard.requireAdmin`), a user their own, and a Joi's only of an assistant of that user.

## Writing a tool

1. Add it to `ServerTool` with its key, default permission and description.
2. Offer the function in a turn: a listener of `COLLECT_TOOLS` that adds a `FunctionDefinition` when `ToolPermissionService` says it is not denied.
   Refuse a forced call of a denied tool.
3. A tool that takes long makes a `JoiTask` and answers at once. The task runs after the reply (see [A chat turn](chat-turn.md)).
4. Put its options in `config`, its keys in `secrets`; add a panel to `ui/tools` beside `ToolPermissionsPanel`.
5. Write a note in the conversation for every approval, denial, cancel or standing approval (`ChatService.log`).

## Tasks, approvals and standing approvals

With `ASK` a task is `WAITING` until the user answers in the Tasks tab (Approve, Approve all of this tool, Deny with a reason, Cancel) or through the HTTP
API. `DENY` makes no task. A standing approval is the server's: a task of that tool still goes through `approve`, so the answer is the same and only
automatic; it is marked `autoApproved` and a note says so. A `DENY` at any level still wins. States: `QUEUED`, `RUNNING`, `WAITING`, `DONE`, `FAILED`,
`CANCELLED`. Tasks left `RUNNING` by a restart become `FAILED`.

The task's result comes back as one event (`task.finished`, `task.failed`, `task.denied`); with no node connected it waits as a `QueuedEvent`.

## Web search and pages

`WebTaskService` and `WebResearch` run the task: the model gets the question and two internal functions, `search` and `read_page`, and loops (at most 10
rounds). `WebSearchClient` calls the sources (`DDGS`, `SEARXNG`, `GOOGLE`, `BRAVE`, `TAVILY`, `PERPLEXITY`) in order; each answers a list of
`SearchHit`. `PageFetcher` reads a page: `DIRECT` (a plain GET stripped with jsoup), `CRAWL4AI` (`POST /crawl`) or `CRAWL4AI_PROXY` (the user's wrapper:
`POST {url}` with `{"url": url}`, answer `{"content": markdown}`). `WebSearchConfig` and `PageConfig` are the `config` of the two tools.

- One `HttpClient` per source, from the common proxy (HTTP(S) only: the JDK client has no SOCKS) or the source's own.
- A status that is not 2xx is a failure of that source with its status and a trimmed message.
- In local-only mode only the sources and readers marked `local` run; nothing is guessed from the URL.
- Fetched content is data, not instructions. Direct reads refuse addresses of the server's own network unless the option allows them (`UrlGuard`).

The prompt of the task and the three event texts are templates (`JoiPromptField`s).
