package org.enthusia.rep.discord;

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
