# Policy v2 exact-entry correction: isolated primary-data projection

**Status:** Non-mutating, **NOT** a complete primary-data candidate and **NOT** an executable correction. Stacked on [PR #30](https://github.com/wsg138/EnthusiaCommend/pull/30) → #28 → #27 → #26. Policy v2 remains disabled.

`ReputationCorrectionPrimaryDataProjection.project(prepared, providerData)` builds a defensive copy of a supplied `PluginDataSnapshot`, then invokes the already-tested exact-entry provider preview. It refuses stale scores, changed or missing vote versions, forged before-state and ambiguous legacy negative-vote tarnish. It creates an isolated projected data view that changes **only** the selected subject's score, identified commendations, and attributable negative-vote tarnish state.

The projection preserves unrelated players' scores and active commendations, original removals, stalk data, analytics records, suspicious cases, cooldowns, trade alert preferences and unrelated identities. Mutable `Commendation` and `SuspiciousRepCase` objects are copied to prevent a later change to the supplied objects from mutating the projection.

## What this does not do

- No live service mutation, filesystem write, Bukkit event, API registration, policy activation, approval or case-revision verification.
- No new removal history, new reputation-change audit, removal cooldown, commit receipt, committed operation ID, storage generation, Plan event, or outbox record. These are deliberate omissions, not proof of completed side effects.
- No guaranteed coherent live snapshot without a future provider-wide read/mutation lock. The supplied `PluginDataSnapshot` may have been assembled from separately mutable structures.
- No safe persistence or replay guarantee. **Do not persist the returned projected data.** It is solely an inspectable expected primary-data view for reviewing and testing the next isolated transaction-construction milestone.

## Required next milestone

Build a full offline transaction candidate with exact case/reviewer/revision-bound authorization, removal/audit/cooldown semantics and replay-safe receipts. Serialize candidate state **and its receipt in one atomic primary data generation** as described in [PR #31](https://github.com/wsg138/EnthusiaCommend/pull/31). Add a global writer/reader barrier and failure-injection tests before exposing a correction API.

Until that protocol has been proven, neither PREPARED intents, matching recovery observations, nor this projection may satisfy a Staff remedy.
