package org.enthusia.rep.effects;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;

public record RepAppliedEffects(
        int movementSpeedPercent,
        int potionDurationPercent,
        int fireworkDurationPercent,
        int pearlCooldownSeconds,
        int windCooldownSeconds,
        boolean glow,
        ChatColor glowColor,
        boolean stalkable,
        double teleportCooldownMultiplier
) {
    public static final RepAppliedEffects NONE = new RepAppliedEffects(0, 0, 0, 0, 0, false, null, false, 1);

    public String describe() {
        List<String> descriptions = new ArrayList<>(8);
        addPercentDescription(descriptions, "Movement", movementSpeedPercent);
        addPercentDescription(descriptions, "Potion duration", potionDurationPercent);
        addPercentDescription(descriptions, "Rocket flight duration", fireworkDurationPercent);
        addSecondsDescription(descriptions, "Ender pearl cooldown", pearlCooldownSeconds);
        addSecondsDescription(descriptions, "Wind charge cooldown", windCooldownSeconds);
        addDescription(descriptions, glow, "Glow: " + (glowColor != null ? glowColor.name() : "WHITE"));
        addDescription(descriptions, stalkable, "Stalkable");
        if (teleportCooldownMultiplier != 1) descriptions.add("Teleport cooldown: " + Math.round(teleportCooldownMultiplier * 100) + "%");
        if (descriptions.isEmpty()) {
            return "You currently have no rep-based buffs or penalties.";
        }
        return String.join("\n", descriptions);
    }

    private static void addPercentDescription(List<String> descriptions, String label, int value) {
        if (value != 0) {
            descriptions.add(label + ": " + formatPercent(value));
        }
    }

    private static void addSecondsDescription(List<String> descriptions, String label, int value) {
        if (value > 0) {
            descriptions.add(label + ": " + value + "s");
        }
    }

    private static void addDescription(List<String> descriptions, boolean enabled, String description) {
        if (enabled) {
            descriptions.add(description);
        }
    }

    private static String formatPercent(int value) {
        return value > 0 ? "+" + value + "%" : value + "%";
    }
}
