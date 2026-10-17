# Primary data.yml save durability groundwork

This PR hardens **ordinary** primary snapshot saves in `YamlPluginDataStore`, without altering the reputation schema, correction API, moderation policy, or autosave sequencing.

## Change

1. Write YAML to `data.yml.tmp` as before.
2. Flush temporary-file contents and metadata via `FileChannel.force(true)`.
3. Replace `data.yml` using the existing atomic-move preference and existing compatibility fallback.
4. Flush the parent directory on filesystems supporting directory `FileChannel`. Retain the existing Windows-directory-channel exception convention used by `ReputationModerationStore`.
5. Return failure if an I/O step throws; on a failed write attempt, `OrderedSnapshotWriter` continues fencing older queued snapshots.

Tests cover complete replacement of an existing snapshot with different player keys, reload and no leftover temp file. Existing compatibility/failure tests continue to apply.

## Important limitations

This is **not** sufficient to make a Policy v2 correction safe:

- The preexisting **non-atomic rename fallback** remains available for regular autosaves. It must **not** be used for a combined correction and receipt transaction.
- A write may have replaced `data.yml` before a directory flush failure; the caller must not interpret a returned failure as guaranteed rollback.
- The current `load` parser can skip malformed records, and there is no primary-file receipt ledger or generation yet.
- `data.yml` and the PREPARED journal / moderation-state file are still different files, and are not committed atomically.
- No coherent provider-wide live snapshot/mutation lock exists yet.

Follow [the correction atomicity design](https://github.com/wsg138/EnthusiaCommend/pull/31) to introduce a **separate stricter** storage contract for authorized corrections: no non-atomic fallback, receipt and data in one generation, strict validation, recovery barriers and failure-injection tests.

No new destructive operation is implemented or activated here. Policy v2 remains disabled.
