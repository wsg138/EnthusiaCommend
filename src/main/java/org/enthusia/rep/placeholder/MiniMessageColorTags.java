package org.enthusia.rep.placeholder;

import org.bukkit.ChatColor;

import java.util.Locale;

/** Produces canonical MiniMessage color tags from resolved Bukkit color data. */
public final class MiniMessageColorTags {
    private MiniMessageColorTags() {
    }

    public static String opening(ChatColor color) {
        ChatColor resolved = color != null && color.isColor() ? color : ChatColor.WHITE;
        return '<' + canonicalName(resolved) + '>';
    }

    public static String apply(ChatColor color, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String name = canonicalName(color != null && color.isColor() ? color : ChatColor.WHITE);
        return '<' + name + '>' + escapeText(text) + "</" + name + '>';
    }

    public static String opening(String code) {
        return '<' + org.enthusia.rep.config.RepColor.miniMessageTag(code) + '>';
    }

    public static String apply(String code, String text) {
        String tag = org.enthusia.rep.config.RepColor.miniMessageTag(code);
        return '<' + tag + '>' + escapeText(text) + "</" + tag + '>';
    }

    private static String canonicalName(ChatColor color) {
        return color.name().toLowerCase(Locale.ROOT);
    }

    private static String escapeText(String text) {
        return text.replace("\\", "\\\\").replace("<", "\\<");
    }
}
