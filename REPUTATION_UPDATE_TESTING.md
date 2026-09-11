# Reputation update acceptance checks

Build: `mvn -B -ntp -Dmaven.compiler.fork=true clean verify` (Java 21+).
The automated suite covers default/custom/category effects, removal and identity persistence, Tarnished state, polarity totals, recent filtering, and book-session cleanup. It also retains the chat/anvil exploit regression tests. These checks do not replace a live Paper/client test of the virtual lectern or external teleport integration.

## Menus and books

1. On Paper 1.21.11, open a player with enough mixed reviews to span pages. Positive and Negative open separate pages; Categories and Back preserve the selected player. Check overall polarity and individual categories in both `/rep top` and `/rep bottom`.
2. From a filtered page after page 1, open a reason, then click Done or press Escape. The same player, filter, and page should reopen. Repeat with a full inventory. Try Take Book, shift-click, number keys, and disconnecting while reading; no temporary book should enter inventories or drop into the world.
3. Confirm long reasons, unbroken text, and category labels fit the tooltips. The book must preserve the full reason. Check both Java and Geyser/Bedrock clients if supported on the server.
4. Repeat the existing [rep input exploit checks](REP_INPUT_TESTING.md). Chat input must remain private and anvil dyes must not escape with a full inventory.

## Reputation and effects

1. Give a player +30 overall score and -10 in Spawn Killed through actual entries. Confirm warzone glow and a 140% EnthusiaTeleport cooldown despite positive overall score. Spawn and duels must not glow. At -12 category score, stalking should be available.
2. Confirm positive teleport tiers at +5/+10/+15/+20, and negative tiers at -10/-15/-25. Check that warmup is unchanged. With default rules, potion duration, pearl/wind cooldowns, firework duration, and movement must be unchanged, and no cashback is displayed or paid.
3. Change a category rule, add an optional effect, disable a rule, then put an effect in `disabledEffects` and reload. Confirm disabled effects clear, including existing teleport modifiers and glow. A same-value category edit must refresh effects.
4. While overall reputation remains positive, receive a new negative entry: score color and PlaceholderAPI colors should turn GOLD for the configured Tarnished window. Set a short duration in a test config or use persisted expired timestamps to check expiry. Restart during the window. Negative totals remain red. A reason-only edit must not extend the window.

## Voting and migration

1. Have giver and target log in on different addresses. Vote, remove as the giver, and try again: the default 24-hour removal cooldown must apply. Repeat for staff remove/reset and after restarting. Confirm `removalCooldownHours: 0` disables it independently of edit cooldown.
2. Using accounts on one client address, attempt to rep each other: both signs should be blocked. Rep a third player from one account, then try from its alternate: blocked even after deleting the first entry and restarting. Distinct addresses should work. Repeat with the first account changing addresses to verify retained history.
3. An offline account with no recorded address is blocked until its first observed login. Confirm the configured proxy forwards actual client addresses. Confirm that only hashes appear in `data.yml`.
4. Upgrade a copy of existing data containing Scam Stall entries and removed entries. They should appear as Scammed with the same values/reasons/timestamps. The old category should not appear in commands or menus.
5. Check `/rep recent Player day positive`, `/rep recent Player week SCAMMED`, and `/rep recent all week all 2`. Confirm target, time boundary, polarity/category, order, and pagination. Change the configured day/week windows and verify after reload. Existing category-specific profile lookup remains available.
