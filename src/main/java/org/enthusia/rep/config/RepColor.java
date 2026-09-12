package org.enthusia.rep.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import java.util.Locale;

/** Converts configurable named, hex, or legacy colors into client color codes. */
public final class RepColor {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private RepColor() { }

    public static String code(String input, String fallback) {
        TextColor color = parse(input);
        if (color == null) color = parse(fallback);
        String encoded = LEGACY.serialize(Component.text("x", color));
        return encoded.substring(0, encoded.length() - 1);
    }

    private static TextColor parse(String input) {
        if (input == null) return null;
        String value = input.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("&#")) value = value.substring(1);
        TextColor hex = TextColor.fromHexString(value);
        if (hex != null) return hex;
        NamedTextColor named = NamedTextColor.NAMES.value(value);
        if (named != null) return named;
        return LegacyComponentSerializer.legacyAmpersand().deserialize(value + "x").color();
    }

    public static String miniMessageTag(String code) {
        TextColor color = LEGACY.deserialize(code + "x").color();
        if (color == null) return "white";
        String named = color instanceof NamedTextColor namedColor ? NamedTextColor.NAMES.key(namedColor) : null;
        return named == null ? color.asHexString().toLowerCase(Locale.ROOT) : named;
    }
}
