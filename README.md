# EnthusiaCommend

[![Codacy Badge](https://app.codacy.com/project/badge/Grade/2c9e5865361d4b24a29ccd1d64d97767)](https://app.codacy.com/gh/wsg138/EnthusiaCommend/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)

Reputation and commendation plugin for Enthusia SMP, targeting Java 21 and Paper-compatible 1.21.x servers.

## Features

- Player reputation profiles, reviews, leaderboards, and configurable score effects
- Positive commendations worth `+1` and new negative commendations worth `-2`
- Per-category reputation totals derived from persisted commendations
- Staff history, targeted removal, restore, and suspicious-activity reports
- Reciprocity, clustered-downrep, and same-IP abuse detection
- Vault-backed stalking subscriptions with verified transaction results
- Optional PlaceholderAPI, Plan, ProtocolLib, EnthusiaTeleport, WarzoneDuels, and Discord webhook integrations
- Versioned, atomic YAML persistence with periodic autosave and shutdown flushing

## Build and test

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

The deployable jar is created under `target/EnthusiaCommend-<version>.jar`.
