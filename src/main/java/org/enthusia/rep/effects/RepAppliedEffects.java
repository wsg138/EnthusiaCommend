package org.enthusia.rep.effects;

import org.bukkit.ChatColor;

public record RepAppliedEffects(
        int movementSpeedPercent,
        int potionDurationPercent,
        int fireworkDurationPercent,
        int pearlCooldownSeconds,
        int windCooldownSeconds,
        boolean glow,
        ChatColor glowColor,
        boolean stalkable,
        int cashbackPercent
) {
    public static final RepAppliedEffects NONE = new RepAppliedEffects(0, 0, 0, 0, 0, false, null, false, 0);

    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (movementSpeedPercent != 0) {
            sb.append("Movement: ").append(formatPercent(movementSpeedPercent)).append('\n');
        }
        if (potionDurationPercent != 0) {
            sb.append("Potion duration: ").append(formatPercent(potionDurationPercent)).append('\n');
        }
        if (fireworkDurationPercent != 0) {
            sb.append("Rocket flight duration: ").append(formatPercent(fireworkDurationPercent)).append('\n');
        }
        if (pearlCooldownSeconds > 0) {
            sb.append("Ender pearl cooldown: ").append(pearlCooldownSeconds).append("s\n");
        }
        if (windCooldownSeconds > 0) {
            sb.append("Wind charge cooldown: ").append(windCooldownSeconds).append("s\n");
        }
        if (glow) {
            sb.append("Glow: ").append(glowColor != null ? glowColor.name() : "WHITE").append('\n');
        }
        if (stalkable) {
            sb.append("Stalkable\n");
        }
        if (cashbackPercent > 0) {
            sb.append("Cashback: ").append(cashbackPercent).append("%\n");
        }
        if (sb.length() == 0) {
            return "You currently have no rep-based buffs or penalties.";
        }
        return sb.toString().trim();
    }

    private String formatPercent(int value) {
        return value > 0 ? "+" + value + "%" : value + "%";
    }
}
