# EnthusiaCommend — SMP Player Guide

This file documents the current player-facing reputation system on Enthusia SMP. The main [`README.md`](README.md) remains the deeper technical/admin reference.

The values below were checked against the live production configuration and current source on August 22, 2026.

## Reputation basics

Every reputation entry has a category and an optional written reason.

- A **positive** reputation entry is worth **+1**.
- A **negative** reputation entry is worth **-2**.
- You cannot give reputation to yourself.
- The current production minimum active-playtime requirement to give reputation is **0 hours**.
- The same giver/target reputation entry is subject to a **24-hour edit/change cooldown**.
- Reasons can be up to **256 characters** and the current GUI uses an anvil-style text input with a 60-second input timeout.

Reputation is intended to describe actual player interactions rather than act as a raw like/dislike counter. The server stores the individual entries, category, giver, reason, and resulting totals.

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

opens another player's profile. Profiles show:

- overall reputation;
- positive and negative entry counts;
- individual reviews;
- category filters and category-specific totals;
- pagination for larger histories.

When viewing another player, the GUI can be used to leave positive or negative reputation. If you already have an entry for that player, the profile also exposes the relevant edit/remove state and cooldown.

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

`/rep top` and `/rep bottom` open paginated reputation leaderboards. Their category buttons can switch between overall reputation and individual reputation categories.

`/rep reviews [player]` gives a compact text view of recent reviews.

`/rep give ...` is the direct command alternative to using the profile GUI.

## Reputation effects

A player's overall reputation can change gameplay at configured thresholds. These effects are cumulative according to the highest thresholds reached.

### Positive reputation

| Reputation | Active configured effects |
| ---: | --- |
| **+5** | +1% movement speed |
| **+10** | +3% movement speed; +5% potion duration |
| **+15** | +5% movement speed; +10% potion duration |

### Negative reputation

| Reputation | Active configured effects |
| ---: | --- |
| **-5** | -1% movement speed |
| **-6** | 3s Ender Pearl cooldown; -5% Elytra-rocket flight duration |
| **-7** | -3% movement speed; 2s Wind Charge cooldown; -10% rocket duration |
| **-10** | -5% movement speed; 5s Ender Pearl cooldown; glowing |
| **-12** | becomes stalkable; -10% potion duration |
| **-15** | 7s Ender Pearl cooldown; 5s Wind Charge cooldown; -15% rocket duration |
| **-20** | 10s Ender Pearl cooldown; 10s Wind Charge cooldown; -10% movement speed; -15% potion duration; -25% rocket duration; red glow |

The current profile GUI has a **Your Rep Effects** item that summarizes the effects associated with your current score.

### Where the effects apply

These reputation effects are intentionally region-aware rather than all being global:

- **Movement-speed changes** apply while the player is in the configured **spawn or Warzone** effect areas.
- **Glow penalties** apply in those same spawn/Warzone effect areas.
- **Potion-duration modifiers** apply in spawn or Warzone.
- **Ender Pearl and Wind Charge cooldown penalties** apply specifically in the **Warzone**.
- Negative Elytra-firework duration changes apply to the relevant gliding/rocket use and are suppressed for WarzoneDuels duel participants.
- WarzoneDuels participants are generally exempt from these reputation gameplay modifiers during the duel so reputation does not distort the duel ruleset.

Leaving the relevant effect zone removes the zone-scoped movement/glow effect instead of permanently changing the player's base attributes.

### Cashback note

The production config still contains `cashback_percent` values at +10 and +15 reputation, and the effects data model can display them. **The current code does not contain an economy transaction hook that actually awards reputation cashback.** Cashback therefore should not be advertised as a current player benefit unless runtime support is added later.

## Stalking low-reputation players

At **-12 reputation or lower**, a player becomes eligible for the reputation stalking system.

Any ordinary player with the normal stalking permission can purchase a stalking subscription:

```text
/rep stalk <player> [days]
```

Current production rules:

- the target must have reputation **-12 or lower**;
- cost is **100 currency per day**;
- minimum purchase is 1 day;
- maximum purchase is **7 days**;
- payment must succeed before the subscription is created.

A stalking subscription does **not** continuously reveal the target or provide a live compass/GPS position.

Instead, while the subscriber is online, the system watches the target's logical region transitions. When the target makes a genuine same-world transition from **Market, Spawn, or Wilderness into the Warzone**, the subscriber receives an alert containing the target's name and the **exact block coordinates where they entered the Warzone**.

The system establishes a fresh baseline after login, respawn, cross-world transitions, initialization, etc., so those lifecycle events alone do not fabricate a Warzone-entry alert.

Use:

```text
/rep stalk list
```

to view active subscriptions and remaining time, and:

```text
/rep stalk cancel <player>
```

to cancel one.

## Rep abuse handling

The plugin detects patterns such as reciprocal reputation trading, clustered down-reputation, and same-IP activity for staff review. These systems create moderation signals/history; they are not themselves an automatic player punishment or automatic ban system.

Individual reputation entries can be audited, removed, or restored by staff when needed.

## What belongs on the public wiki

Useful public information from this plugin includes:

- the +1/-2 scoring system;
- exact selectable reputation categories;
- how to view/give reputation;
- the 24-hour edit cooldown;
- leaderboard/profile behavior;
- the exact live reputation effect thresholds and where they apply;
- how becoming stalkable at -12 works;
- stalking cost, duration, and Warzone-entry-coordinate alerts.

Staff audit commands, Discord webhook delivery, suspicious-case tooling, storage internals, and recovery mechanics should remain repository/admin documentation rather than normal player wiki content.
