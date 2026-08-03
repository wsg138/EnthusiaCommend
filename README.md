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

## Reputation category views

`/rep top` and `/rep bottom` open a paginated leaderboard. Use the category icons to switch between overall reputation and every registered category. `/rep <player>` uses the same category registry and shows the target's overall total plus a selectable total for every category; selecting one filters the displayed entries and pagination to that category.

## Personal rep-trading alerts

`/rep alerts` toggles whether the executing player receives suspicious rep-trading alerts. The default is controlled by `rep-trading-alerts.enabled-by-default` (default `true`). Explicit UUID choices are stored under `playerSettings.<uuid>.repTradingAlertsEnabled` in `data.yml` and are not overwritten by config reloads.

## Stalking logical zones

The stalking transition resolver does not use WorldGuard region IDs. It uses the cuboids under these exact configuration paths:

- `regions.market`
- `regions.spawn`
- `regions.warzone`

Each cuboid entry uses `world`, `min`, and `max`, with coordinates written as comma-separated `x, y, z` values. Configure the market cuboid even when it is physically nested inside the broad warzone cuboid. Resolution precedence is **MARKET → SPAWN → WARZONE → WILDERNESS**; locations in worlds without any configured cuboid resolve to `OTHER`. A stalking alert is sent only when the resolved destination becomes `WARZONE` and the prior resolved zone was `MARKET`, `SPAWN`, or `WILDERNESS`. Login, respawn, and reload establish a baseline without alerting.

## Discord reputation webhook

Created and updated reputation entries use one compact embed description:

```text
Giver repped Recipient
Category • Reason
```

The reason line omits the separator when no reason exists. The embed includes the event timestamp and a 64px square Minecraft head thumbnail for the reputation giver, resolved as a URL from the giver UUID through `mc-heads.net`; no skin download or blocking lookup occurs on the server thread. Removed/restored moderation audit records remain persisted and are not presented as a misleading “repped” webhook event.
