from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:120]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "src/main/java/org/enthusia/rep/config/RepConfig.java",
    '''    public boolean crossedEffectThreshold(int oldScore, int newScore) {
        if (oldScore == newScore) return false;
        for (int threshold : effectThresholds.activeMilestones()) {
            if ((oldScore < threshold && newScore >= threshold)
                    || (oldScore > threshold && newScore <= threshold)) {
                return true;
            }
        }
        return false;
    }''',
    '''    public boolean crossedEffectThreshold(int oldScore, int newScore) {
        if (oldScore == newScore) return false;
        for (int threshold : effectThresholds.activeMilestones()) {
            boolean oldActive = threshold <= 0 ? oldScore <= threshold : oldScore >= threshold;
            boolean newActive = threshold <= 0 ? newScore <= threshold : newScore >= threshold;
            if (oldActive != newActive) {
                return true;
            }
        }
        return false;
    }'''
)

replace_once(
    "src/main/java/org/enthusia/rep/discord/DiscordWebhookService.java",
    '''    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\\\", "\\\\\\\\")
                .replace("\\\"", "\\\\\\\"")
                .replace("\\r", "\\\\r")
                .replace("\\n", "\\\\n")
                .replace("\\t", "\\\\t");
    }''',
    '''    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\\\' -> escaped.append("\\\\\\\\");
                case '\"' -> escaped.append("\\\\\\\"");
                case '\\b' -> escaped.append("\\\\b");
                case '\\f' -> escaped.append("\\\\f");
                case '\\r' -> escaped.append("\\\\r");
                case '\\n' -> escaped.append("\\\\n");
                case '\\t' -> escaped.append("\\\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }'''
)

replace_once(
    "src/main/java/org/enthusia/rep/rep/Commendation.java",
    '''    public synchronized int applyUpdate(boolean newPositive, RepCategory newCategory, String newReasonText,
                                        long newLastEditedAt, String newIpHash) {
        int oldValue = scoreValue;
        boolean polarityChanged = positive != newPositive;
        int newValue = polarityChanged ? newCategory.defaultScoreValue() : oldValue;
        positive = newPositive;
        category = newCategory;
        reasonText = newReasonText == null ? "" : newReasonText;
        lastEditedAt = newLastEditedAt;
        ipHash = newIpHash;
        scoreValue = normalizeScoreValue(newPositive, newValue);
        return scoreValue - oldValue;
    }''',
    '''    public synchronized int applyUpdate(boolean newPositive, RepCategory newCategory, String newReasonText,
                                        long newLastEditedAt, String newIpHash) {
        int oldValue = scoreValue;
        boolean polarityChanged = positive != newPositive;
        RepCategory normalizedCategory = newCategory == null
                ? (newPositive ? RepCategory.WAS_KIND : RepCategory.SCAMMED)
                : newCategory.migratedCategory();
        int newValue = polarityChanged ? normalizedCategory.defaultScoreValue() : oldValue;
        positive = newPositive;
        category = normalizedCategory;
        reasonText = newReasonText == null ? "" : newReasonText;
        lastEditedAt = newLastEditedAt;
        ipHash = newIpHash;
        scoreValue = normalizeScoreValue(newPositive, newValue);
        return scoreValue - oldValue;
    }'''
)

replace_once(
    "src/main/java/org/enthusia/rep/rep/RepService.java",
    '''    public record CommendationResult(boolean success, boolean created, Commendation commendation,
                                      long cooldownRemainingMillis, int repDelta) {
        public static CommendationResult created(Commendation commendation) {
            return new CommendationResult(true, true, commendation, 0L, commendation.getScoreValue());
        }

        public static CommendationResult updated(Commendation commendation, int delta) {
            return new CommendationResult(true, false, commendation, 0L, delta);
        }

        public static CommendationResult cooldown(long remainingMillis) {
            return new CommendationResult(false, false, null, remainingMillis, 0);
        }

        public static CommendationResult invalid() {
            return new CommendationResult(false, false, null, 0L, 0);
        }
    }''',
    '''    public record CommendationResult(boolean success, boolean created, Commendation commendation,
                                      long cooldownRemainingMillis, int repDelta, Failure failure) {
        public enum Failure {
            NONE,
            COOLDOWN,
            INVALID_CATEGORY
        }

        public static CommendationResult created(Commendation commendation) {
            return new CommendationResult(true, true, commendation, 0L,
                    commendation.getScoreValue(), Failure.NONE);
        }

        public static CommendationResult updated(Commendation commendation, int delta) {
            return new CommendationResult(true, false, commendation, 0L, delta, Failure.NONE);
        }

        public static CommendationResult cooldown(long remainingMillis) {
            return new CommendationResult(false, false, null, remainingMillis, 0, Failure.COOLDOWN);
        }

        public static CommendationResult invalid() {
            return new CommendationResult(false, false, null, 0L, 0, Failure.INVALID_CATEGORY);
        }
    }'''
)

