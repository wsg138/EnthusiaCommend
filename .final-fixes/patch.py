from pathlib import Path


def patch_rules() -> None:
    path = Path("src/main/java/org/enthusia/rep/rep/RepRules.java")
    text = path.read_text(encoding="utf-8")
    anchor = '''    public static boolean isRecentReciprocal(Commendation reverse, long nowMillis) {'''
    method = '''    public static RepCategory acceptedCategory(RepCategory category, boolean positive) {
        RepCategory candidate = category == null
                ? (positive ? RepCategory.WAS_KIND : RepCategory.SCAMMED)
                : category;
        return candidate.isSelectable() && candidate.isPositive() == positive ? candidate : null;
    }

    public static boolean isRecentReciprocal(Commendation reverse, long nowMillis) {'''
    if anchor not in text:
        raise SystemExit("RepRules insertion anchor not found")
    path.write_text(text.replace(anchor, method), encoding="utf-8")


def patch_service() -> None:
    path = Path("src/main/java/org/enthusia/rep/rep/RepService.java")
    text = path.read_text(encoding="utf-8")
    old = '''        RepCategory normalizedCategory = category == null
                ? (positive ? RepCategory.WAS_KIND : RepCategory.SCAMMED)
                : category.migratedCategory();
        if (!normalizedCategory.isSelectable() || normalizedCategory.isPositive() != positive) {
            return CommendationResult.invalid();
        }'''
    new = '''        RepCategory normalizedCategory = RepRules.acceptedCategory(category, positive);
        if (normalizedCategory == null) {
            return CommendationResult.invalid();
        }'''
    if old not in text:
        raise SystemExit("RepService category validation block not found")
    path.write_text(text.replace(old, new), encoding="utf-8")


def patch_discord() -> None:
    path = Path("src/main/java/org/enthusia/rep/discord/DiscordWebhookService.java")
    text = path.read_text(encoding="utf-8")
    old_send = '''    private void send(LogEntry entry) {
        if (closed.get()) {
            return;
        }
        try {'''
    new_send = '''    private void send(LogEntry entry) {
        try {'''
    if old_send not in text:
        raise SystemExit("Discord queued-send guard not found")
    text = text.replace(old_send, new_send)

    old_reason = '''    private static String truncate(String value, int maxLength) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength - 3) + "...";
    }'''
    new_reason = '''    static String displayReason(String value, int maxLength) {
        String safe = value == null || value.isBlank() ? "(none)" : value;
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength - 3) + "...";
    }'''
    if old_reason not in text:
        raise SystemExit("Discord reason formatter not found")
    text = text.replace(old_reason, new_reason)
    text = text.replace('field("Reason", truncate(entry.reason(), 1024), false)',
                        'field("Reason", displayReason(entry.reason(), 1024), false)')
    path.write_text(text, encoding="utf-8")


def patch_tests() -> None:
    path = Path("src/test/java/org/enthusia/rep/rep/RepRulesTest.java")
    text = path.read_text(encoding="utf-8")
    anchor = '''        assertEquals(-2, RepCategory.GRIEFED.defaultScoreValue());
    }
}'''
    replacement = '''        assertEquals(-2, RepCategory.GRIEFED.defaultScoreValue());
    }

    @Test
    void publicCategoryValidationRejectsLegacyAndMismatchedCategories() {
        assertEquals(RepCategory.WAS_KIND, RepRules.acceptedCategory(null, true));
        assertEquals(RepCategory.SCAMMED, RepRules.acceptedCategory(null, false));
        assertEquals(RepCategory.GRIEFED, RepRules.acceptedCategory(RepCategory.GRIEFED, false));
        assertNull(RepRules.acceptedCategory(RepCategory.OTHER_NEGATIVE, false));
        assertNull(RepRules.acceptedCategory(RepCategory.OTHER_POSITIVE, true));
        assertNull(RepRules.acceptedCategory(RepCategory.WAS_KIND, false));
    }
}'''
    if anchor not in text:
        raise SystemExit("RepRulesTest insertion anchor not found")
    path.write_text(text.replace(anchor, replacement), encoding="utf-8")

    discord_test = Path("src/test/java/org/enthusia/rep/discord/DiscordWebhookServiceTest.java")
    discord_test.parent.mkdir(parents=True, exist_ok=True)
    discord_test.write_text('''package org.enthusia.rep.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscordWebhookServiceTest {
    @Test
    void blankReasonsUseAValidDiscordFieldValue() {
        assertEquals("(none)", DiscordWebhookService.displayReason(null, 1024));
        assertEquals("(none)", DiscordWebhookService.displayReason("   ", 1024));
        assertEquals("reason", DiscordWebhookService.displayReason("reason", 1024));
    }
}
''', encoding="utf-8")


patch_rules()
patch_service()
patch_discord()
patch_tests()
