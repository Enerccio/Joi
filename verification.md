# Verification log

A log of which model verified which commits. The rules are in `CLAUDE.md`, the reason is in `DESIGN.md` → Code base and
style → Who wrote and who verified the code.

**Verified up to:** `af4ccb0` (the first, whole-code verification; the parts that were only swept are named in its notes and
still want a line-by-line read). Every commit after that line (`git log <hash>..HEAD`) is unverified.

**Latest commit when the log started:** `6dad9e5`

## Verifications

A row says a model reviewed the commits, not that they are correct. The notes say how: what was read, whether the
tests were run, what was fixed. The fix commits made during a verification are part of its row and may be included in
"Verified up to". Do not rebase or squash verified history: the hashes here would point at nothing.

| Date | Verified by | Commits | Notes |
|------|-------------|---------|-------|
| 2026-11-21 | `claude-opus-5-5` | everything up to `af4ccb0` (whole code) | Read in full: the API and node endpoint, the node protocol records in use, the chat turn and its steps, NodeService/NodeSession, all domain services and repositories, entities, summaries, web tools, bodies and animations (SpringBones, ModelCapabilities, conversion, Blender, joim/joia containers), extensions, jobs, workspace tasks, and the node core (ServerClient, NodeSocket, settings, tools, clipboard, notifications, PartStaging, renderer server). The tts package was diffed against Marginalia's audiobook plugin it was copied from and its Joi parts read. Swept, not read line by line: the Vaadin UI (HTML/JS injection, uploads, raw-id lookups, admin gating, threads; the chat deletion and summarize paths read), the JavaFX app (threads, processes), the converter scripts, the frontend and renderer JS, the YAML/JSON schemas (SchemaContractTest), the SQL and Spring XML (booted by the tests). Fixed: duplicate node tool names; NodeSession.markClosed never called; StoreStep/SpeechStep listener errors skipping later listeners and leaking clips; web task drain race; distinct switch_body names; Summarizer get(-1) on an empty list; Blender timeout that never applied (hung Blender held the lock); FBX path from an archive not confined to the work folder; node cache file named by an unchecked server sha256; node socket stopped reading after a frame error; cycles in a VRM node tree (loops, stack overflow); VrmaFiles/UnityMaterial throwing on malformed input; a lost part batch in the node never told; a doc naming a missing class. Open, for the user: readers other than direct and the web/TTS "local" options let a non-admin reach the server's network (F6, F10, F10b); same-named bodies; a deleted assistant with a connected node. Tests run: 10 server classes (121 tests) and joi-node (58), all pass. |

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