replace_once(
    "src/main/java/org/enthusia/rep/gui/RepGuiManager.java",
    '''        if (!result.success()) {
            long hoursLeft = (long) Math.ceil(result.cooldownRemainingMillis() / 1000.0D / 3600.0D);
            player.sendMessage(plugin.getMessages().get("rep.cooldown", Map.of("hours", String.valueOf(hoursLeft))));
            openProfile(player, Bukkit.getOfflinePlayer(targetId), returnPage);
            return;
        }''',
    '''        if (!result.success()) {
            if (result.failure() == RepService.CommendationResult.Failure.INVALID_CATEGORY) {
                player.sendMessage(plugin.getMessages().get("rep.category-invalid", Map.of(
                        "list", "Was Kind, Helped Me, Gave Items/Money, Trustworthy, Good Stall, "
                                + "Scammed, Spawn Killed, Griefed, Trapped, Scam Stall")));
            } else {
                long hoursLeft = (long) Math.ceil(result.cooldownRemainingMillis() / 1000.0D / 3600.0D);
                player.sendMessage(plugin.getMessages().get("rep.cooldown", Map.of(
                        "hours", String.valueOf(Math.max(1L, hoursLeft)))));
            }
            openProfile(player, Bukkit.getOfflinePlayer(targetId), returnPage);
            return;
        }'''
)

Path("src/test/java/org/enthusia/rep/config/RepConfigThresholdTest.java").write_text('''package org.enthusia.rep.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepConfigThresholdTest {
    @Test
    void detectsLeavingAnEffectAtItsExactBoundary() {
        RepConfig config = new RepConfig(new YamlConfiguration());

        assertTrue(config.crossedEffectThreshold(-10, -9));
        assertTrue(config.crossedEffectThreshold(10, 9));
        assertFalse(config.crossedEffectThreshold(-9, -8));
        assertFalse(config.crossedEffectThreshold(9, 8));
    }
}
''', encoding="utf-8")

replace_once(
    "src/test/java/org/enthusia/rep/discord/DiscordWebhookServiceTest.java",
    '''    void blankReasonsUseAValidDiscordFieldValue() {
        assertEquals("(none)", DiscordWebhookService.displayReason(null, 1024));
        assertEquals("(none)", DiscordWebhookService.displayReason("   ", 1024));
        assertEquals("reason", DiscordWebhookService.displayReason("reason", 1024));
    }
}''',
    '''    void blankReasonsUseAValidDiscordFieldValue() {
        assertEquals("(none)", DiscordWebhookService.displayReason(null, 1024));
        assertEquals("(none)", DiscordWebhookService.displayReason("   ", 1024));
        assertEquals("reason", DiscordWebhookService.displayReason("reason", 1024));
    }

    @Test
    void jsonEscapingCoversEveryControlCharacter() {
        assertEquals("\\\\b\\\\f\\\\u0001\\\\n\\\\t", DiscordWebhookService.escape("\\b\\f\\u0001\\n\\t"));
    }
}'''
)

replace_once(
    "src/test/java/org/enthusia/rep/rep/CommendationMigrationTest.java",
    '''    void atomicUpdatePreservesWeightUntilPolarityChangesAndSnapshotIsDetached() {
        Commendation commendation = new Commendation(
                UUID.randomUUID(), UUID.randomUUID(), false, RepCategory.SCAMMED,
                "legacy", 1L, 1L, null, -1);

        assertEquals(0, commendation.applyUpdate(false, RepCategory.GRIEFED, "edited", 2L, "hash"));
        assertEquals(-1, commendation.getScoreValue());
        Commendation snapshot = commendation.snapshot();

        assertEquals(2, commendation.applyUpdate(true, RepCategory.WAS_KIND, "positive", 3L, null));
        assertEquals(1, commendation.getScoreValue());
        assertFalse(snapshot.isPositive());
        assertEquals(-1, snapshot.getScoreValue());
        assertEquals("edited", snapshot.getReasonText());
    }
}''',
    '''    void atomicUpdatePreservesWeightUntilPolarityChangesAndSnapshotIsDetached() {
        Commendation commendation = new Commendation(
                UUID.randomUUID(), UUID.randomUUID(), false, RepCategory.SCAMMED,
                "legacy", 1L, 1L, null, -1);

        assertEquals(0, commendation.applyUpdate(false, RepCategory.GRIEFED, "edited", 2L, "hash"));
        assertEquals(-1, commendation.getScoreValue());
        Commendation snapshot = commendation.snapshot();

        assertEquals(2, commendation.applyUpdate(true, RepCategory.WAS_KIND, "positive", 3L, null));
        assertEquals(1, commendation.getScoreValue());
        assertFalse(snapshot.isPositive());
        assertEquals(-1, snapshot.getScoreValue());
        assertEquals("edited", snapshot.getReasonText());
    }

    @Test
    void atomicUpdateMaintainsCategoryInvariant() {
        Commendation commendation = new Commendation(
                UUID.randomUUID(), UUID.randomUUID(), false, RepCategory.SCAMMED,
                "legacy", 1L, 1L, null, -1);

        assertEquals(2, commendation.applyUpdate(true, null, "positive", 2L, null));
        assertEquals(RepCategory.WAS_KIND, commendation.getCategory());
        assertEquals(1, commendation.getScoreValue());

        assertEquals(0, commendation.applyUpdate(true, RepCategory.OTHER_POSITIVE, "legacy category", 3L, null));
        assertEquals(RepCategory.WAS_KIND, commendation.getCategory());
    }
}'''
)

Path("src/test/java/org/enthusia/rep/rep/CommendationResultTest.java").write_text('''package org.enthusia.rep.rep;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommendationResultTest {
    @Test
    void invalidAndCooldownFailuresAreDistinct() {
        assertEquals(RepService.CommendationResult.Failure.INVALID_CATEGORY,
                RepService.CommendationResult.invalid().failure());
        assertEquals(RepService.CommendationResult.Failure.COOLDOWN,
                RepService.CommendationResult.cooldown(1000L).failure());
    }
}
''', encoding="utf-8")
