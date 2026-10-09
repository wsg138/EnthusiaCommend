# Policy v2: unwired correction-storage groundwork merged independently of current autosaves

**Scope:** Additive, test-only integration of the strict replacement helper, typed correction metadata and ledger models from [#34](https://github.com/wsg138/EnthusiaCommend/pull/34), [#37](https://github.com/wsg138/EnthusiaCommend/pull/37), [#38](https://github.com/wsg138/EnthusiaCommend/pull/38), and [#39](https://github.com/wsg138/EnthusiaCommend/pull/39). These source files were copied without altering their tested content, onto the current `main` branch after read-only [#26](https://github.com/wsg138/EnthusiaCommend/pull/26) and [#36](https://github.com/wsg138/EnthusiaCommend/pull/36) integration.

## Why this separate PR

[PR #33](https://github.com/wsg138/EnthusiaCommend/pull/33) changes the *currently active* ordinary `YamlPluginDataStore.saveConfiguration` path. PR #34 originated on top of #33 and PR #39 originated on top of #34. Merging those stacked branches without isolation would also adopt current-autosave behavior changes.

**This branch excludes #33 entirely**, including its live data writer modifications. The only code added is package-private `StrictAtomicFileReplacement`, the pure `CorrectionCommitMetadata` and `CorrectionLedgerState` data types, and associated tests. No production plugin class is edited, no callable correction API is registered, and no class is invoked from existing live runtime entrypoints.

## Existing checks and intended guarantees

- Strict replacement helper writes a uniquely named same-directory temporary file, forces its bytes, requires atomic replacement without a non-atomic fallback and forces the parent directory. A post-rename I/O error is ambiguous: the caller MUST read back and reconcile, not assume rollback.
- Typed correction metadata parser rejects malformed/unknown fields, invalid case revision, wrong subject or duplicate givers, invalid checksum format, and incomplete record schema. SHA-256 format is not a signature.
- Candidate ledger enforces unique operation identifiers and increasing generation, rejects conflicting replays and unsafe retention overflow.
- Synthetic integration tests show the new state and ledger can be serialized into the same file for atomic replacement, plus post-rename ambiguity. The synthetic file is **not** the actual provider `data.yml` format.

## Hard blockers before any real operation

- No authentic Staff case/reviewer/approval binding, commit receipt issuance, primary schema migration, provider-wide lock, stale-autosave fencing, strict receipt readback, or restart recovery.
- The live `data.yml` writer is unchanged by this PR. None of these pieces are connected to it.
- Need independent code review, OS/filesystem staging and end-to-end testing before a correction executor can be introduced.
- Policy v1 remains authoritative, Policy v2 remains disabled. No production deployment is requested.

**Merge rule:** Integrate this source-only PR as an *alternative* to merging the historical stacked #33/#34/#37/#38/#39 chain. Do not merge both copies; keep earlier PRs as design/review provenance and close them with appropriate comments after reconciliation.
