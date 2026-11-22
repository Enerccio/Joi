# Verification log

A log of which model verified which commits. The rules are in `CLAUDE.md`, the reason is in `DESIGN.md` → Code base and
style → Who wrote and who verified the code.

**Verified up to:** `1f8fe47` (the whole code in two passes; what the second pass still only scanned is named in its notes).
Every commit after that line (`git log <hash>..HEAD`) is unverified.

**Latest commit when the log started:** `6dad9e5`

## Verifications

A row says a model reviewed the commits, not that they are correct. The notes say how: what was read, whether the
tests were run, what was fixed. **Reasoning** is the reasoning (effort) level the verifying model ran at, as its
environment reports it (`CLAUDE_EFFORT`); a lower level reads less carefully, so a later verification may want to redo it. The commits the verifying model makes during a verification (fixes, docs, stamps) are part of
its row and verified with it: "Verified up to" moves to the newest of them. Do not rebase or squash verified history: the hashes here would point at nothing.

| Date | Verified by | Reasoning | Commits | Notes |
|------|-------------|-----------|---------|-------|
| 2026-11-21 | `claude-opus-5-5` | `medium` | everything up to `af4ccb0` (whole code) | Read in full: the API and node endpoint, the node protocol records in use, the chat turn and its steps, NodeService/NodeSession, all domain services and repositories, entities, summaries, web tools, bodies and animations (SpringBones, ModelCapabilities, conversion, Blender, joim/joia containers), extensions, jobs, workspace tasks, and the node core (ServerClient, NodeSocket, settings, tools, clipboard, notifications, PartStaging, renderer server). The tts package was diffed against Marginalia's audiobook plugin it was copied from and its Joi parts read. Swept, not read line by line: the Vaadin UI (HTML/JS injection, uploads, raw-id lookups, admin gating, threads; the chat deletion and summarize paths read), the JavaFX app (threads, processes), the converter scripts, the frontend and renderer JS, the YAML/JSON schemas (SchemaContractTest), the SQL and Spring XML (booted by the tests). Fixed: duplicate node tool names; NodeSession.markClosed never called; StoreStep/SpeechStep listener errors skipping later listeners and leaking clips; web task drain race; distinct switch_body names; Summarizer get(-1) on an empty list; Blender timeout that never applied (hung Blender held the lock); FBX path from an archive not confined to the work folder; node cache file named by an unchecked server sha256; node socket stopped reading after a frame error; cycles in a VRM node tree (loops, stack overflow); VrmaFiles/UnityMaterial throwing on malformed input; a lost part batch in the node never told; a doc naming a missing class. Open, for the user: readers other than direct and the web/TTS "local" options let a non-admin reach the server's network (F6, F10, F10b); same-named bodies; a deleted assistant with a connected node. Tests run: 10 server classes (121 tests) and joi-node (58), all pass. |
| 2026-11-22 | `claude-opus-5-5` | `medium` | the parts of everything up to `af4ccb0` that the first pass only swept, and `2e5a0da` (its fixes); with it its own commits `cd7efe8` (the fixes below and the App settings and gate) and `1f8fe47` (docs and stamps) | Second pass. Read in full: the SQL (V1) and Spring XML, persistence and logback, the converter scripts, the protocol records, most of the Vaadin UI (main, widgets, workspace, access, jobs, tools, tts, Joi dialog and its tabs, body, outfit and model dialogs, ModelViewer, AppearancePanel, the parts for Jois, bodies, AIs, admin and trash, the personality, mood, preview and AI dialogs, the animation upload, import and export paths), the node's AvatarDirector, LookingGlassBridge, renderer tuning and preview, JoiNodeApp's threads and part staging, JoiNodeCli's questions, the renderer page's main.js, and the `2e5a0da` diff. Only scanned (async, files, saves, HTML injection): AnimationDialog, AnimationPackDialog, ClipPreview, SpringBonesPanel, ToggleGroupsPanel, the rest of JoiAboutPart and UserSettingsPart, the other JavaFX panes, joi-model-viewer.js and looking-glass.js (the rendering math was not reviewed). Fixed: the Joi dialog saved its older copy of her and a turn saved its copy when rolling the mood, each putting back what the other saved (her mood of the day, the user's edit), now `JoiService.update`/`saveMood`; the voice clip upload had no size limit (20 MB, enforced on the server); the outfit snapshots went into an unsynchronized map; an inline style. Also reviewed, uncommitted from the same session: the administrator's gate on private addresses for page readers and TTS servers (Administration → App settings). Open: same-named bodies; a deleted assistant with a connected node; search source URLs (SearXNG) are not kept off the LAN. Tests run: JoiApiTest, TtsAndAdminTest, BodyDialogTest, JoisPartTest, JoiAnimationsPartTest, JoiSummariesTest, BodyOutfitsTest, JoiMemoriesPartTest, ChatTurnTest, WebToolSettingsTest, WebTasksTest, JoiChatsPartTest, NodeServiceTest: all pass. |

## Written by (secondary)

Optional. A commit that has no row here is simply not recorded.

| Commit | Written by |
|--------|------------|
| `0bc9d69` | `claude-sonnet-5-5` |
| `4168ede` | `claude-sonnet-5-5` |
| `031106c` | `claude-sonnet-5-5` |
| `ed86d8f` | `claude-sonnet-5-5` |
| `0e3d77c` | `claude-sonnet-5-5` |
| `64a2196` | `claude-sonnet-5-5` |
| `ee73ef6` | `claude-sonnet-5-5` |
| `7084150` | `claude-sonnet-5-5` |
| `7d8ce47` | `claude-sonnet-5-5` |
| `a44a5aa` | `claude-sonnet-5-5` |
| `7c29655` | `claude-sonnet-5-5` |
| `7a46652` | `claude-sonnet-5-5` |
| `2e5a0da` | `claude-opus-5-5` |
| `cd7efe8` | `claude-opus-5-5` |
| `1f8fe47` | `claude-opus-5-5` |
