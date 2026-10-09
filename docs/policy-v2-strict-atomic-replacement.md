# Strict atomic replacement groundwork for Policy v2 corrections

**Status:** Source and tests only. The new package-private `StrictAtomicFileReplacement` is **not called** from the running plugin and does not execute a correction.

It is stacked on the ordinary save-durability [PR #33](https://github.com/wsg138/EnthusiaCommend/pull/33). Future correction commits must write a *complete primary data snapshot plus the authenticated operation receipt in the same serialized generation*; this primitive accepts only bytes that such a future transaction builder has already generated and validated.

## Strict write sequence

- Require an existing data directory, and reject a symlink directory or target file.
- Create a **unique** temporary file next to the target, write all bytes and `force(true)` the temporary channel.
- Replace with `ATOMIC_MOVE + REPLACE_EXISTING`, **without** the ordinary autosave's non-atomic fallback.
- Open and force the parent directory. Unlike ordinary autosaves, unsupported directory fsync is an error.
- Cleanup the unique staging file on either success or failure.

The test suite injects failures before writing, after temporary-file fsync, immediately after atomic move, and a simulated unsupported atomic-move error. It also exercises symlink and absent-directory refusal. A post-rename failure intentionally demonstrates that an **exception does not prove rollback**: the new primary bytes may already be present.

## Missing before an executable correction

1. Canonically serialize and persist **the whole corrected reputation state and the provider-issued committed operation receipt together**, with strict schema, checksum, and generation validation.
2. Authenticate reviewer authority, Staff case and revision, exact preflight selection, operation UUID and replay fingerprint.
3. Quiesce all active vote/score/index mutations and autosaves under a shared serialization barrier. Ensure delayed snapshots cannot overwrite a committed generation even after failed writes.
4. Define full removal/audit/identity/cooldown effects, outbox and operational recovery to match ordinary authoritative reputation changes.
5. On ambiguous post-rename failure, **fence** further mutation and reload the authoritative primary state/receipt before returning completion or retrying. Protect against corrupt/missing files and restore/backups.
6. Inject startup/restart failures and simultaneous vote/correction races on the actual target OS and filesystem, with comprehensive staging/appeals acceptance tests.

This deliberately remains an **unwired primitive**, not a correction API, staff remedy receipt, live mutation, production rollout or Policy v2 activation.
