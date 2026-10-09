# Policy v2 reputation correction: atomic commit and recovery design

**Status:** Design proposal only. No execution endpoint, production activation, or authoritative commit receipt is implemented by this document.
**Scope:** EnthusiaCommend exact-entry corrections for a Staff Policy v2 case.
**Dependencies:** Read-only preflight PR #26, prepared-intent journal PR #27, readback comparator PR #28, and primary-data offline preview PR #30 (stacked, unmerged). Confirm current branch ancestry before implementation.

## Executive decision

A prepared intent persisted in a separate journal, followed by mutation of reputation in `data.yml`, is **not** an atomic transaction. A later comparison of selected entries is **not** evidence of an authorized commit. Do not expose a destructive Policy v2 correction API based on those two operations alone.

**Proposed commit boundary:** Store the reputation after-state **and** its durable provider-issued operation receipt as one generation of the **same primary data file**, then replace that file atomically. The separate prepared-intent journal is a prerequisite and recovery input, not the authoritative commit outcome. A startup barrier and monotonic snapshot generation must ensure no old autosave can overwrite an accepted correction.

This design requires changes to the existing data model, persistence contract, mutation serialization, and recovery bootstrap **before** accepting any destructive request. It is intentionally not a claim that the existing file I/O code already provides this guarantee.

## Verified current implementation constraints

- `RepService.removeCommendationInternal` changes giver/target indexes, total score, negative-vote identity state (for staff actions), removal cooldown, removed-entry history, reputation change history, anti-abuse index, dirty flag and audit callbacks. Reusing it as a Policy v2 transaction would mutate memory before a durable receipt.
- `ReputationSnapshotFactory.snapshot` gets commendation snapshots and total score in separate reads. A recomputed checksum authenticates internal consistency of the supplied value, **not** an atomic, authoritative read of the live service.
- `RepService.snapshot` captures scores, commendations, identities and other collections in separate passes. There is no demonstrated single transaction lock shared with every mutator.
- `CommendPlugin.buildSnapshot` also combines separate Rep, stalk and analytics snapshots.
- `OrderedSnapshotWriter.saveIfNewer` serializes saves and refuses an older sequence after a newer sequence is accepted. This guarantee must be retained, including its behavior after a newer save fails.
- `YamlPluginDataStore` currently writes a temporary `data.yml`, then moves it into place, with an unsupported-atomic-move fallback. No explicit temporary-file and parent-directory durability barrier is shown in this path.
- Existing `ReputationModerationStore` writes `moderation-state.yml` separately from primary reputation `data.yml`.
- PR #30's `ReputationCorrectionProviderPreview.plan` checks actual persisted score and selected commendations against the prepared before-state, and projects some identity/tarnish effects, but does not mutate or persist a candidate. It does not bind identity state to a prepared operation, so commit-time full-state CAS is still required.\n- `ReputationCorrectionIntentJournal.Prepared` does not represent a committed correction; `ReputationCorrectionRecoveryReadback` can only classify matching observed snapshots, not identify the actor or transaction responsible.

These are architectural constraints, not proof that a live correction race or data loss has occurred.

## Non-negotiable invariants

1. **Authorization:** Every operation references an immutable Staff case ID and case revision, subject UUID, reviewer UUID, reviewer authority/approval evidence, exact selected entries, policy snapshot, and an idempotency UUID. Validation must be independent of user-controlled answers. A revocation or case revision change before commit blocks execution.
2. **Fresh CAS:** Under the same provider serialization barrier as mutation, re-read the complete authoritative subject state and compare its fingerprint and every selected entry with the authorized before-state. On mismatch, do not mutate.
3. **One committed state:** The primary storage generation contains both the full after-state (including all secondary persisted fields) and a committed-operation receipt. It must never contain the latter without the former or vice versa.
4. **No historical overwrite:** A delayed autosave or shutdown save with an older captured generation cannot overwrite the correction or its receipt, including after a failed higher-generation save.
5. **Replay safety:** Reusing an operation UUID with a different request is an error. Reusing it with an identical request returns the *persisted* receipt, never re-applies the delta.
6. **No inferred completion:** Matching an after-state without a receipt is `EXPECTED_DATA_ONLY_NO_RECEIPT`, never success.
7. **Fail closed:** Ambiguous filesystem failure, corrupt records, missing receipt metadata, unsupported atomic replacement, inconsistent generations, or unresolved startup recovery blocks this subject's correction/related mutations pending operator reconciliation. Do not silently recreate empty records.
8. **Historical preservation:** Other player data, unselected entries, audit and reputation-change histories, cooldown policy, score effects, identity/tarnish, anti-abuse state and analytics remain consistent with the defined correction semantics.
9. **No cross-plugin claim without proof:** A Staff or Market remedy is not marked complete until the provider commits, returns a verifiable receipt, and Staff durably binds it to the proper case revision.

