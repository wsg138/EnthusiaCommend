# Policy v2: projected primary generation ledger

**Unwired, pure model; no executable correction and no completion receipt.** Stacked on typed correction metadata [PR #37](https://github.com/wsg138/EnthusiaCommend/pull/37), itself stacked on strict atomic-file groundwork [#34](https://github.com/wsg138/EnthusiaCommend/pull/34).

`CorrectionLedgerState` models `currentGeneration` and a bounded ordered list of `CorrectionCommitMetadata` that will ultimately need to be included within the **same primary data snapshot** as a corrected player's scores, votes, identities, history, cooldowns and receipt. It does not mutate any existing plugin state.

- A new candidate must advance the primary generation **exactly once**. A higher/lower supplied generation fails rather than skipping or reverting history.
- Repeated operation UUID with **identical** metadata returns the unchanged projected ledger (idempotent offline simulation). Repeated UUID with changed metadata fails.
- On load, records must be ordered by strictly increasing data generation, have unique UUIDs and never claim a generation newer than their enclosing snapshot. Unknown/missing fields, floating-point generations, malformed records and oversized lists fail closed.
- No receipt pruning: when the ledger reaches its bounded limit, future append attempts are refused pending an explicit retention/migration policy. Removing prior operation IDs would break idempotent replay.
- Tests exercise append, identical/conflicting replay, stale and skipped generations, malformed ledger, and actual Bukkit YAML roundtrip.

**Security and integration limits:** Caller-created metadata, a projected ledger, a checksum, or a successfully parsed YAML map does NOT prove Staff approval, provider issuance, on-disk persistence or completion. This is a pure candidate model. The production `data.yml` parser/writer and live caches remain unchanged. A future authenticated executor must atomically persist the complete corrected primary state with this ledger, read and reconcile committed generations after crashes, forbid stale autosave overwrites and return receipts only from trusted readback. Integration and controlled cutover remain blocked pending test coverage and owner approval. Policy v2 stays disabled.
