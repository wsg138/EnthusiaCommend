# Policy v2: durable reputation correction intent preparation (not execution)

Dependency: [exact entry preflight PR #26](https://github.com/wsg138/EnthusiaCommend/pull/26); external [EnthusiaStaff provider plan #462](https://github.com/wsg138/EnthusiaStaff/issues/462).

The new `ReputationCorrectionIntentJournal` is **not registered as an API**, not called from live moderation, and has **no ability to remove entries or change scores**. It safely persists a proposed provider-owned correction identity before a *future* actual authorized correction transaction.

## Implemented read/write guarantees

- Durable per-operation UUID journal via Bukkit YAML snapshot, temporary file + fsync + **atomic-only replacement** + directory sync, isolated from the plugin's current live data files. If `ATOMIC_MOVE` is unsupported, it refuses to write rather than fall back to a non-atomic replacement; this is a deliberately strict filesystem requirement.
- Each prepared record pins reviewer UUID, case ID, canonical player UUID, original SHA-256 reputation checksum and full provider snapshot, exact selected entry data, expected post-removal score and first prepare timestamp.
- `prepare` runs the exact-entry preflight and persists the journal before returning. An I/O error does not insert the record into memory. `findOperation` and restart loading support original-ID recovery.
- Same operation ID + identical payload replays the original record without changing its timestamp. Reuse with a different actor, case, target, score/entries/checksum fails closed. Intent history is not automatically evicted or silently replaced; reaching capacity blocks new work.
- Journal loading independently validates the snapshot checksum and selected entry set using the same preflight, then rechecks the persisted expected total. Corruption or unexpected fields cannot silently produce a correction.
- Tests cover restart replay, stable original timestamp, forged input, operation ID collisions, changed stored score or expected result, and write failure with no advertised intent. The atomic-move-unavailable path must remain fail-closed in deployment-specific filesystem testing.

## Critical missing work

This is a **PREPARED** intent *only*, not a provider commit or receipt. To meet the remedy contract, the next implementation needs:

1. A serialized provider-authorized execution operation that independently verifies the persisted case decision and operator rank/duty, and rechecks the provider-owned *current* snapshot under the same lock as mutation.
2. A durable write-ahead/reconciliation protocol with the primary `PluginDataSnapshot` file. Those stores are **separate files**; an atomic write to the intent YAML alone cannot make deletion and journal completion atomic. After a crash at either intermediate write, startup must deterministically reconcile or fail closed. The existing `OrderedSnapshotWriter` and async autosave must not overwrite corrections with an older snapshot.
3. Exact changed-entry receipts and before/after checksum, a new explicit API version and read-back by operation ID. The current `ReputationModerationApi` v2 blacklist operations must retain their existing compatibility behavior.
4. A private immutable audit trail, appeals/compensation policy, and main-thread or shared serialization fencing against player votes and concurrent administrative changes.
5. Dedicated force-crash, missing/corrupt data, duplicate replay, partial save, stale snapshot, wrong case and concurrent removal regression tests, plus staged server verification.

Until these gates pass, **do not expose a correction endpoint, activate Policy v2 or call a prepared operation a remedy completion**.
