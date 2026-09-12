package org.enthusia.rep.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerListNameFormatterTest {
    @Test
    void translatesLiteralRankMarkupAndPreservesUsername() {
        var raw = Component.text("<gold><bold>Rank</bold><reset> <#FF4A00>Player");
        var result = PlayerListNameFormatter.translate(raw);
        assertEquals("Rank Player", PlainTextComponentSerializer.plainText().serialize(result));
        assertTrue(MiniMessage.miniMessage().serialize(result).toLowerCase(java.util.Locale.ROOT).contains("#ff4a00"));
        assertEquals(result, PlayerListNameFormatter.translate(result));
    }

    @Test
    void leavesExistingFormattingAndUnknownTagsIntact() {
        var styled = MiniMessage.miniMessage().deserialize("<green>Player");
        assertEquals(styled, PlayerListNameFormatter.translate(styled));
        var unknown = Component.text("<unrecognized>Player");
        assertEquals(unknown, PlayerListNameFormatter.translate(unknown));
        assertNull(PlayerListNameFormatter.translate(null));
    }
}
