package org.enthusia.rep.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.config.RepConfig;
import org.enthusia.rep.effects.RepAppliedEffects;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

public final class RepPlaceholderExpansion extends PlaceholderExpansion {

    private final Supplier<String> versionSupplier;
    private final Function<UUID, Integer> scoreLookup;
    private final Supplier<RepConfig> configSupplier;

    public RepPlaceholderExpansion(CommendPlugin plugin) {
        this(
                () -> plugin.getPluginMeta().getVersion(),
                playerId -> plugin.getRepService().getScore(playerId),
                plugin::getRepConfig
        );
    }

    RepPlaceholderExpansion(
            Supplier<String> versionSupplier,
            Function<UUID, Integer> scoreLookup,
            Supplier<RepConfig> configSupplier
    ) {
        this.versionSupplier = versionSupplier;
        this.scoreLookup = scoreLookup;
        this.configSupplier = configSupplier;
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
        return versionSupplier.get();
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
        Integer score = scoreLookup.apply(player.getUniqueId());
        RepConfig config = configSupplier.get();
        if (score == null || config == null) {
            return "";
        }
        return resolvePlaceholder(score, params, config);
    }

    static String resolvePlaceholder(int score, String params, RepConfig config) {
        if (params == null) {
            return null;
        }
        String identifier = params.toLowerCase(Locale.ROOT);
        return switch (identifier) {
            case "score", "score_raw" -> Integer.toString(score);
            case "score_colored" -> config.formatColoredScore(score);
            case "color" -> config.colorForScore(score).toString();
            case "glowcolor" -> legacyGlowColor(config.resolveEffects(score));
            case "score_mm" -> MiniMessageColorTags.apply(config.colorForScore(score), Integer.toString(score));
            case "color_mm" -> MiniMessageColorTags.opening(config.colorForScore(score));
            case "glowcolor_mm" -> MiniMessageColorTags.opening(resolvedGlowColor(config.resolveEffects(score)));
            default -> null;
        };
    }

    private static String legacyGlowColor(RepAppliedEffects effects) {
        return effects.glowColor() != null ? effects.glowColor().toString() : "&f";
    }

    private static ChatColor resolvedGlowColor(RepAppliedEffects effects) {
        return effects.glowColor() != null ? effects.glowColor() : ChatColor.WHITE;
    }
}
