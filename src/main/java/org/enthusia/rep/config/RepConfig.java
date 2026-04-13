// src/main/java/org/enthusia/commend/config/RepConfig.java
package org.enthusia.rep.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;

public class RepConfig {

    private final int minActivePlaytimeHours;
    private final long editCooldownMillis;
    private final InputMode inputMode;

    private final double stalkCost;
    private final long stalkDurationMillis;

    // Map: threshold -> tier data
    private final NavigableMap<Integer, RepTierConfig> negativeTiers = new TreeMap<>(Collections.reverseOrder());
    private final NavigableMap<Integer, RepTierConfig> positiveTiers = new TreeMap<>();

    public RepConfig(FileConfiguration config) {
        this.minActivePlaytimeHours = config.getInt("rep.minActivePlaytimeHours", 12);
        int editCooldownHours = config.getInt("rep.editCooldownHours", 24);
        this.editCooldownMillis = editCooldownHours * 60L * 60L * 1000L;
        this.inputMode = InputMode.from(config.getString("rep.inputMode", "ANVIL"));

        this.stalkCost = config.getDouble("stalk.cost", 100.0);
        int stalkDays = config.getInt("stalk.durationDays", 1);
        this.stalkDurationMillis = stalkDays * 24L * 60L * 60L * 1000L;

        loadTiers(config);
    }

    private void loadTiers(FileConfiguration config) {
        // Negative tiers
        ConfigurationSection neg = config.getConfigurationSection("rep.negative");
        if (neg != null) {
            for (String key : neg.getKeys(false)) {
                int threshold = Integer.parseInt(key);
                ConfigurationSection sec = neg.getConfigurationSection(key);
                negativeTiers.put(threshold, RepTierConfig.fromSection(sec));
            }
        }

        // Positive tiers
        ConfigurationSection pos = config.getConfigurationSection("rep.positive");
        if (pos != null) {
            for (String key : pos.getKeys(false)) {
                int threshold = Integer.parseInt(key);
                ConfigurationSection sec = pos.getConfigurationSection(key);
                positiveTiers.put(threshold, RepTierConfig.fromSection(sec));
            }
        }
    }

    public int getMinActivePlaytimeHours() {
        return minActivePlaytimeHours;
    }

    public long getEditCooldownMillis() {
        return editCooldownMillis;
    }

    public InputMode getInputMode() {
        return inputMode;
    }

    public double getStalkCost() {
        return stalkCost;
    }

    public long getStalkDurationMillis() {
        return stalkDurationMillis;
    }

    public NavigableMap<Integer, RepTierConfig> getNegativeTiers() {
        return negativeTiers;
    }

    public NavigableMap<Integer, RepTierConfig> getPositiveTiers() {
        return positiveTiers;
    }

    public ChatColor colorForScore(int score) {
        if (score > 0) return ChatColor.GREEN;
        if (score == 0) return ChatColor.YELLOW;
        return ChatColor.RED;
    }

    public String formatColoredScore(int score) {
        ChatColor color = colorForScore(score);
        return color + String.valueOf(score);
    }

    public enum InputMode {
        ANVIL,
        CHAT,
        BOOK;

        public static InputMode from(String raw) {
            if (raw == null) return ANVIL;
            try {
                return InputMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return ANVIL;
            }
        }
    }

    public static class RepTierConfig {
        public final Integer movementSpeedPercent;
        public final Integer potionDurationPercent;
        public final Integer fireworkDurationPercent;
        public final Integer pearlCooldownSeconds;
        public final Integer windCooldownSeconds;
        public final Boolean glow;
        public final ChatColor glowColor;
        public final Boolean stalkable;
        public final Integer cashbackPercent;

        private RepTierConfig(Integer movementSpeedPercent,
                              Integer potionDurationPercent,
                              Integer fireworkDurationPercent,
                              Integer pearlCooldownSeconds,
                              Integer windCooldownSeconds,
                              Boolean glow,
                              ChatColor glowColor,
                              Boolean stalkable,
                              Integer cashbackPercent) {
            this.movementSpeedPercent = movementSpeedPercent;
            this.potionDurationPercent = potionDurationPercent;
            this.fireworkDurationPercent = fireworkDurationPercent;
            this.pearlCooldownSeconds = pearlCooldownSeconds;
            this.windCooldownSeconds = windCooldownSeconds;
            this.glow = glow;
            this.glowColor = glowColor;
            this.stalkable = stalkable;
            this.cashbackPercent = cashbackPercent;
        }

        public static RepTierConfig fromSection(ConfigurationSection sec) {
            if (sec == null) return new RepTierConfig(null, null, null, null, null, null, null, null, null);
            Integer move = sec.isSet("movement_speed_percent") ? sec.getInt("movement_speed_percent") : null;
            Integer potion = sec.isSet("potion_duration_percent") ? sec.getInt("potion_duration_percent") : null;
            Integer firework = sec.isSet("firework_duration_percent") ? sec.getInt("firework_duration_percent") : null;
            Integer pearl = sec.isSet("pearl_cooldown_seconds") ? sec.getInt("pearl_cooldown_seconds") : null;
            Integer wind = sec.isSet("windcharge_cooldown_seconds") ? sec.getInt("windcharge_cooldown_seconds") : null;
            Boolean glow = sec.isSet("glow") ? sec.getBoolean("glow") : null;
            ChatColor glowColor = null;
            if (sec.isSet("glow_color")) {
                try {
                    glowColor = ChatColor.valueOf(sec.getString("glow_color", "WHITE").toUpperCase());
                } catch (IllegalArgumentException ignored) {
                }
            }
            Boolean stalkable = sec.isSet("stalkable") ? sec.getBoolean("stalkable") : null;
            Integer cashback = sec.isSet("cashback_percent") ? sec.getInt("cashback_percent") : null;
            return new RepTierConfig(move, potion, firework, pearl, wind, glow, glowColor, stalkable, cashback);
        }
    }
}
