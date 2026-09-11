package org.enthusia.rep.gui;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuiTextTest {
    @Test
    void boundsLongTokensWithoutLosingUnicodeOrColor() {
        String text = "x".repeat(70) + "😀";
        var lines = GuiText.wrap(ChatColor.GREEN + text, 36);
        assertEquals(text, String.join("", lines.stream().map(ChatColor::stripColor).toList()));
        assertTrue(lines.stream().allMatch(line -> line.startsWith(ChatColor.GREEN.toString())));
        assertTrue(lines.stream().map(ChatColor::stripColor).allMatch(line -> line.codePointCount(0, line.length()) <= 36));
    }
}
