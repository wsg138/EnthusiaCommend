package org.enthusia.rep.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;

class MiniMessageColorTagsTest {

    @Test
    void namedBukkitColorsProduceCanonicalMiniMessageTags() {
        assertEquals("<red>", MiniMessageColorTags.opening(ChatColor.RED));
        assertEquals("<green>", MiniMessageColorTags.opening(ChatColor.GREEN.toString()));
    }

    @Test
    void nullAndFormattingCodesFailSafeToWhite() {
        assertEquals("<white>", MiniMessageColorTags.opening((ChatColor) null));
        assertEquals("<white>", MiniMessageColorTags.opening(ChatColor.BOLD));
    }

    @Test
    void applyEscapesMiniMessageOpenersAndBackslashes() {
        assertEquals(
                "<red>hello\\<world\\\\path</red>",
                MiniMessageColorTags.apply(ChatColor.RED, "hello<world\\path")
        );
    }

    @Test
    void emptyTextStaysEmptyInsteadOfProducingEmptyTags() {
        assertEquals("", MiniMessageColorTags.apply(ChatColor.RED, null));
        assertEquals("", MiniMessageColorTags.apply(ChatColor.RED, ""));
    }

    @Test
    void legacyCodeApplicationUsesTheResolvedCanonicalTag() {
        assertEquals("<green>ok</green>", MiniMessageColorTags.apply(ChatColor.GREEN.toString(), "ok"));
    }
}
