package org.enthusia.rep.discord;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** URL-only head resolution; it never performs a skin network request. */
public final class MinecraftHeadUrl {
    private static final String BASE = "https://mc-heads.net/avatar/";

    private MinecraftHeadUrl() { }

    public static String resolve(UUID uuid, String username) {
        String cleanedName = username == null ? "" : username.trim();
        // Floodgate names use the configured prefix in this project. Their synthetic UUIDs
        // are not Mojang profile UUIDs, so the username fallback degrades more gracefully.
        if (cleanedName.startsWith("*") && cleanedName.length() > 1) {
            return usernameUrl(cleanedName.substring(1));
        }
        if (uuid != null) {
            return BASE + uuid.toString().replace("-", "") + "/64";
        }
        return cleanedName.isBlank() ? null : usernameUrl(cleanedName);
    }

    private static String usernameUrl(String username) {
        return BASE + URLEncoder.encode(username, StandardCharsets.UTF_8) + "/64";
    }
}
