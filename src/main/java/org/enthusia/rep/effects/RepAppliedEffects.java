// src/main/java/org/enthusia/commend/effects/RepAppliedEffects.java
package org.enthusia.rep.effects;

import org.bukkit.ChatColor;

public class RepAppliedEffects {
    public int movementSpeedPercent = 0;
    public int potionDurationPercent = 0;
    public int fireworkDurationPercent = 0;
    public int pearlCooldownSeconds = 0;
    public int windCooldownSeconds = 0;
    public boolean glow = false;
    public ChatColor glowColor = null;
    public boolean stalkable = false;
    public int cashbackPercent = 0;

    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (movementSpeedPercent != 0) {
            sb.append("Movement: ").append(formatPercent(movementSpeedPercent)).append("\n");
        }
        if (potionDurationPercent != 0) {
            sb.append("Potion duration: ").append(formatPercent(potionDurationPercent)).append("\n");
        }
        if (fireworkDurationPercent != 0) {
            sb.append("Firework penalty: ").append(formatPercent(fireworkDurationPercent)).append("\n");
        }
        if (pearlCooldownSeconds > 0) {
            sb.append("Ender pearl cooldown: ").append(pearlCooldownSeconds).append("s\n");
        }
        if (windCooldownSeconds > 0) {
            sb.append("Wind charge cooldown: ").append(windCooldownSeconds).append("s\n");
        }
        if (glow) {
            sb.append("Glowing in Spawn/Warzone\n");
        }
        if (stalkable) {
            sb.append("Stalkable: others can /commend stalk you\n");
        }
        if (cashbackPercent > 0) {
            sb.append("Cashback on eligible payments: ").append(cashbackPercent).append("%\n");
        }
        if (sb.length() == 0) {
            sb.append("You currently have no rep-based buffs or debuffs.");
        }
        return sb.toString().trim();
    }

    private String formatPercent(int value) {
        if (value > 0) return "+" + value + "%";
        return value + "%";
    }
}
