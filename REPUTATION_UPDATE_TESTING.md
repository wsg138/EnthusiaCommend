# Reputation update acceptance checks

Build: `mvn -B -ntp -Dmaven.compiler.fork=true clean verify` (Java 21+).
The automated suite covers default/custom/category effects, removal and identity persistence, Tarnished state, polarity totals, recent filtering, and command suggestion cleanup. It also retains the chat/anvil exploit regression tests. These checks do not replace a live Paper/client test of client tooltips, GUI navigation, or external teleport integration.

## Menus and navigation

1. On Paper 1.21.11, open a player with enough mixed reviews to span pages. Positive and Negative open separate pages; Categories and Back preserve the selected player. Check overall polarity and individual categories in both `/rep top` and `/rep bottom`.
2. From a filtered leaderboard after page 1, open a player, browse their profile and categories, then use Back to leaderboard. The same leaderboard filter, sort, and page should reopen. Direct `/rep Player` must not show a stale leaderboard return button.
3. Hover over reviews to read reasons. Clicking a review must leave the profile open, including with a full inventory, and must never create a lectern or book. Confirm long reasons and category labels fit tooltips. Type `/rep Player` and check that player suggestions no longer show display-name metadata. Check Java and Geyser/Bedrock clients if supported. Any lecterns left by older builds need to be removed once; their locations were not recorded.
4. Repeat the existing [rep input exploit checks](REP_INPUT_TESTING.md). Chat input must remain private and anvil dyes must not escape with a full inventory.

## Reputation and effects

1. Upgrade data with both Helped Me and Was Kind entries. Confirm only Was Kind appears in menus, their scores combine, and every reason, timestamp, and giver remains intact. Check custom rules from both old categories are retained under WAS_KIND after reload and restart.
2. Edit `rep.categories.SPAWN_KILLED.description`, reload, and inspect both profile and leaderboard category filters. Set `rep.tarnished.color: '#FF4A00'` and check profile names and both legacy/MiniMessage placeholders while Tarnished. Try a custom `rep.colors.positive` too.
3. With the server's rank/tab plugin active, type `/rep Player`. Check that any remaining rank tooltip renders formatting instead of literal MiniMessage tags. Check tab-list names immediately after login and after a rank update. Existing formatted names must retain their styling.

1. Give a player +30 overall score and -10 in Spawn Killed through actual entries. Confirm warzone glow and a 140% EnthusiaTeleport cooldown despite positive overall score. Spawn and duels must not glow. At -12 category score, stalking should be available.
2. Confirm positive teleport tiers at +5/+10/+15/+20, and negative tiers at -10/-15/-25. Check that warmup is unchanged. With default rules, potion duration, pearl/wind cooldowns, firework duration, and movement must be unchanged, and no cashback is displayed or paid.
3. Change a category rule, add an optional effect, disable a rule, then put an effect in `disabledEffects` and reload. Confirm disabled effects clear, including existing teleport modifiers and glow. A same-value category edit must refresh effects.
4. While overall reputation remains positive, receive a new negative entry: score color and PlaceholderAPI colors should turn GOLD for the configured Tarnished window. Set a short duration in a test config or use persisted expired timestamps to check expiry. Restart during the window. Negative totals remain red. A reason-only edit must not extend the window.

## Voting and migration

1. Have giver and target log in on different addresses. Vote, remove as the giver, and try again: the default 24-hour removal cooldown must apply. Repeat for staff remove/reset and after restarting. Confirm `removalCooldownHours: 0` disables it independently of edit cooldown.
2. Using accounts on one client address, attempt to rep each other: both signs should be blocked. Rep a third player from one account, then try from its alternate: blocked even after deleting the first entry and restarting. Distinct addresses should work. Repeat with the first account changing addresses to verify retained history.
3. An offline account with no recorded address is blocked until its first observed login. Confirm the configured proxy forwards actual client addresses. Confirm that only hashes appear in `data.yml`.
4. Upgrade a copy of existing data containing Scam Stall entries and removed entries. They should appear as Scammed with the same values/reasons/timestamps. The old category should not appear in commands or menus.
5. In `/rep top`, cycle the clock through Score, Most recent, Recent: day, and Recent: week. Confirm ordering and time boundaries, including positive/negative/category filters and pagination. Change configured day/week windows and verify after reload. Confirm the removed recent, reviews, positive, and negative subcommands are absent from completion lists.

## Overall profiles and admin forgiveness

1. Open /rep Player with mixed positive and negative entries: both must appear initially. Positive, Negative, and All reps filters must continue working.
2. Open both give-reason menus: four dyes should occupy symmetric positions, two on either side of center. Click every dye and verify its category.
3. Give a positive player two negative votes. Admin remove one: Tarnished remains. Restart, then admin remove the second: normal color and status return immediately. Repeat through the staff command and GUI. Remove the newer vote while an earlier vote remains: expiry must use the earlier vote's original time. Player self-removal must not provide admin forgiveness.

4. Upgrade old data whose latest negative vote was already admin removed: stale Tarnished must clear on startup. If an earlier negative remains, use its original expiry. Repeat with data saved by test4 and verify a second restart retains the correction. Dye pairs should be adjacent in middle-row columns 3–4 and 6–7, matching the supplied screenshots.
