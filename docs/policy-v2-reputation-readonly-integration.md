# Policy v2: consolidated read-only reputation correction stack

**Status:** Integration test branch, not a replacement for approved review, not a provider execution API.

This branch starts from the current [preflight PR #26](https://github.com/wsg138/EnthusiaCommend/pull/26) **HEAD**, then imports the complete source and tests for the following existing stacked changes as a single linear source tree:

1. PREPARED intent journal ([#27](https://github.com/wsg138/EnthusiaCommend/pull/27)).
2. Recovery readback classifier ([#28](https://github.com/wsg138/EnthusiaCommend/pull/28)).
3. Stored provider snapshot and identity preview ([#30](https://github.com/wsg138/EnthusiaCommend/pull/30)).
4. Isolated primary-data projection ([#32](https://github.com/wsg138/EnthusiaCommend/pull/32)).
5. Offline removal-history/audit/cooldown projection ([#35](https://github.com/wsg138/EnthusiaCommend/pull/35)).

The original stacked branches diverged in Git history after the preflight static-analysis refactor, even though their preflight sources were synchronized. This integration branch makes PR #26 its actual ancestor and validates the entire non-mutating provider stack together. The imported source files match their current feature branches at assembly time.

## Important

- **Do not merge this PR in addition to the five original feature PRs without a deliberate integration decision.** That would create redundant review/change history and might cause conflicts. Keep the original PRs for their individual review/audit provenance until the owner selects an integration path.
- If this branch is used for eventual integration, independently review its complete diff, CI and Codacy results, and reconcile changes that land on the individual branches afterward.
- It contains **no authorized correction transaction**, no live cache mutation, no atomic primary-data receipt, no authoritative Staff case-revision approval, no external-effect outbox and no post-crash replay.
- Storage-only [#33](https://github.com/wsg138/EnthusiaCommend/pull/33) and [#34](https://github.com/wsg138/EnthusiaCommend/pull/34) are separate and intentionally not included. Their commit protocol remains unwired.
- No production deployment, plugin JAR substitution, automatic merge or Policy v2 mode change is allowed from this branch.

## Review acceptance

All tests, static checks and independent review must pass on the **latest consolidated head**. This validates only read-only projections; the separate authenticated transaction, receipt ledger, startup reconciliation and operator approval are still required for real corrections.
