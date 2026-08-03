package org.enthusia.rep.discord;

import org.enthusia.rep.rep.RepCategory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordWebhookServiceTest {
    @Test
    void compactNormalEmbedContainsReasonTimestampAndHeadWithoutRemovedFields() {
        String json = DiscordWebhookService.toJson(new DiscordWebhookService.LogEntry(
                DiscordWebhookService.Action.CREATED, "P2wn", "P2wn", "ExamplePlayer",
                RepCategory.HELPED_ME, "Helped recover lost items",
                Instant.parse("2026-08-03T01:00:00Z"), "https://mc-heads.net/avatar/test/64"));
        assertTrue(json.contains("P2wn repped ExamplePlayer\\nHelped Me • Helped recover lost items"));
        assertTrue(json.contains("2026-08-03T01:00:00Z"));
        assertTrue(json.contains("\"thumbnail\":{\"url\":\"https://mc-heads.net/avatar/test/64\"}"));
        assertFalse(json.contains("Action"));
        assertFalse(json.contains("New Total"));
        assertFalse(json.contains("Value"));
    }

    @Test
    void compactRemovalAndRestorationNameTheActorAndAffectedPlayer() {
        String removed = DiscordWebhookService.toJson(new DiscordWebhookService.LogEntry(
                DiscordWebhookService.Action.REMOVED, "Admin", "Giver", "ExamplePlayer",
                RepCategory.SCAMMED, "Invalid or abusive reputation entry", Instant.EPOCH, null));
        assertTrue(removed.contains("Admin removed reputation from ExamplePlayer\\n"
                + "Scammed • Invalid or abusive reputation entry"));
        assertFalse(removed.contains("REMOVED"));

        String restored = DiscordWebhookService.toJson(new DiscordWebhookService.LogEntry(
                DiscordWebhookService.Action.RESTORED, "Admin", "Giver", "ExamplePlayer",
                RepCategory.HELPED_ME, "", Instant.EPOCH, null));
        assertTrue(restored.contains("Admin restored reputation for ExamplePlayer\\nHelped Me"));
        assertFalse(restored.contains(" • "));
    }

    @Test
    void blankReasonOmitsSeparatorPlaceholderAndThumbnailWhenUnavailable() {
        String json = DiscordWebhookService.toJson(new DiscordWebhookService.LogEntry(
                "P2wn", "ExamplePlayer", RepCategory.HELPED_ME, "   ", Instant.EPOCH, null));
        assertTrue(json.contains("P2wn repped ExamplePlayer\\nHelped Me"));
        assertFalse(json.contains(" • "));
        assertFalse(json.contains("(none)"));
        assertFalse(json.contains("thumbnail"));
    }

    @Test
    void jsonEscapingAndMentionsAreSafe() {
        String json = DiscordWebhookService.toJson(new DiscordWebhookService.LogEntry(
                "P2\\\"wn", "@everyone", RepCategory.SCAMMED, "line1\\nline2", Instant.EPOCH, null));
        assertTrue(json.contains("allowed_mentions"));
        assertTrue(json.contains("P2\\\\\\\"wn repped @everyone"));
        assertTrue(json.contains("line1\\\\nline2") || json.contains("line1 line2"));
    }

    @Test
    void disabledWebhookAcceptsEntriesWithoutThrowing() {
        try (DiscordWebhookService service = new DiscordWebhookService("", Logger.getAnonymousLogger())) {
            assertDoesNotThrow(() -> service.log(new DiscordWebhookService.LogEntry(
                    "A", "B", RepCategory.WAS_KIND, "", Instant.EPOCH, null)));
        }
    }

    @Test
    void descriptionRemainsWithinDiscordLimit() {
        String json = DiscordWebhookService.toJson(new DiscordWebhookService.LogEntry(
                "A".repeat(200), "B".repeat(200), RepCategory.WAS_KIND, "R".repeat(6000), Instant.EPOCH, null));
        assertTrue(json.length() < 4600);
    }

    @Test
    void escapeCoversControlCharacters() {
        assertEquals("\\b\\f\\u0001\\n\\t", DiscordWebhookService.escape("\b\f\u0001\n\t"));
    }
}
