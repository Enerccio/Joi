# Verification log

A log of which model verified which commits. The rules are in `CLAUDE.md`, the reason is in `DESIGN.md` → Code base and
style → Who wrote and who verified the code.

**Verified up to:** `c09c2f2` (the whole code in three passes up to `3dd8af1`, then each commit after it).
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
| 2026-11-22 | `claude-opus-5-5` | `medium` | `61104d2`, `230a8e9` | Read the whole diff of `61104d2` and the code around it (WebSearchClient, WebHttp, PageFetcher, WebToolSettingsServiceImpl, WebTaskServiceImpl's scope of the owner, NodeServiceImpl.receive and hello, BodyServiceImpl, BodyDialog.store) and the stamps of `230a8e9`. Fixed (in `3dd8af1`): the search sources were checked by one DNS lookup and then looked up again by the client (DNS rebinding), and a proxy on the server's network was not checked (`WebHttp` gets `publicOnly`: the proxy is refused, or without one every connection is pinned to the address checked); the same pinning for direct page reads without a proxy; the redirect check that looked up hosts the proxy should resolve was dropped. Node and body changes: no findings (a check-then-create race of two dialogs naming a body at once is left: `create` checks again). Tests run: WebSearchClientTest (a new test), WebToolSettingsTest, WebTasksTest, PageFetcherTest, ToolsUiTest, BodyDialogTest, NodeServiceTest: all pass. |
| 2026-11-22 | `claude-opus-5-5` | `medium` | what the second pass only scanned, up to `230a8e9`; with it its own commit `3dd8af1` (the fixes of this pass and of the review of `61104d2`) and the stamps that follow it | Third pass. Read in full: AnimationDialog, AnimationPackDialog, ClipPreview, SpringBonesPanel, ToggleGroupsPanel, JoiAboutPart, UserSettingsPart, the JavaFX panes (AvatarPane, Chromium, ChromiumPane, ControlsPane, LookingGlassTuningPane, LookingGlassWindow, SpeechPlayer), Launcher, RendererPreviewApp, NodeShots and ShotCommands, `joi-model-viewer.js` and the renderer's `looking-glass.js` (the shaders and the camera math of the views read, not measured on the display). Fixed (in `3dd8af1`): a toggle taken by one group was still offered to the others (ToggleGroupsPanel); the node's screenshot command server took any request (a page in the browser could post a command) and wrote a picture under any name, also outside the out folder (now POST with a JSON content type, names checked); the viewer's download put the decoded bytes into a buffer of Content-Length, so a compressed or cut response threw or left zeros. Left: the Looking Glass quilt size is not capped by the GPU's largest texture (the user's own tuning). Tests run: ModelAppearanceTest (a new test), joi-node compiled with `-Pfx`, the viewer's download checked under node with a stream longer than its Content-Length. |
| 2026-11-22 | `claude-opus-5-5` | `medium` | `ddf8533`, `920e6fb`, `7236ba1`, `fed6fd5`; with it its own commit `0f75532` (the fixes below) and the stamps that follow it | The desktop app. Read in full: the server's local door (`ApiConfiguration.isLocalSocket`, `JoiApiServlet`, `JoiApi.local`, `AccessTokenServiceImpl.issueLocal` and `authenticate`, `AccessPanel`), the protocol 1.8 (`LocalToken`, its schema, `openapi.yaml`), `DesktopLauncher`, the node's `UnixHttp`, `UnixWebSocket`, `LocalMode`, `NodeInstance`, `ServerClient`, `NodeSocket` and the `JoiNodeApp` / `JoiNodeCli` / `Launcher` changes, the root and node POMs, the workflows (permissions, secrets, publishing off pull requests), and the docs and stamps of `7236ba1`, `fed6fd5` and `ddf8533`. The socket is safe as built: it sits in a folder of mode 700, and a TCP call can't pass as local (Jetty gives it a port). Fixed (in `0f75532`): two refreshes at once in the node (a call and the WebSocket, or two calls after a 401) made each other's token useless, the local token being replaced and a used OAuth refresh token ending the pairing; now one lock of the process, and a 401 refreshes only when nobody did meanwhile (`ServerClient.refreshAfter`); the fallback socket folder in a shared `/tmp` could be made first by another user, or be a link (now refused unless it is a real folder of this user); a chunk of negative size in `UnixHttp`; `ApiConfiguration` said the web UI is not served on the socket, while Jetty serves it there too (harmless: only the same user can open it). Left, for the user: the desktop's node shares `~/.joi-client` with a node paired to another server, and signing in locally replaces that pairing's credentials; the actions in the workflows are pinned by tag, not by hash. Tests run: joi-node (80, all pass, the local-socket ones included), JoiApiTest (25, pass), the launcher's socket folder checked by hand (fallback, private mode, a link refused); `DesktopLauncherTest` not run (it needs the WAR in `~/.m2`). |
| 2026-11-22 | `claude-opus-5-5` | `medium` | `5bb00a3`, `233b1b7`, `49ab65d`, `cfa8451`, `7768ec3`, `b489423`, `020429b`, `203475b`, `923bb1a`, `b0d2890`, `6c61caa`, `6318316`; with it its own commit `020b39c` (the fixes below) and the stamps that follow it | The desktop app's own home folder and the iOS app. Read in full: the `233b1b7` diff (`DesktopLauncher`, the `joi-node` scripts; it also settles the shared `~/.joi-client` left open in the last row), `SwiftGenerator`, `ProtocolSamples`, the `SchemaContractTest` change, the joi-protocol, root and `joi-ios` POMs, the renderer page's native mode (`main.js`), and the hand-written Swift: `JoiClient` (one refresh at a time, as the Java node now has it), `Credentials` and the Keychain store, `HTTP`, `FrameChannel`, `NodeConnection` (fresh credentials on each reconnect, final close codes), `CBOR`, `Containers` (bounds, hashes, streamed copies), `FileHash`, `AppModel`, `ChatModel`, `AvatarDirector`, `AvatarModel`, `BodyFiles`, `RendererBridge`, `AvatarView` and its scheme handler (`..` refused, body files by a map, not by path), `SpeechPlayer`, `Views`, `project.yml` (ATS opened for the local network only). The generated Swift was not read line by line: it is checked by `SwiftGeneratorTest` and its own tests. Fixed (in `020b39c`): the app kept one set of credentials whatever the server, so an app pointed at another server (after an error, or by the user) skipped the pairing and sent the first server's token to it (now the credentials are tied to the server that made them, `AppModelTests`); the web view was inspectable in release builds (now in debug builds only); the generator did not escape an enum value that is a Swift keyword, and a value `unknown` would clash with the case of unknown values (none is in the schemas yet). Left: the scheme handler reads her files on the main actor in 1 MB blocks (fine so far, a model of hundreds of megabytes takes many blocks); the app target itself (xcodebuild, the Simulator) was not built. Tests run: joi-protocol (19, `SwiftGeneratorTest` included: the generated files are unchanged), `swift test` of JoiProtocol (76), JoiClient (40) and JoiApp (49, three new): all pass. |
| 2026-11-22 | `claude-opus-5-5` | `medium` | `9b563d8`, `77d8718`, `cd82d3e`; with it its own commit `b34ee9c` (the fixes below) and the stamps that follow it | Pairing a phone by camera. Read in full: `DeviceAuthorizationServiceImpl` (offers: random, capped, ten minutes, one device each, forgotten when the dialog closes), the `JoiApi` change (an unknown offer is a 400 `invalid_request`), `PairPhoneDialog`, `AccessPanel`, the localization, `openapi.yaml` and the protocol 1.9, and the Swift: `AppModel.open` and `pairing(from:)` (only `joi://pair` with an http(s) server and an offer of URL-safe characters), `JoiClient` (the offer, the first poll at once, credentials the Keychain refused are an error), `Credentials`, `ChatModel.switchPart` (one at a time, a 20 s limit, answered on a lost connection), `AvatarModel` (the same files are not shown again), `Views` (Her look), `project.yml` and the ad hoc signing in the POM; the tests and docs of the commit. Fixed (in `b34ee9c`): the QR code was styled inline (now the class `pair-qr`); the build docs (CLAUDE.md, the developer and user build pages) did not install Marginalia's parent POM, so on a new machine the platform did not resolve (now `mvn -N install -f marginalia/pom.xml`, as the workflows do). Left: scanning a pairing link drops the current pairing before the new one works (it is the user's own scan); a failing approval warns on each poll. Tests run: PairPhoneDialogTest (3), JoiApiTest (27), `swift test` of JoiClient (42) and JoiApp (58): all pass; the app target was not built. |
| 2026-11-22 | `claude-opus-5-5` | `medium` | `69fb3b8`, `4eae084`, `0051157`, `1420cb9`, `532f872`, `714077a`, `e8faf68`, `3701cea`, `a4abc50`, `a86e400`, `f5b00b7`; with it its own commit `703ca40` (the fixes below) | Approval cards in the iOS app, lip sync, her face, moods. Read in full: the app's task questions (`ChatModel.answer` and `take`, `TaskQuestionCard`, `HistorySource`; the server resends the waiting tasks on each connect, `NodeServiceImpl.sendWaitingTasks`), `joi-lipsync.js` (the analysis: loudness, the consonant test, linear prediction and the formants, the FFT; `MouthPlayer`), the speech players of the node and the app and their numbering, `RendererServer` (speech, eyes, mood; pages in a copy-on-write list), the renderer page, the viewer's mouth and face layer, `AnimationFace`, `AnimationPack`, the `.joia` change and `joia.md`, `AnimationPackDialog`, `ModelViewer`, `ClipPreview`, `MoodFace`, `MoodSetting`, `PersonaServiceImpl.moods`, `ChatServiceImpl.changeMood` and `watchMoods`, `MoodTools`, `NodeServiceImpl.mood`, the protocol 1.10 (`MoodState`, its schema, asyncapi), `MoodDialog`, `JoiAboutPart`, `UserSettingsPart`, the node and app sides of the `mood` frame, the moods screenshot flow, and the docs of each commit. Fixed (in `703ca40`): the face layer left the point she looks at in the scene each time it was turned off; an animation with a face turned the layer on in the server's dialogs for good, so a later animation without a face, or the expressions the model dialog tries, still had her blinking and her expression overwritten (now the layer an animation turned on goes off with the next animation without one). Left: a new piece numbered 1 after the node loads her again could meet a stale track of the same number if no stop came between (harmless, the mouth only). Tests run: joi-protocol and joi-node (101), NodeServiceTest (34), ChatTurnTest (14), PersonaAndSettingsTest (12), JoisPartTest (7), AnimationsTest (7), JoiaTest (3), `swift test` of JoiProtocol (79), JoiClient (42) and JoiApp (66): all pass; the viewer change checked for syntax only, not in a browser. |
| 2026-11-23 | `claude-opus-5-5` | `medium` | `78260ee`, `e94d19a`, `889be06`, `1a543df`, `7bc4056`, `75c5946`, `da3bc3c`, `ce566dc`, `7026311`, `3c67cdc`, `63dc07b`, `fa86bea`, `2339dcf`, `05fd497`, `5fbb610`, `f84c852`, `13054e1`, `9f6433e`, `d150187`, `93c6450`, `446f8b5`, `1562731`; with it its own commit `c09c2f2` (the fixes below) | The voice line, the retried TTS lease, failed turns kept, CI, images in chat, the assistant workspace. Read in full: `LineOutput`, the `SpeechPlayer` and renderer changes (the `at` of a piece), `TtsServiceImpl.connectionDropped`, `ChatServiceImpl.keepWhatHappened` with `StoreStep` and `CleanupStep`, `CallToolsStep.afterCalls` (no round after a gesture), `AnimationConversionServiceImpl`, the Dockerfile and workflows, `AnimationPacksPart.checkForTest`, the `no_model` error of `NodeServiceImpl.greet`, `WebTaskServiceImpl.origin`, the always-offered tools and `decidePartChange`; for the images `ChatImages`, `ChatImageServiceImpl`, the API's upload and image calls, `PayloadStep`, `StoreStep`, `ChatMessage`, `ChatMessageServiceImpl`, `JoiChatsPart`, the node's upload (`ServerClient`, `UnixHttp`, `JoiNodeApp`, `JoiNodeCli`), the iOS side (`ImageInput`, `ImagePreparer`, `JoiClient`, `ChatModel`, `Views`) and the protocol 1.11; for the workspace `JoiWorkspaceServiceImpl`, `WorkspaceTools`, `WebResearch`, `WebTaskServiceImpl`, `JoiAppSettings`, `JoiFilesPart`; the docs and stamps. Fixed (in `c09c2f2`): `mvn test -pl joi-node`, which CI runs, did not compile, because `fx/LineOutputTest` was compiled without the JavaFX classes it tests (the pom now leaves the `fx` tests to `-Pfx`, `testExcludes`; `testing.md` says so); the iOS history dropped a message that had only images when its text came back null. Left: two nodes with open requests to change her parts can share a request number (the numbers are per session; the first session wins); `WebTaskServiceImpl.origin` says it is null for a task the user made, which it does not check; each `SpeechPlayer` has its own `LineOutput` that is never closed (harmless now: the node loads one assistant per run). Tests run: joi-protocol (19), joi-node (84) and `LineOutputTest` with `-Pfx` (3), ChatImageApiTest, ChatImagesTest, JoiChatsPartTest, NodeServiceTest, ChatTurnTest, WebTasksTest, AssistantWorkspaceTest, JoiaTest, AssistantsRunsTest, TtsLeaseRetryTest, ModelConversionTest, ModelCleanupTest, JoimServiceTest (120), `swift test` of JoiProtocol (83), JoiClient (44) and JoiApp (76): all pass. The app target and the web UI were not run by hand. |

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
| `61104d2` | `claude-opus-5-5` |
| `230a8e9` | `claude-opus-5-5` |
| `3dd8af1` | `claude-opus-5-5` |
| `ddf8533` | `claude-opus-5-5` |
| `920e6fb` | `claude-sonnet-5-5` |
| `7236ba1` | `claude-sonnet-5-5` |
| `0f75532` | `claude-opus-5-5` |
| `5bb00a3` | `claude-opus-5-5` |
| `cfa8451` | `claude-sonnet-5-5` |
| `7768ec3` | `claude-sonnet-5-5` |
| `b489423` | `claude-sonnet-5-5` |
| `020429b` | `claude-sonnet-5-5` |
| `203475b` | `claude-sonnet-5-5` |
| `923bb1a` | `claude-sonnet-5-5` |
| `b0d2890` | `claude-sonnet-5-5` |
| `6c61caa` | `claude-sonnet-5-5` |
| `6318316` | `claude-sonnet-5-5` |
| `77d8718` | `claude-sonnet-5-5` |
| `cd82d3e` | `claude-sonnet-5-5` |
| `4eae084` | `claude-sonnet-5-5` |
| `0051157` | `claude-sonnet-5-5` |
| `1420cb9` | `claude-sonnet-5-5` |
| `532f872` | `claude-sonnet-5-5` |
| `714077a` | `claude-sonnet-5-5` |
| `e8faf68` | `claude-sonnet-5-5` |
| `3701cea` | `claude-sonnet-5-5` |
| `a4abc50` | `claude-sonnet-5-5` |
| `a86e400` | `claude-sonnet-5-5` |
| `f5b00b7` | `claude-sonnet-5-5` |
| `78260ee` | `claude-sonnet-5-5` |
| `e94d19a` | `claude-sonnet-5-5` |
| `889be06` | `claude-sonnet-5-5` |
| `1a543df` | `claude-sonnet-5-5` |
| `7bc4056` | `claude-sonnet-5-5` |
| `75c5946` | `claude-sonnet-5-5` |
| `da3bc3c` | `claude-sonnet-5-5` |
| `ce566dc` | `claude-sonnet-5-5` |
| `7026311` | `claude-sonnet-5-5` |
| `3c67cdc` | `claude-sonnet-5-5` |
| `63dc07b` | `claude-sonnet-5-5` |
| `fa86bea` | `claude-sonnet-5-5` |
| `2339dcf` | `claude-sonnet-5-5` |
| `05fd497` | `claude-sonnet-5-5` |
| `5fbb610` | `claude-sonnet-5-5` |
| `7768ec3` | `claude-sonnet-5-5` |
| `b489423` | `claude-sonnet-5-5` |
| `020429b` | `claude-sonnet-5-5` |
| `203475b` | `claude-sonnet-5-5` |
| `923bb1a` | `claude-sonnet-5-5` |
| `b0d2890` | `claude-sonnet-5-5` |
| `6c61caa` | `claude-sonnet-5-5` |
| `020b39c` | `claude-opus-5-5` |
| `6318316` | `claude-sonnet-5-5` |
| `9b563d8` | `claude-opus-5-5` |
| `b34ee9c` | `claude-opus-5-5` |
| `77d8718` | `claude-sonnet-5-5` |
| `cd82d3e` | `claude-sonnet-5-5` |
| `4eae084` | `claude-sonnet-5-5` |
| `0051157` | `claude-sonnet-5-5` |
| `1420cb9` | `claude-sonnet-5-5` |
| `532f872` | `claude-sonnet-5-5` |
| `714077a` | `claude-sonnet-5-5` |
| `e8faf68` | `claude-sonnet-5-5` |
| `3701cea` | `claude-sonnet-5-5` |
| `a4abc50` | `claude-sonnet-5-5` |
| `69fb3b8` | `claude-opus-5-5` |
| `703ca40` | `claude-opus-5-5` |
| `13054e1` | `claude-sonnet-5-5` |
| `9f6433e` | `claude-sonnet-5-5` |
| `d150187` | `claude-sonnet-5-5` |
| `93c6450` | `claude-sonnet-5-5` |
| `446f8b5` | `claude-sonnet-5-5` |
| `c09c2f2` | `claude-opus-5-5` |
| `aa2a6f7` | `claude-opus-5-5` |
| `211d7e1` | `claude-opus-5-5` |
