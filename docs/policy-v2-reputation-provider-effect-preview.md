# Offline preview against the real EnthusiaCommend provider state

This read-only change is stacked on exact-entry preflight [#26](https://github.com/wsg138/EnthusiaCommend/pull/26), durable PREPARED intent [#27](https://github.com/wsg138/EnthusiaCommend/pull/27), and recovery readback [#28](https://github.com/wsg138/EnthusiaCommend/pull/28). It implements **only the non-mutating provider-data inspection** step of actual executor issue [#29](https://github.com/wsg138/EnthusiaCommend/issues/29).

`ReputationCorrectionProviderPreview.plan(intent, providerData)` reads the actual `PluginDataSnapshot` structure (scores, active `Commendation` values and target identity state). It reconstructs the canonical provider state, verifies the full prepared checksum and exact selected entries against current provider data, projects the post-removal score and exact survivors, and calculates the target's tarnish-source forgiveness for selected negative votes.

The preview:
- Refuses stale independent scores, edited, absent or replaced votes, wrong checksum, mismatched case-bound preparation, and already-changed provider data rather than silently planning a broad removal.
- Preserves arbitrary unaffected entries and other-player state. The score is an independent stored value, not assumed to equal the sum of active vote weights.
- Forgives only the explicitly selected negative givers' tarnish sources in a **projected immutable identity value**. Positive entry removal never forgives negative givers. A legacy tarnished identity with no attribution sources fails closed until the plugin's identity migration/reconciliation is performed.
- Does **not** modify `RepService` indexes, `PluginDataSnapshot`, `data.yml`, removal history, cooldowns, audit history, effects, permissions, Staff cases or the durable operation journal. It does not prepare a fully persistable after-data file or issue a commit receipt.

**Blockers before actual corrections:** executor issue #29 requires a single serialized vote/mutation/save boundary, crash-safe write-ahead transactions spanning `data.yml` and the durable journal, exact immutable removed-entry audit, stable replay receipts, boot-time reconciliation and explicit Staff case/approval authorization. Do not use a preview or an observed after-state as completion proof, and do not enable Policy v2 or merge/deploy these changes without separate owner approval.
