# Verification log

A log of which model verified which commits. The rules are in `CLAUDE.md`, the reason is in `DESIGN.md` → Code base and
style → Who wrote and who verified the code.

**Verified up to:** nothing yet. The first verification has to go over the whole code (nothing before the log was recorded).
Every commit after that line (`git log <hash>..HEAD`) is unverified.

**Latest commit when the log started:** `6dad9e5`

## Verifications

A row says a model reviewed the commits, not that they are correct. The notes say how: what was read, whether the
tests were run, what was fixed. The fix commits made during a verification are part of its row and may be included in
"Verified up to". Do not rebase or squash verified history: the hashes here would point at nothing.

| Date | Verified by | Commits | Notes |
|------|-------------|---------|-------|

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
