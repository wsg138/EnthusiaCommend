# Integration-only Policy v2 correction storage stack

**Offline synthetic-data verification only. This is not a production storage upgrade, a trusted receipt or a live reputation correction.**

This branch starts on the latest strict writer [PR #34](https://github.com/wsg138/EnthusiaCommend/pull/34) (itself stacked on ordinary durability #33), then imports current typed metadata [#37](https://github.com/wsg138/EnthusiaCommend/pull/37) and generation/replay ledger [#38](https://github.com/wsg138/EnthusiaCommend/pull/38) source/tests into a **linear ancestry**. The individual original PRs remain available for review; avoid merging both the integrated and original PR chains.

`CorrectionStorageIntegrationTest` verifies two isolated, *synthetic* scenarios on Linux:

1. An old fake primary file is atomically replaced with a new fake subject score **and** a complete projected correction ledger in one serialized YAML file; readback gets the new score, ledger generation and full exact-entry metadata.
2. Injected failure immediately **after** an atomic rename shows that an exception can coexist with a new complete generation on disk. A recovery caller must read back and reconcile; it must **not** assume rollback or blindly apply the correction again. Replaying the exact existing ledger record returns the original simulated ledger.

The test uses deliberately **synthetic** `syntheticSubject` YAML rather than the real `PluginDataSnapshot` layout; arbitrary example checksums do not prove they correspond to scores or authenticated requests. **Do not use this integration fixture as production data or to satisfy Staff remedies.**

## Remaining gates

- The actual `data.yml` schema needs a strict typed migration and ledger field integrated into the full authoritative reputation snapshot; no such migration or load is present here.
- Implement one live provider-wide mutation/snapshot/autosave serialization boundary with stale-generation fencing, and atomic swap or replay-safe rebuild of provider indexes.
- Implement exact mutation semantics for removals, audit, cooldown, identity, analytics, and external event effects; no effects can be published before durable reconciliation.
- Authenticate Staff case/revision, approvals, reviewer role, prepared fingerprint and exact CAS selection independently of user-supplied records.
- Preserve original receipt identity across replay, power loss, corrupt/partial records, backups, appeals and outbound Staff/Market remedial state.
- Inject failures at all transaction points on actual server filesystems and independently review all code before controlled owner-approved deployment. Policy v2 stays disabled.

**No production file, GitHub default branch, plugin runtime or server has been changed.**
