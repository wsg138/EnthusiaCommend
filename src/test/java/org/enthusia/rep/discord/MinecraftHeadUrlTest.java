package org.enthusia.rep.discord;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftHeadUrlTest {
    @Test
    void uuidUsesSquareHeadUrlWithoutNetworkLookup() {
        UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        assertEquals("https://mc-heads.net/avatar/12345678123412341234123456789abc/64",
                MinecraftHeadUrl.resolve(uuid, "Ignored"));
    }

    @Test
    void usernameFallbackAndMissingDataAreGraceful() {
        assertEquals("https://mc-heads.net/avatar/Bedrock+Player/64", MinecraftHeadUrl.resolve(null, "Bedrock Player"));
        assertEquals("https://mc-heads.net/avatar/BedrockPlayer/64",
                MinecraftHeadUrl.resolve(UUID.randomUUID(), "*BedrockPlayer"));
        assertNull(MinecraftHeadUrl.resolve(null, "   "));
    }
}
