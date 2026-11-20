# Tool permissions and `web_search` (plan)

Status: **stages 1 (permissions), 2 (search clients, page readers, settings), 3 (the task, `web_search`, `fetch_page`, the event) and 4 (the API) are built and tested; 5 and 6 are plan.** Nothing here is in `DESIGN.md` yet; when it is
built it moves into `DESIGN.md` (Tools, Web, Tasks after the reply) and this file goes away.

## What is asked

1. A **permission** for the server's tools that need approval, in a **chain**: the assistant, then the user's settings,
   then the administrator's settings.
2. `web_search`, built on it. It **creates a `JoiTask`** and runs on the **stronger model** (the deep reasoning role).
3. Web search has **options**: a proxy, and more.
4. **Sources**: DuckDuckGo (`ddgs`), SearXNG, Google Programmable Search, Brave, AI search (Tavily, Perplexity), each
   optionally limited to **specific sites**.
5. **Pages**: reading a page goes through **crawl4ai**, including the user's own small proxy (`/crawl`, takes `url`,
   answers `{"content": "<markdown>"}`, reports errors as text in `content`).
6. An `ASK` is answered in the **Joi dialog** and through the **API**.

## Decisions taken (change them here, not in the code)

| Question | Decision |
|---|---|
| What the permission is | `ALLOW` (runs), `ASK` (waits for the user), `DENY` (never runs, she is told). Per tool, `ServerTool` lists the tools (only `web_search` now) with the built-in default (`ASK`). |
| The chain | The value is the first one set of: **assistant, user, administrator, built-in default**. The administrator's value is also a **ceiling**: what is set below it can only be as strict or stricter (`DENY` > `ASK` > `ALLOW`). So an admin `ASK` is a default users can only tighten, an admin `DENY` locks the tool, an admin `ALLOW` is only a default. Without the ceiling the administrator's page would be a default and no more. |
| Where it is stored | One entity, **`ToolMetadata`** (an `ExtendableEntity`): the settings of one tool in one place of the chain. Three real columns to find it by: `scope` (`JOI`, `USER`, `APP`), the `joi` (for `JOI`; the owner is the user for `USER`, the administrator's for `APP`) and `tool_key` (`web_search`, `fetch_page`). **Everything else is the dynamic JSON of its attributes** (no column per option, so a new option needs no migration): `permission` (absent = inherit), `config` (an object, the tool's options) and `secrets` (an object of keys and tokens by name). One row per scope and tool: *use my own search settings* and *my own permission* are one decision per tool and place. Table in `V1__platform.sql` (V1 stays open: `upgrade_dev_db.py` afterwards). |
| Secrets | In `secrets`, each value protected with `InferenceServices.protectSecret` (AES-GCM with `secret.key`) and read with `revealSecret`, as an extension AI keeps its keys; the service does it, never the caller. Never printed, logged, returned by the API or filled into a field again: the panel shows *set*, and an empty field keeps it. |
| Caps | `maxSearches` (6) and `maxPages` (8) per task, in the tool's `config`, so they follow the chain and each place can change them. |
| Pages for her | `fetch_page` is a tool of its own (`ServerTool.FETCH_PAGE`, its own `ToolMetadata`, its own permission, default `ASK`): she gives it a URL the user gave her, it makes a task as `web_search` does (the stronger model reads the page and answers what she asked). The crawl4ai options belong to its metadata; the task of `web_search` uses them too to read the pages it finds, under the permission of `web_search`. |
| Who runs `web_search` | Her function `web_search(question)` creates a `JoiTask` (role `DEEP_REASONING`) and answers at once: "searching, I will tell you". It does not search itself. |
| When the task runs | After the reply that started it is finished (the design of tasks, so one GPU is not asked for two models). An `ASK` task is `WAITING` until the user answers; `DENY` creates no task. |
| What the task does | The stronger model gets the question and two internal functions, `search` and `read_page`, and loops (`InferenceService.round`, at most 10 rounds) until it writes the answer: short, with the sources (title, url) it used. Its transcript is `JoiTaskMessage`s. The two functions are **not** offered to her. |
| How it reports | When it ends, the result is **one event** in her conversation (`task.finished`, kind in `JoiEvents`), as a turn started by the server, so she says it in her own voice. A failed or refused task is an event too (`task.failed`, `task.denied`). With no node connected it waits as a `QueuedEvent` (stage 4 depends on the dispatcher below). |
| Prompts | Every prompt is a template (`CLAUDE.md`): `WEB_SEARCH_TASK_PROMPT` (the task's instruction) and the three event texts, as `JoiPromptField`s with defaults in `JoiDefaults`, editable in both Templates tabs. |
| Config chain of the search itself | The same chain without the ceiling: the user's own search settings if they have any, else the administrator's. A user can bring their own sources and keys. |
| Approval | Joi dialog (new **Tasks** tab: list, approve / deny, cancel, result and transcript) **and** the HTTP API (list, approve, deny). The node protocol is not changed now (a card on the node comes later, roadmap). |
| Proxy | One proxy per source list (`http://host:port`, user and password optional), also used for page fetching, unless a source sets its own. HTTP(S) proxies only: the JDK's `HttpClient` does not do SOCKS. |

## The permission chain (stage 1)

- `ToolPermission` (`ALLOW`, `ASK`, `DENY`, `stricter`), `ServerTool` (`WEB_SEARCH`, `FETCH_PAGE`): enums.
- `ToolMetadata` (entity, `domain/model/impl`), `ToolMetadataRepository` / `JpaToolMetadataRepository`,
  `ToolMetadataService` (beans in `services-config.xml`, class in `persistence.xml`, table and index in V1).
  - `find(scope, joi, tool)`, `save` (the administrator's only by an administrator: `AdminGuard.requireAdmin`; a
    user's only their own; a Joi's only of their own assistant), `delete`.
- `ToolPermissionService`:
  - `Chain chain(Joi joi, ServerTool tool)`: the assistant's, user's, administrator's value (null = not set), the
    built-in one, the **effective** one and **where it came from**, for the UI to say "Ask (from the administrator)".
  - `effective(joi, tool)`; `config(joi, tool)`: the first of assistant, user, administrator that has one.
- **Test:** `ToolPermissionTest` (the whole matrix of the chain and the ceiling; the three scopes are kept apart;
  only an administrator saves the administrator's; a secret is not in the stored content in the clear).

## Web search settings and clients (stage 2)

`WebSearchConfig` (record, the `config` JSON of the `web_search` metadata) and `PageConfig` (the `fetch_page`
metadata's); `ToolPermissionService.config` resolves assistant, else user, else administrator.

- `sources`: a list of `SearchSource`: `id`, `name`, `type`, `enabled`, `url`, `apiKey`, `engineId` (Google),
  `sites` (limit to them), `proxy` (own, blank = the common one).
- `proxy`, `timeoutSeconds` (default 20), `maxResults` (default 8), `language`, `safeSearch`, `userAgent`.
- `pages`: `PageSource`: type `DIRECT` (a plain GET, stripped to text with jsoup), `CRAWL4AI` (the native `POST /crawl`,
  `{"urls":[url]}`, answer `results[0].markdown.fit_markdown` else `raw_markdown`), `CRAWL4AI_PROXY` (the user's
  wrapper: `POST {url}` with `{"url": url}`, answer `{"content": ...}`; `Crawl4AI Proxy Error:` and `Error:` at the start
  of `content` are failures, not text), with `url`, `token` (bearer, optional), `timeoutSeconds`, `maxChars`
  (the page is cut for the model, default 20000).
- The API keys and the tokens are **secrets**: the `secrets` of the metadata, by source id; see above.

Sources (`SearchBackend` per type, all through one `HttpClient` built from the proxy and the timeout):

| Type | Request (checked against the provider's documentation on 2026-11-20, unless marked) | Needs |
|---|---|---|
| `DDGS` (DuckDuckGo) | `POST https://html.duckduckgo.com/html/` (form `q`), results parsed with jsoup (`a.result__a`, `a.result__snippet`, the `uddg` redirect decoded). **No official API: unverified**, fragile by nature, so the parser is tested with a saved page and a failure to parse says so | nothing |
| `SEARXNG` | `GET {url}/search?q=&format=json[&language=&safesearch=0..2&time_range=&pageno=]`; an instance with `json` not in `search.formats` answers **403**; results `results[].title/url/content/engine` | the instance's URL (JSON format on) |
| `GOOGLE` | `GET https://customsearch.googleapis.com/customsearch/v1?key=&cx=&q=&num=` (`num` 1-10, `start`+`num` at most 100, `safe=active`, `hl`, `lr=lang_xx`); one site: `siteSearch=<domain>&siteSearchFilter=i`; items `items[].title/link/snippet`. **The API is closed to new customers and ends for existing ones on 2027-01-01** (Google points to Vertex AI Search): kept for those who still have a key, and the panel says so | key and engine id (`cx`) |
| `BRAVE` | `GET https://api.search.brave.com/res/v1/web/search?q=&count=` (`count` at most 20, `offset` at most 9, `country`, `search_lang`, `safesearch=off\|moderate\|strict`, `freshness`), header `X-Subscription-Token`; sites by `site:` in `q`; `web.results[].title/url/description` | key |
| `TAVILY` | `POST https://api.tavily.com/search`, `Authorization: Bearer tvly-...`, body `query`, `max_results` (at most 20), `search_depth` (`basic` default), `include_answer`, `include_domains` (restrict), `country`, `language`, `safe_search`; `results[].title/url/content/score`, `answer`; errors `{"detail":{"error":...}}`, 401, 429, 432/433/434 for plan limits | key |
| `PERPLEXITY` | `POST https://api.perplexity.ai/search`, bearer, body `query`, `max_results` (at most 20), `search_domain_filter` (up to 200, `-` excludes), `country`, `search_language_filter`; `results[].title/url/snippet/date`; errors `{"error":{"message","type","code"}}`, 400, 401, 429; no answer text (the Sonar and Agent APIs are a different product, not used) | key |

**Errors:** any status that is not 2xx is a failure of that source with its status and a trimmed message (the first of
`error.message`, `detail.error`, `error` or the start of the body); the bodies of Google's and Brave's errors were not
found in their documentation, so none is relied on. 429 and 432-434 say "limit reached" in the message.

**Specific sites:** `include_domains` (Tavily), `search_domain_filter` (Perplexity), `siteSearch` for one site (Google; several
use `(site:a OR site:b)` in the query, unverified), else `(site:a OR site:b)` added to the query (Brave, SearXNG, DuckDuckGo).
Every backend answers a `List<SearchHit>` (`title`, `url`, `snippet`, `source`); the answer of Tavily may add `answer`.
Read in the documentation on 2026-11-20 with the user's go-ahead (Google, Brave, Tavily, Perplexity, SearXNG). **Not
verified:** DuckDuckGo (no API), crawl4ai's native `/crawl` (its documentation was not reachable: the request and the
answer follow the user's own wrapper, which is a working client of it: `POST /crawl` with `urls`, `results[0].markdown`
as an object with `fit_markdown` / `raw_markdown`, or a plain string, else `results[0].raw_markdown`), and the exact top-level keys
of SearXNG's JSON (`results` is what its attributes and clients use). Each test uses a fake server (the JDK's
`HttpServer`), never the real service, so a first real call to each is part of the browser check of stage 5.

**Privacy mode** (`DESIGN.md` → Web): only sources marked `local` (a flag on the source and on the page source, shown in
the panel; nothing is guessed from the URL) run; the others are refused with a message.

- **Tests:** one per backend against a fake server (request shape, parsing, sites, proxy through a fake proxy, a
  timeout, an error status); `Crawl4aiPages` for the three page styles including the wrapper's error texts.

## The task (stage 3)

- `JoiTaskRunner` (service): `submit(joi, kind, title, instructions, toolCallId)`, `approve(task)`, `deny(task, reason)`,
  `cancel(task)`, `drain(joi)`.
  - Waits for the end of the reply: an observer on `TurnEvents.AFTER_CLEANUP` calls `drain(joi)`; one task of an
    assistant runs at a time; it also drains when approved or created while no turn runs (needs
    `ChatService.busy(Joi)`, which does not exist yet: a small addition to `ChatServiceImpl`).
  - On start the tasks left `RUNNING` by a restart become `FAILED` ("the server restarted"); `QUEUED` ones run.
  - Runs in the owner's request scope (as `AppearanceServiceImpl` does) on its own executor, the model locked per call
    (`GpuService`) like a turn does.
- `WebSearchTasks`: her function `web_search` (offered in `COLLECT_TOOLS` unless the permission is `DENY`), the task's
  loop and its two internal functions. The permission is read **when she calls**, not when the turn starts, so a change
  of it in the dialog counts at once.
- Task title = the question, shortened. The result is stored on the task; the event carries it.
- **Event delivery:** the result is told to her by a turn the server starts (`TurnInput` with `eventKind`), through a
  connected node when there is one (as the greeting is, `NodeServiceImpl.greet`), else it is a `QueuedEvent`. The
  gap noted before (no dispatcher takes waiting `QueuedEvent`s) is closed here for events of tasks, and
  built generally if it turns out to be the same code (the node's connect path).
- **A result is in the prompt once** (the design of tasks, not built): this stage does not build it. A search answer is
  short (the task writes a summary, not a page), so it stays as an event message. The designed `fetch_result` and the
  "seen" flag stay open.
- **Tests (fake model server, like `NodeServiceTest`):** she calls `web_search` with `ALLOW` and the task runs after the
  reply and its result comes back as an event; with `ASK` it waits, then runs after approval; `DENY` offers no function
  and a forced call is refused; a task whose model fails ends `FAILED` with an event; the chain (user `ALLOW` under an
  administrator `ASK` still waits).

## The API (stage 4, built, protocol 1.6)

`joi-protocol` first (`CLAUDE.md`: schema, record, `DESIGN.md` together): `TaskInfo` (id, title, tool, status, question,
url, result, error, created, started, finished, `steps` only in the answer for one task), `TaskStep` (role, text,
toolCalls), `TaskList`, `TaskDenial` (`reason`, the body of a denial, may be left out). Statuses are lower case:
`queued`, `running`, `waiting`, `done`, `failed`, `cancelled` (a denied task is `cancelled` with the reason as its
`error`). `openapi.yaml` paths (served by `JoiApi`):

- `GET /api/v1/jois/{id}/tasks` (filter `status`, a wrong one is 400; the newest first, no transcript),
  `GET .../tasks/{task}` (with its transcript),
- `POST .../tasks/{task}/approve`, `.../deny` (`{reason}`), `.../cancel`: the task as it is now; 409 `conflict` when
  its status is not one the call is for (approve and deny: only `waiting`; cancel: not one that ended).
- A task of another assistant or another user is 404. Version 1.6 changes only the version and adds these paths.
- The node WebSocket gets nothing new; a node reads the list again on the existing event (`task_ended`).
- **Test:** `SchemaContractTest` (records and samples), `JoiApiTest` (list, filter, one with transcript, 404s for other
  assistant and user, deny with and without a reason, no answering twice, cancel, approve runs it).
- Left out: the plan had a `permission` field ("why it waits"); the task does not store it and the permission chain
  answers it (`ToolPermissionService.chain`), so the UI shows it from there.

## The UI (stage 5)

- `ToolPermissionsPanel` (`ui/bodies`-style shared component): a row per `ServerTool`; a select (*Inherit*, *Allow*, *Ask*,
  *Deny*) and a line with what is in force and where from. Used in **Administration** (new tab *Tools*), **User
  Settings** (a section), and the **Joi dialog**.
- `WebSearchPanel`: the sources (a grid, add, edit, test), the proxy and the other options, the pages source, a *Test
  search* button; in Administration (the default) and in User Settings (empty = use the administrator's).
- Joi dialog: a **Tasks** tab (`JoiTasksPart`): grid (title, status, started), buttons Approve / Deny / Cancel, the
  result and the transcript below; follows the tasks live as the Chats tab follows chats. The tool permissions of
  the assistant sit above it, or in its About tab (to decide when building it).
- Texts in `J_L` + `JoiLocalizationBundleEN`, classes in `JoiCssStyles` + `joi-styles.css`. `JoisPartTest` and
  `PersonaAndSettingsTest` change with the tabs.
- Verified in the browser on the cloned data (`joi-test-instance`), as the outfits were.

## Order and what each stage leaves working

1. **Permissions** (the chain, storage, service, test). Nothing offered to her yet.
2. **Search clients and settings** (sources, pages, proxy, their tests; no UI, settings written through the service).
3. **The task and `web_search`** (runner, her function, the event, the delivery). Works end to end with settings
   saved by a test or by hand; ASK is answered through the service.
4. **API** (protocol, servlet, tests).
5. **UI** (the three panels, the Tasks tab, the browser check with a real SearXNG or DuckDuckGo and the real model).
6. **`DESIGN.md`** and `CLAUDE.md` (status line), this file removed.

Each stage is its own commit (committed only when asked), tests of the classes touched, the whole tree built at the end.

## Open questions

None open. Decided with the user: the metadata entity for permissions, options and secrets (dynamic attributes, `protectSecret`); the `local` flag is enough for
privacy mode; `fetch_page` is built now with its own metadata; the caps are options that follow the chain. What the
stronger model is: the deep reasoning model of the user's setting, and with none set the chat model does the task (and
the task's transcript says so).
