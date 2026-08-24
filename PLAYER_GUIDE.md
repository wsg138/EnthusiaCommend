# EnthusiaCommend — SMP Player Guide

This file documents the current player-facing reputation system on Enthusia SMP. The main [`README.md`](README.md) remains the deeper technical/admin reference.

The values below were rechecked against the live production configuration and the current 2.13.1 source on August 23, 2026.

## Reputation basics

Every reputation entry has a category and an optional written reason.

- A **positive** reputation entry is worth **+1**.
- A **negative** reputation entry is worth **-2**.
- You cannot give reputation to yourself.
- The current production minimum active-playtime requirement to give reputation is **0 hours**.
- The same giver/target reputation entry is subject to a **24-hour edit/change cooldown**.
- Reasons can be up to **256 characters** and the current GUI uses an anvil-style text input with a 60-second input timeout.

## Reputation categories

### Positive (+1)

| Category | Intended use |
| --- | --- |
| **Was Kind** | Friendly or considerate behavior |
| **Helped Me** | Useful help or support |
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
| **Scam Stall** | Ran a misleading/dishonest market stall |

Older generic positive/negative categories can still exist in migrated historical data, but they are not selectable for new reputation entries.

## Commands and GUI

```text
/rep
/reputation
```

opens your own reputation profile.

```text
/rep <player>
```

opens another player's profile. Profiles show overall reputation, positive/negative entry counts, individual reviews, category filters, category-specific totals, and pagination.

Other player commands:

```text
/rep top
/rep bottom
/rep reviews [player]
/rep give <player> <category> <reason>
/rep stalk <player> [days]
/rep stalk list
/rep stalk cancel <player>
```

## Reputation effects

The current 2.13.1 code deliberately disables all reputation movement-speed modifiers. Legacy movement-speed keys still exist in the production config for compatibility, but they are inert and are **not active gameplay effects**.

### Positive reputation

| Reputation | Active effects |
| ---: | --- |
| **+5** | No active gameplay effect |
| **+10** | +5% beneficial potion duration |
| **+15** | +10% beneficial potion duration |

The config/data model still contains cashback values at +10 and +15, but the current code does not contain an economy transaction hook that actually awards reputation cashback, so cashback should not be advertised as a current benefit.

### Negative reputation

These effects are cumulative as the score becomes more negative.

| Reputation | Active effects added at this threshold |
| ---: | --- |
| **-5** | No active gameplay effect |
| **-6** | 3s Ender Pearl cooldown; -5% Elytra-rocket duration |
| **-7** | 2s Wind Charge cooldown; Elytra-rocket penalty increases to -10% |
| **-10** | Glowing |
| **-12** | Becomes stalkable; -10% beneficial potion duration |
| **-15** | Ender Pearl cooldown increases to 7s; Wind Charge cooldown to 5s; Elytra-rocket penalty to -15% |
| **-20** | Ender Pearl cooldown increases to 10s; Wind Charge cooldown to 10s; potion penalty to -15%; Elytra-rocket penalty to -25%; red glow |

There is **no 5-second Pearl tier at -10** in the current code. The Pearl penalty remains 3 seconds from -6 through -14, then becomes 7 seconds at -15 and 10 seconds at -20.

### Where the effects apply

- **Glow penalties** apply in the configured Spawn/Warzone effect areas.
- **Potion-duration modifiers** apply in Spawn or the Warzone.
- **Ender Pearl and Wind Charge cooldown penalties** apply specifically in the Warzone.
- Negative Elytra-firework duration changes apply while gliding and are suppressed for WarzoneDuels participants.
- WarzoneDuels participants are generally exempt from reputation gameplay modifiers so reputation does not distort duel rules.

## Stalking low-reputation players

At **-12 reputation or lower**, a player becomes eligible for reputation stalking.

```text
/rep stalk <player> [days]
```

Current rules:

- target must have reputation **-12 or lower**;
- cost is **100 currency per day**;
- minimum purchase is 1 day;
- maximum purchase is **7 days**.

A stalking subscription is not live GPS. When the target makes a genuine same-world transition from Market, Spawn, or Wilderness into the Warzone, an online subscriber receives an alert containing the target's name and the exact block coordinates where they entered.

Use `/rep stalk list` to view active subscriptions and `/rep stalk cancel <player>` to cancel one.

## Rep abuse handling

The plugin detects patterns such as reciprocal reputation trading, clustered down-reputation, and same-IP activity for staff review. These create moderation signals/history; they are not automatic punishments.

## Public wiki guidance

Useful public information includes the +1/-2 scoring system, selectable categories, profile/review commands, the 24-hour edit cooldown, current live effect thresholds, and the stalking system. Staff audit tooling, storage internals, recovery mechanics, and webhook details should remain repository/admin documentation.