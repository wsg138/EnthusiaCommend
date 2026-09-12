# EnthusiaCommend — SMP Player Guide

This file documents the current player-facing reputation system on Enthusia SMP. The main [`README.md`](README.md) remains the deeper technical/admin reference.

These are the shipped defaults for 2.14.0. Server administrators can change the thresholds, effects, and time windows; this is not a claim about an already deployed server configuration.

## Reputation basics

Every reputation entry has a category and an optional written reason.

- A **positive** reputation entry is worth **+1**.
- A **negative** reputation entry is worth **-2**.
- You cannot give reputation to yourself.
- The default minimum active-playtime requirement is **12 hours**, configurable by the server.
- The same giver/target reputation entry is subject to a **24-hour edit/change cooldown**. Removing it also starts a separate **24-hour cooldown**, including staff removals; this is configurable.
- Reasons can be up to **256 characters** and the current GUI uses an anvil-style text input with a 60-second input timeout.

## Reputation categories

### Positive (+1)

| Category | Intended use |
| --- | --- |
| **Was Kind** | Friendly behavior, useful help, or support |
| **Gave Items/Money** | Fairly gave items or money |
| **Trustworthy** | Kept promises and acted reliably |
| **Good Stall** | Ran a fair/reliable market stall |

### Negative (-2)

| Category | Intended use |
| --- | --- |
| **Scammed** | Scammed or deliberately misled another player |
| **Spawn Killed** | Killed players unfairly around spawn |
| **Griefed** | Damaged/destroyed another player's build |
| **Trapped** | Used a trap unfairly against another player |

Legacy generic categories and Scam Stall are not selectable. Old Scam Stall entries migrate to Scammed without changing their score or written reason.

## Commands and GUI

```text
/rep
/reputation
```

opens your own reputation profile.

```text
/rep <player>
```

opens another player's profile on the positive page. The Positive/Negative buttons switch between separate pages, with the newest created or edited entries first. Categories filters the selected side; Back returns to that side, and All reps combines both sides. Hover over a review to read its reason. Profiles opened from a leaderboard have a Back to leaderboard button that restores its filter, sort, and page. Category-selection menus also have Back buttons.

Other player commands:

```text
/rep top
/rep bottom
/rep give <player> <category> <reason>
/rep stalk <player> [days]
/rep stalk list
/rep stalk cancel <player>
```

## Reputation effects

Only teleport cooldown changes, warzone glow, and stalking are enabled by default. Cashback has been removed. Administrators can add or remove rules for overall reputation and for each category independently.

### Teleport cooldown

These multipliers require the EnthusiaTeleport integration. They change cooldown, with no default warmup change.

| Score threshold | Cooldown multiplier |
| ---: | ---: |
| +5 | 83.33% |
| +10 | 75% |
| +15 | 66.67% |
| +20 | 50% |
| -10 | 140% |
| -15 | 160% |
| -25 | 200% |

The strongest penalty wins over a reward, and multipliers do not stack. Each category evaluates these thresholds independently using its own score. For example, an overall score of +30 with -10 Spawn Killed still causes glow and a 140% teleport cooldown.

### Glow and stalking

At -10 in overall reputation or any negative category, glow activates in the configured Warzone outside Spawn. At -20 it becomes red when ProtocolLib is available. WarzoneDuels exemptions remain in place for glow. At -12 overall or in any negative category, the player becomes eligible for stalking. Administrators can change these rules per category.

### Tarnished

A positive-reputation player who receives a new negative entry, or whose existing positive entry is changed to negative, is marked **Tarnished**. Their reputation color changes from green to orange for 24 hours while their total remains positive. Negative and zero totals retain their normal red/yellow colors. Editing the text of an existing negative entry does not extend the timer. Duration, label, and color are configurable, and the timer survives restarts.

### Recent reputation

In `/rep top`, click the clock to cycle Score, Most recent, Recent: day, and Recent: week. Recent sorts players by their latest received reputation entry's creation or edit time; day/week restrict that activity to the configured window (24/168 hours by default). Positive, negative, and category filters apply to that activity too. The displayed scores remain category/polarity totals. Click a player to inspect their reviews, then use Back to leaderboard to resume browsing. The same sorting is available in `/rep bottom`.

## Stalking low-reputation players

At **-12 overall reputation or in any negative category**, a player becomes eligible for reputation stalking by default.

```text
/rep stalk <player> [days]
```

Current rules:

- target must meet an enabled overall or category stalking rule;
- cost is **100 currency per day**;
- minimum purchase is 1 day;
- maximum purchase is **7 days**.

A stalking subscription is not live GPS. When the target makes a genuine same-world transition from Market, Spawn, or Wilderness into the Warzone, an online subscriber receives an alert containing the target's name and the exact block coordinates where they entered.

Use `/rep stalk list` to view active subscriptions and `/rep stalk cancel <player>` to cancel one.

## Rep abuse handling

By default, the plugin blocks reputation between accounts with a shared recorded IP address. It also blocks an alternate account on the same recorded address from repping a player already repped by another account, even after removal or restart. Both players must have a recorded login address before voting is permitted after an upgrade. The setting is configurable. Reciprocal trading and clustered down-reputation still generate staff review alerts.

## Public wiki guidance

Useful public information includes the +1/-2 scoring system, selectable categories, profile/review commands, the 24-hour edit cooldown, current live effect thresholds, and the stalking system. Staff audit tooling, storage internals, recovery mechanics, and webhook details should remain repository/admin documentation.
