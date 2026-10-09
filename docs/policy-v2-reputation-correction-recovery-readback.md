# Reputation correction: read-only recovery comparison

This is a **non-mutating prerequisite**, stacked on [durable PREPARED intent PR #27](https://github.com/wsg138/EnthusiaCommend/pull/27) and [exact preflight PR #26](https://github.com/wsg138/EnthusiaCommend/pull/26). Nothing is registered in the running plugin. No provider correction can be executed, no staff sanction or remedy can be fulfilled, and Policy v2 stays disabled.

`ReputationCorrectionRecoveryReadback.inspect(prepared, observed)` treats both the prepared intent and the provider's current observation as untrusted. It rechecks the preparation against the original full, SHA-256-verified provider snapshot and exact selection, and separately recomputes the observation checksum. It then derives an immutable expected after-snapshot by removing **only** the exact preflight-selected givers, preserving the complete order and contents of all unaffected entries and the expected total.

The result can only be:

| Outcome | Meaning | May mark a correction complete? |
| --- | --- | --- |
| `ORIGINAL_DATA_UNCHANGED` | Complete observed data still matches the original PREPARED snapshot | **No** |
| `EXPECTED_DATA_ONLY_NO_RECEIPT` | Complete observed data matches the projected after-state | **No**: another actor or partially committed work could have produced it |
| `CONFLICT_REQUIRES_RECONCILIATION` | Neither complete snapshot matches; someone must investigate | **No** |

Missing/wrong player, inconsistent observed checksum, forged prepared checksum or forged expected score reject with errors rather than claim an outcome. The comparator does not inspect secondary indexes, removed-entry audit logs, primary-data-file persistence, case authorization, or a mutation journal, so even an exact data match **cannot provide a successful-remedy receipt**.

## What must exist before execution

1. Case/reviewer authorization, sufficient rank, separate approval where required and a replay-safe operation UUID.
2. One provider-side serialization boundary over player votes, exact-entry mutation, score, secondary indexes, durable journal and primary `data.yml` saving.
3. Crash-recoverable intent-to-primary-data commit protocol with boot-time barriers. A write to one YAML file cannot atomically commit the second YAML file. Asynchronous saves must not revert the operation.
4. Durable provider-issued commit receipt keyed by operation UUID, with exact removed entries, before/after checksums and a documented appeal/compensation route.
5. Failure injection tests for every crash point, stale data, duplicate replay, tampering, drift, concurrent votes, refusal, and missing storage before any live workflow may use the API.

Do **not** treat this PR or the two prerequisite PRs as permission to merge to production, enable Policy v2 or issue live corrections.
