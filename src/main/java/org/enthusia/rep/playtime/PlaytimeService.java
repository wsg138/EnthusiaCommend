package org.enthusia.rep.playtime;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.enthusia.rep.config.RepConfig;

public final class PlaytimeService {

    private volatile RepConfig config;

    public PlaytimeService(RepConfig config) {
        this.config = config;
    }

    public void reload(RepConfig config) {
        this.config = config;
    }

    public boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    public double getActiveHours(OfflinePlayer player) {
        if (player == null || !isAvailable()) {
            return 0.0D;
        }

        Player online = player.getPlayer();
        if (online == null) {
            return 0.0D;
        }

        String rawPrimary = PlaceholderAPI.setPlaceholders(online, config.getPlaytimePrimaryPlaceholder());
        Double seconds = parseDouble(rawPrimary);
        if (seconds != null) {
            return seconds / 3600.0D;
        }

        String rawFallback = PlaceholderAPI.setPlaceholders(online, config.getPlaytimeFallbackPlaceholder());
        return parseFormattedHours(rawFallback);
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank() || value.contains("%")) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private double parseFormattedHours(String formatted) {
        if (formatted == null || formatted.isBlank()) {
            return 0.0D;
        }

        double hours = 0.0D;
        for (String token : formatted.split("\\s+")) {
            if (token.endsWith("h")) {
                hours += safeNumber(token.substring(0, token.length() - 1));
            } else if (token.endsWith("m")) {
                hours += safeNumber(token.substring(0, token.length() - 1)) / 60.0D;
            } else if (token.endsWith("s")) {
                hours += safeNumber(token.substring(0, token.length() - 1)) / 3600.0D;
            }
        }
        return hours;
    }

    private double safeNumber(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return 0.0D;
        }
    }
}
