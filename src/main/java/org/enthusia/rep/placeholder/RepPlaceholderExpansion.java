package org.enthusia.rep.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.effects.RepAppliedEffects;

public final class RepPlaceholderExpansion extends PlaceholderExpansion {

    private final CommendPlugin plugin;

    public RepPlaceholderExpansion(CommendPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "enthusiarep";
    }

    @Override
    public String getAuthor() {
        return "Enthusia";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null || player.getUniqueId() == null) {
            return "";
        }
        int score = plugin.getRepService().getScore(player.getUniqueId());
        if (params.equalsIgnoreCase("score") || params.equalsIgnoreCase("score_raw")) {
            return Integer.toString(score);
        }
        if (params.equalsIgnoreCase("score_colored")) {
            return plugin.getRepConfig().formatColoredScore(score);
        }
        if (params.equalsIgnoreCase("color")) {
            return plugin.getRepConfig().colorForScore(score).toString();
        }
        if (params.equalsIgnoreCase("glowcolor")) {
            RepAppliedEffects effects = plugin.getRepConfig().resolveEffects(score);
            return effects.glowColor() != null ? effects.glowColor().toString() : "&f";
        }
        return null;
    }
}
