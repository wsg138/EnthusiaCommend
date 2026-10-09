# Offline Policy v2 correction audit and cooldown projection

**No mutation or receipt is implemented here.** This branch extends the pure provider-data [projection PR #32](https://github.com/wsg138/EnthusiaCommend/pull/32) with offline, inspectable *proposed* historical effects.

`ReputationCorrectionAuditProjection.plan(prepared, currentData, occurredAt, removalCooldownMillis)` runs the already verified whole-primary-data projection and adds:

- A separate `RemovedRep` candidate for every **exact** selected giver/target commendation, with deterministic per-operation/per-giver history IDs.
- A separate `ReputationChangeRecord` with `REMOVE`, `ADMIN_CORRECTION`, reviewer ID, private case reference, and the incremental old/new score for each selected entry.
- Projected removal cooldowns: the named giver/target pairs are replaced with the proposed removal timestamp when the active cooldown policy is positive, or cleared if that policy is disabled.
- Unrelated scores, votes, histories, identities, cooldowns, alert settings and other snapshot sections are preserved. The input `PluginDataSnapshot` is never modified.

It rejects a stale or ambiguous before-state, negative cooldown, correction time preceding the PREPARED intent, and score/delta overflow. Tests check mixed positive/negative removals, independent existing historical records and cooldowns, deterministic repeat projection, disabled cooldown, stale total, timing, and integer edge cases.

## Limitations (mandatory blockers)

The candidate is **not a validated executable transaction** and MUST NOT be saved to `data.yml` or presented to Staff as a fulfilled remedy. In particular:

1. The current PREPARED intent has reviewer/case identity but **does not authenticate** a reviewer or bind a current authoritative Staff case revision/second approval.
2. These are proposed audit records, not events that actually occurred. No provider-issued committed operation receipt, persisted generation or durable replay ledger is constructed.
3. There is no provider-wide lock, in-memory index swap, player-facing effects replay, strict YAML schema migration, or startup recovery.
4. The cooldown policy is supplied to a pure function; an executor must instead obtain authoritative provider configuration under the same transaction boundary. A future design must specify whether an administrative correction is supposed to impose a removal cooldown at all.
5. The model does not implement an appeals/compensation operation or trusted outbox for effects and third-party analytics.

The separate strict atomic replacement [PR #34](https://github.com/wsg138/EnthusiaCommend/pull/34) is also an unwired storage prerequisite. Neither primitive alone creates a safe correction executor. Refer to the [atomic commit design PR #31](https://github.com/wsg138/EnthusiaCommend/pull/31).

Policy v2 remains disabled, with no production changes.
