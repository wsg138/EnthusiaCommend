package org.enthusia.rep.gui;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;

final class GuiText {
    private GuiText() { }

    static List<String> lore(List<String> lines) {
        return lines.stream().flatMap(line -> wrap(line, 36).stream()).toList();
    }

    /** Hard-wrap long tokens too, preserving formatting across line boundaries. */
    static List<String> wrap(String text, int width) {
        List<String> result = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int visible = 0;
        for (int index = 0; index < text.length();) {
            char ch = text.charAt(index);
            if (ch == ChatColor.COLOR_CHAR && index + 1 < text.length()) {
                line.append(ch).append(text.charAt(index + 1));
                index += 2;
                continue;
            }
            if (ch == '\n' || visible >= Math.max(1, width)) {
                String previous = line.toString();
                result.add(previous);
                line = new StringBuilder(ChatColor.getLastColors(previous));
                visible = 0;
                if (ch == '\n' || ch == ' ') { index++; continue; }
            }
            int codePoint = text.codePointAt(index);
            line.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
            visible++;
        }
        if (visible > 0 || result.isEmpty()) result.add(line.toString());
        return List.copyOf(result);
    }
}
