package org.enthusia.rep.playtime;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.enthusia.rep.CommendPlugin;

/**
 * Fetches active playtime (in hours) using PlaceholderAPI placeholders.
 * Falls back to 0 if PAPI or the placeholder is unavailable.
 */
public class PlaytimeService {

    private final CommendPlugin plugin;

    public PlaytimeService(CommendPlugin plugin) {
        this.plugin = plugin;
    }

    public double getActiveHours(OfflinePlayer player) {
        if (player == null) return 0.0;
        Player online = player.getPlayer();
        if (online == null) return 0.0;
        try {
            String raw = PlaceholderAPI.setPlaceholders(online, "%playtime_active%");
            double seconds = Double.parseDouble(raw);
            return seconds / 3600.0;
        } catch (Exception ex) {
            try {
                String formatted = PlaceholderAPI.setPlaceholders(online, "%playtime_active_formatted%");
                // formatted like "15h 3m" or "12h"
                return parseFormatted(formatted);
            } catch (Exception ignored) {
                return 0.0;
            }
        }
    }

    private double parseFormatted(String formatted) {
        if (formatted == null) return 0.0;
        double hours = 0.0;
        String[] parts = formatted.split("\\s+");
        for (String p : parts) {
            if (p.endsWith("h")) {
                try {
                    hours += Double.parseDouble(p.replace("h", ""));
                } catch (NumberFormatException ignored) {}
            } else if (p.endsWith("m")) {
                try {
                    hours += Double.parseDouble(p.replace("m", "")) / 60.0;
                } catch (NumberFormatException ignored) {}
            }
        }
        return hours;
    }
}
