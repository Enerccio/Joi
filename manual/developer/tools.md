---
label: Server tools
icon: tools
order: 740
covers:
  - joi-server/src/main/java/com/github/enerccio/joi/domain/tools
  - joi-server/src/main/java/com/github/enerccio/joi/domain/web
  - joi-server/src/main/java/com/github/enerccio/joi/domain/service/impl/JoiWorkspaceServiceImpl.java
  - joi-server/src/main/java/com/github/enerccio/joi/ui/dialogs/joi/JoiFilesPart.java
  - joi-server/src/main/java/com/github/enerccio/joi/domain/chat/WorkspaceTools.java
verified: b338033
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
- A user's or an assistant's source may be on the server's own network (localhost, private addresses) only when Administration →
  App settings allows local servers to users (`JoiAppSettingsService.localServersAllowed`); the administrator's may always.
  `WebToolSettingsService.Search.allowPrivate` carries it to `WebSearchClient`. Without leave, `WebHttp` is built `publicOnly`: with a proxy,
  a proxy on that network is refused; without one, the client pins every connection (redirects included) to an address it checked
  (`OutboundAddressGuard.publicOnly`), so a DNS answer that changes after the check does not get through. `PageFetcher` reads directly the same
  way when it has no proxy.
- Fetched content is data, not instructions. Direct reads refuse addresses of the server's own network unless the option allows them (`PageFetcher.checkReachable`, checked again at each redirect). A user's or an assistant's
  own options allow them only when the administrator's do (`WebToolSettingsService.privateAllowed`; their `allowPrivate` is ignored when saved,
  read and tested otherwise).

The prompt of the task and the three event texts are templates (`JoiPromptField`s).

## The workspace and the clipboard

`JoiWorkspaceService` (`domain/service`, `JoiWorkspaceServiceImpl`) opens the workspace of an assistant: a platform `VirtualFileSystem.ofWorkspace` on
`<data>/joi-workspaces/<uuid of the assistant>`, with the limits of the administrator and one `ReentrantLock` per assistant (her functions, her tasks and the
Files tab share it). `open(joi)` checks the owner with `JoiService.findForUser`, so a Joi of another user is refused. The limits are
`JoiAppSettings.workspaceLimits()` (`WorkspaceLimits`: file, whole, files, image, archive; a value that is not positive is the platform's default), edited in
`AppSettingsPanel` (kilobytes in the fields) and read on every open, so a change holds at once. `clear(joi)` empties it. The folder of a purged assistant is not removed.

`fileTools(joi)` binds the platform's `AgentFileToolsImpl` through the function registry and keeps all of its functions but `list_command_results` and
`save_command_result` (Joi has no command runner). `imageTools(joi, shown)` binds the platform's `ImageTools` (`view_image`) over the workspace and `shownImages(joi, shown)` makes the message with the pictures a round asked for (read from the workspace, scaled down by the platform, a path that is gone told in the text).

`WorkspaceTools` is hers: in `COLLECT_TOOLS` it adds `fileTools(joi)`, `list_chat_images`, `save_chat_image(path, image?)` and `offer_file_to_clipboard(path, node?)`, which reads the file from the workspace and calls
the node's `offer_to_clipboard` (`NodeService.callTool`) with the text, so the text does not pass through her turn. All are offered in every turn (a tool that
comes and goes moves the server's cache of the prompt, see `chat-turn.md`); with no node that has `offer_to_clipboard` the offer fails and says so. None is a
`ServerTool`: they touch only her own files, so the chain has nothing to decide; the user's decision is on the node. `ToolEffects` counts
`WorkspaceTools.READ_ONLY` (`list_files`, `read_file`, `grep_files`) as read-only; the others change the workspace or the user's machine, so a turn that did one is finished.

A task writes through the same functions: `WebResearch.withFiles(fileTools)` adds them to the model's own (at most `MAX_FILE_CALLS`, 8, calls, and the rounds of
the loop grow by that), and `WebTaskServiceImpl` takes a snapshot of the workspace before the task and adds the paths that are new or changed to its result, so
she is told where they are. `web_search` and `fetch_page` say in their descriptions that a file is asked for in the question.

**Images.** `list_chat_images` and `save_chat_image` work on the images of the user's messages (`ChatImageService`): the ones of the message being answered (`TurnInput.images`, not linked to a message until `STORE`), then those of the latest 200
messages (`ChatMessageService.findLatest`), newest first, at most 12, none that are gone; a number is a place in that list, so it holds for the turn that listed it. `save_chat_image` writes the kept JPEG with `VirtualFileSystem.writeImage`, so the limits
of the workspace (the image size too) hold. `view_image` is added only when `event.getAi().isMultimodal()`; `WorkspaceTools` puts the turn's `ShownImages` in `TurnProperties.SHOWN_IMAGES`, and `CallToolsStep.afterCalls` adds the message from `shownImages` to the payload before the
next round. A task does the same in `WebResearch.run` (`withImages`, only when the task's model is multimodal; `MAX_IMAGE_CALLS` more rounds). `list_chat_images` and `view_image` are read-only for `ToolEffects`; `save_chat_image` is not.

The user's view is the **Files** tab of the Joi dialog (`ui/dialogs/joi/JoiFilesPart`), the platform's `WorkspaceBrowser` over a `Source` that goes through the service. Its content is built in `load`, once the part has her: the browser lists the
files as it is built (`JoiFilesPartTest`).
