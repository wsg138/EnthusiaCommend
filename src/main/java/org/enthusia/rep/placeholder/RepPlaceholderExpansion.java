package org.enthusia.rep.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.enthusia.rep.config.RepConfig;
import org.enthusia.rep.rep.RepService;

public class RepPlaceholderExpansion extends PlaceholderExpansion {

    private final RepService repService;
    private final RepConfig config;

    public RepPlaceholderExpansion(RepService repService, RepConfig config) {
        this.repService = repService;
        this.config = config;
    }

    @Override
    public String getIdentifier() {
        return "enthusiarep";
    }

    @Override
    public String getAuthor() {
        return "Lincoln";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null || player.getUniqueId() == null) return "";
        int score = repService.getScore(player.getUniqueId());

        if (params.equalsIgnoreCase("score")) {
            return String.valueOf(score);
        }
        if (params.equalsIgnoreCase("score_colored")) {
            return config.formatColoredScore(score);
        }
        if (params.equalsIgnoreCase("score_raw")) {
            return Integer.toString(score);
        }
        if (params.equalsIgnoreCase("glowcolor")) {
            if (score <= -20) return "&c"; // red
            return "&f"; // white for all other scores
        }
        if (params.equalsIgnoreCase("color")) {
            return config.colorForScore(score).toString();
        }
        return null;
    }
}
