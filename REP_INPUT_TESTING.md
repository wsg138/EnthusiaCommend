# Rep input fixes: 2.13.2-test1

This test build prevents the anvil's temporary dye from being returned or dropped
when its menu closes. It clears tagged GUI items before Minecraft processes the
anvil contents, retains the session until close, and defers GUI transitions until
the inventory click has finished. Ordinary, untagged items are preserved.

Rep chat input is cancelled at the start of both legacy and Paper chat events.
Recipients/viewers are cleared, with cancellation reasserted at HIGHEST priority.
Only the Paper event consumes the message, and confirmation runs on the server
thread. This follows Paper's legacy-to-modern chat event pipeline:
https://github.com/PaperMC/Paper/blob/main/paper-server/src/main/java/io/papermc/paper/adventure/ChatProcessor.java

## Server test

1. Stop the test server, replace the old EnthusiaCommend JAR with this build, and
   restart. Keep the existing plugin data/configuration folder.
2. With a second player watching chat, choose **Type In Chat** and enter a unique
   reason. It should appear in the confirmation GUI but not public chat. Confirm
   once and verify the profile contains the reason. Repeat with `cancel`, `stop`,
   and Retry; ordinary chat after input should still work.
3. Fill every inventory slot. Choose **Type In Anvil**, enter a reason, and click
   the result. Check the inventory, cursor, and ground for leaked dyes. Confirm
   the rep and repeat with a negative rep.
4. Repeat with Escape instead of submitting, repeated result clicks, shift-click,
   number keys, offhand swap, dropping, and dragging. Repeat with inventory space
   available. Legitimate items should remain unchanged.
5. Disconnect while the anvil is open, reconnect, and check for returned dyes.

Automated regression tests exercise event cancellation, legacy/modern handoff,
blank-message retry, full/empty inventory cleanup, repeated/deferred submission,
close, quit, shutdown, and protection of unrelated inventories/items. Actual
server testing is still needed for the installed chat plugins and client behavior.
