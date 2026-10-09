# Policy v2: typed correction commit metadata (storage groundwork)

**Status:** Unwired schema only, **not** a provider-issued receipt or proof of a committed correction. Stacked on strict atomic writer [PR #34](https://github.com/wsg138/EnthusiaCommend/pull/34).

`CorrectionCommitMetadata` models the fields intended for the same **primary file generation** as a future complete corrected reputation state. The YAML-compatible `toMap()/fromMap()` pair round-trips:

- Operation UUID, Staff case ID and **positive case revision**, reviewer and subject UUIDs.
- SHA-256-format prepared-intent request fingerprint, before-state checksum and after-state checksum.
- Positive primary data generation and proposed commit timestamp.
- Full, exact removed-entry snapshots (giver, target, polarity, category, score weight, creation and edit times).

Parsing is **fail closed** on unknown/missing fields, wrong types, unsupported schema versions, invalid digest formats, malformed or duplicate giver entries, wrong subject, excessive selection, invalid generation/revision, or unchanged before/after checksums. Tests cover normal round trip and invalid representations.

## Trust boundary

A hash or a caller-created instance is **not** a secret MAC, digital signature, Staff permission check, verified source read, or proof that disk data changed. The class does **not** generate a receipt, issue authority, write files, or establish persistence. Do not expose `fromMap` as a Staff fulfillment verdict.

The future executor must authenticate Staff case/revision/reviewer and approvals, verify the exact PREPARED operation against a provider-coherent live snapshot, build complete audit/cooldown/index state, persist it **with this metadata in the same primary data generation** via strict atomic replacement, reconcile ambiguous I/O and stale autosaves, then serve the issued receipt through a trusted readback API. Version upgrades require explicit migrations rather than lenient parsing.

The existing live `data.yml` format is unchanged. No mutation, runtime feature flag, punishment, deployment or merge is included.
