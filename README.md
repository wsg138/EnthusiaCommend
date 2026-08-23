# EnthusiaCommend

[![Codacy Badge](https://app.codacy.com/project/badge/Grade/2c9e5865361d4b24a29ccd1d64d97767)](https://app.codacy.com/gh/wsg138/EnthusiaCommend/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)

Reputation and commendation plugin for Enthusia SMP, targeting Java 21 and Paper-compatible 1.21.x servers.

For the current **player-facing SMP behavior**—categories, scoring, commands, exact live benefit/penalty thresholds, region scoping, and stalking—see **[`PLAYER_GUIDE.md`](PLAYER_GUIDE.md)**. This README remains the technical/admin reference.

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

To reproduce the Java static-analysis report used for pull-request review:

```bash
mvn --batch-mode --no-transfer-progress org.apache.maven.plugins:maven-pmd-plugin:3.26.0:pmd
```

The deployable jar is created under `target/EnthusiaCommend-<version>.jar`.

## Reputation category views

`/rep top` and `/rep bottom` open a paginated leaderboard. Use the category icons to switch between overall reputation and every registered category. Overall views retain the overall leaderboard population; category views include only players with an actual record in that category, including records whose values total zero. Sorting, ranks, and pagination all use that filtered population, and empty categories show an explicit empty state. Each open GUI freezes the visible player or review identities so a concurrent reputation change cannot redirect a click to a different entry. `/rep <player>` uses the same category registry and shows the target's overall total plus a selectable total for every category; selecting one filters the displayed entries and pagination to that category.

## Administrative rep-trading alerts

`/rep alerts` is an administrative moderation toggle. It is available only to operators or players granted `enthusiacommend.rep.alert`; the same permission is required when an alert is delivered. The permission defaults to `op`.

`rep-trading-alerts.enabled-by-default` remains `true`, but it applies only to authorized administrators. An authorized administrator with no explicit choice receives alerts, `/rep alerts` toggles that administrator's personal preference, and the UUID-keyed choice persists under `playerSettings.<uuid>.repTradingAlertsEnabled` in `data.yml`. Losing the permission immediately prevents command access and delivery without erasing the saved preference; regaining it restores the existing saved choice. One administrator's preference does not affect another administrator.

## Stalking logical zones

The stalking transition resolver does not use WorldGuard region IDs. It uses the cuboids under these exact configuration paths:

- `regions.market`
- `regions.spawn`
- `regions.warzone`

Each cuboid entry uses `world`, `min`, and `max`, with coordinates written as comma-separated `x, y, z` values. Stalking classification uses the configured world plus X/Z only; Y remains in the shared cuboid syntax for compatibility with other 3D region consumers. Fresh configurations include these production X/Z defaults:

```yaml
regions:
  market:
    - world: world
      min: -72, 0, -281
      max: 102, 256, -162
  spawn:
    - world: world
      min: -48, 0, -33
      max: 69, 256, 84
  warzone:
    - world: world
      min: -218, 0, -404
      max: 219, 256, 188
```

The normal defaults merger still adds missing keys such as `regions.market`. On startup or reload, a spawn or warzone list is migrated only when it exactly matches the old shipped single-cuboid default (`-50..50` spawn or `-500..500` warzone in `world`). Custom worlds, coordinates, extra keys, and multi-cuboid lists are preserved. The migration is idempotent and the saved configuration is reparsed immediately.

General gameplay classification uses **SPAWN → WARZONE → WILDERNESS** in managed worlds. `MARKET` is not returned by the general resolver, so market coordinates inside the broad warzone remain `WARZONE` for pearl, wind-charge, and other existing gameplay effects. Stalking classification separately uses **MARKET → SPAWN → WARZONE → WILDERNESS**; unmanaged worlds resolve to `OTHER` in both classifications.

Completed same-world move and teleport transitions are observed at Bukkit `MONITOR` priority with cancelled events ignored. The regular move listener excludes teleport events so a teleport is processed only once. Cross-world teleports, `PlayerChangedWorldEvent`, login, respawn, reload, and initialization establish a new stalking baseline without alerting. A later genuine same-world transition from `MARKET`, `SPAWN`, or `WILDERNESS` into `WARZONE` alerts once.

## Discord reputation webhook

Created and updated reputation entries use one compact embed description:

```text
Giver repped Recipient
Category • Reason
```

The reason line omits the separator when no reason exists. The embed includes the event timestamp and a 64px square Minecraft head thumbnail for the reputation giver, resolved as a URL from the giver UUID through `mc-heads.net`; no skin download or blocking lookup occurs on the server thread. Removal and restoration audit events are also sent through the same ordered asynchronous queue, using compact action-specific wording that identifies the actor and affected player without exposing UUIDs, database IDs, internal enum names, totals, or reputation amounts. Missing thumbnail data simply omits the thumbnail.