## Proposed persistence shape

Extend the *primary* `PluginDataSnapshot` format with an explicit monotonic `dataGeneration` and bounded, retention-governed `committedCorrections` ledger. Each committed record includes at minimum:

- operation UUID and a canonical fingerprint of all request fields
- Staff case ID and revision, reviewer ID and attested authority reference
- subject ID, policy snapshot ID, prepared-intent fingerprint
- exact entry identifiers/content hashes, original state hash and full resulting state hash
- score before/after, committed timestamp, storage generation, schema version
- receipt digest protected by the provider's canonical encoding

The receipt must be part of the **same serialized file replacement** as the resulting score, commendations, history, identity, cooldowns and derived persistent indexes. Never write the committed ledger only to `moderation-state.yml` or to the preflight journal. A second file may mirror completion later for search, but cannot make completion authoritative.

A SHA-256 checksum detects inconsistency; it is not a secret MAC or proof of who supplied a snapshot. Any external consumer must fetch the receipt directly from the trusted provider and verify the Staff case/revision linkage rather than accepting an arbitrary caller-supplied checksum.

**Open decision:** Receipt retention, backups and migrations must be specified before code execution. Deleting an old receipt while clients may replay its operation UUID breaks idempotency. Prefer durable operation-tombstone retention or explicitly bounded replay with a safe rejection beyond the horizon.

## Provider commit state machine

```
PREPARED (journal persisted, reputation unchanged)
  -> AUTHORIZED_AND_FRESH (Staff case/revision and exact CAS under provider lock)
  -> COMMIT_WRITE_IN_PROGRESS (candidate full snapshot + receipt in one generation)
  -> COMMITTED (durable primary data generation and receipt verifiably present)

Any indeterminate filesystem outcome -> RECONCILIATION_REQUIRED, never success.
```

`AUTHORIZED_AND_FRESH` and `COMMIT_WRITE_IN_PROGRESS` are **process states**, not separate durable journal milestones that by themselves establish completion. Authorization must remain valid at the actual commit boundary. A multi-file prepare record can remain pending if the primary file never receives a committed receipt.

**Commit procedure (design, not implemented):**

1. Validate the prepared operation's canonical request, case revision and reviewer authority from an authenticated Staff source. Reject missing/expired/revoked approval.
2. Acquire one provider-wide mutation barrier shared by player votes, edits, staff commands, administrative score changes, snapshot assembly, cooldown/identity updates, and autosave scheduling. Reject concurrent writes rather than allowing uncoordinated changes.
3. Check the operation ledger first. Return an exact-match existing committed receipt; reject UUID/fingerprint mismatch.
4. Build a fresh coherent before-snapshot inside the barrier. Re-run exact-entry preflight/CAS against it. Ensure the full primary data snapshot is coherent, not just the selected player's public `ReputationStateSnapshot`.
5. Build an isolated **copy-on-write candidate** of all persisted structures. Apply the intended exact removals and defined compensation semantics only to the candidate. Validate score ranges and all giver/target/identity/history invariants. No plugin-visible in-memory mutation yet.
6. Attach the committed receipt and increment the storage generation **within that candidate**. Reserve a new ordered save sequence and establish an explicit barrier against queued/in-flight pre-correction snapshots.
7. Durably save a temporary file, flush it, atomically replace the primary file on a supported filesystem, and flush the parent directory where supported. If any phase is uncertain, read and reconcile under the barrier; do not tell Staff success merely because replacement may have happened.
8. Re-read and verify the persisted generation, full state and receipt from the primary file. Only then publish/swap the in-memory state and emit success to Staff; after persistence, avoid side effects that can throw before memory publication. Events/analytics/integrations need deterministic replay-safe delivery or an outbox.
9. Release the barrier. On an error or ambiguous durability, fence writes and require controlled recovery, never silently resume votes/autosaves on a potentially stale in-memory state.

**Important implementation caveat:** The current `RepService` stores mutable concurrent maps, Bukkit-visible callbacks, and separately synchronized lists. The project does not yet provide atomic copy-on-write swap or comprehensive write locking. Do not simply paste this procedure around `removeCommendationInternal`; refactoring and staged tests are required.

## Crash and restart matrix

| Failure point | Durable primary file | Required result |
| --- | --- | --- |
| Before journal persistence | Original; no commit receipt | No operation. New attempt must supply valid authorization. |
| After PREPARED, before primary write | Original; no commit receipt | Prepared only; report `ORIGINAL_DATA_UNCHANGED`, no completion. |
| While constructing candidate / before replacement | Original; no commit receipt | Discard candidate, require fresh authorization/CAS on retry. |
| During temporary-file write / fsync | Original expected; temporary file may remain | Block on I/O ambiguity, remove only confirmed orphan temporary data after inspection. |
| During replace / parent fsync | Original **or** new generation | Startup/readback must inspect persisted receipt and full state; never rely on the caller's expected checksum alone. |
| Primary new generation durable, journal not updated | New generation contains authoritative receipt | Receipt proves provider commit; later reconcile journal mirror, do not reapply. |
| After persistence, before in-memory publish | New generation contains receipt; memory may be old | Recovery/barrier loads committed generation and rebuilds derived state before writes resume. |
| After in-memory publish, before Staff acknowledgement | New generation contains receipt | Identical replay returns existing receipt; no second removal. |
| Old delayed autosave arrives after commit | New generation with receipt | Writer rejects stale generation/sequence; test this even when later write failed. |
| Corrupt/partially missing ledger or data | Unknown | Fail startup closed; do not interpret a lenient YAML empty load as original state. |

A data after-state matching the projection **without** a provider-issued committed receipt is ambiguous, including if another staff change happened to create the same result. It must remain uncredited and trigger reconciliation.

## Tests required before execution API

### Pure modeling

- Exact original and projected-after match; reordered entries with a recomputed checksum produce conservative conflict unless canonical order is guaranteed and normalized.
- Selection of positive, negative and mixed signed votes, limits, duplicate giver/target, malformed data and overflow.
- Forged/recomputed snapshot checksum does not grant authorization or establish completion.
- Replay of identical operation, conflict on changed input, case revision or reviewer.
- Historical audit, identity/tarnish and removal history semantics explicitly defined and verified.

### Contention

- Two simultaneous votes/edits on the same target during prepare/CAS.
- Two simultaneous Staff corrections for overlapping entries.
- Staff score `set` and blacklist operations racing a correction.
- Autosave snapshot taken before commit but queued afterward; failure of a newer save must not re-enable stale overwrite.
- Shutdown flush and async analytics snapshot racing a correction.

### Persistence injection

- Fail each of temp creation, write, flush, atomic rename, parent directory flush, primary readback, receipt parsing and history load.
- Kill/restart at every state-machine edge.
- Simulate unsupported atomic move and Windows-specific directory-flush behavior; no silent downgrade of the durability guarantee.
- Corrupt/truncated `data.yml` and correction ledger, absent mandatory fields, wrong types, duplicate operation IDs, and failed migration.
- Verify every restart loads either the complete old generation or the complete new generation with receipt; never a mixed committed state.
- Prove retention/replay behavior across compaction, backups/restores and schema upgrades.

### Cross-provider acceptance

- Staff requires case-revision-bound provider-issued receipt; no prepared intent or projected match satisfies a remedy.
- Provider/API unavailable leads to explicit pending/manual escalation, not automatic success.
- Appeals and compensation create a distinct authorized operation with their own receipts; do not erase the historical correction.

## Staged delivery plan

1. **Read-only milestone (current):** Complete independent review of PR #28 and PR #30 and reconcile stacked PR #26 → #27 → #28 → #30 ancestry. Keep all three non-mutating.
2. **Persistence groundwork PR:** Strict primary file read/validation, durable atomic replacement capability check, data generation and ledger schema with migration tests. Do **not** expose a correction method.
3. **Serialization groundwork PR:** Add one coherent snapshot/mutation boundary and queued-autosave sequence fencing. Test normal votes/edits unchanged.
4. **Candidate/receipt PR:** Pure copy-on-write correction projection, full derived state validation, idempotent ledger and adversarial crash tests. Still no externally callable destructive endpoint.
5. **Provider integration PR:** Authenticate Staff case/revision/reviewer; commit under barrier; return exact durable receipt; implement fail-closed recovery and appeal compensation with independent tests.
6. **Staging verification:** Exercise restarts, backups/restore, concurrent votes and Staff workflows against staging copies only. Remain disabled until an owner-approved deployment and cutover.

## Implementation blockers / questions

- Decide whether to introduce a complete immutable reputation aggregate to support atomic swap, or to keep existing structures with a carefully proved global lock and rollback-safe mutation. Do not mutate existing maps before the candidate's durable persistence.
- Determine all mutation entrypoints (including third-party commands, listeners and background tasks) and their scheduling/thread semantics.
- Establish a reliable storage durability contract across actual production OS/filesystem; the current `data.yml` fallback on unsupported atomic move is insufficient for an atomic-receipt guarantee.
- Define primary receipt retention, schema migration, trusted Staff case-revision proof and signature/authenticity boundaries.
- Verify how Plan analytics, effects, external audit/webhooks and Staff compensation reconcile after a committed primary write but failed asynchronous notification.

**Safety gate:** Until these blockers are resolved with tests, the Policy v2 exact-entry API remains unavailable. Current production Policy v1 is unchanged.
