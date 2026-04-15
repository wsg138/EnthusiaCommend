package org.enthusia.rep.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.enthusia.rep.effects.RepAppliedEffects;

import java.util.Locale;

public final class RepConfig {

    private final int minActivePlaytimeHours;
    private final long editCooldownMillis;
    private final InputMode defaultInputMode;
    private final int maxReasonLength;
    private final long inputTimeoutMillis;
    private final long autoSaveIntervalTicks;
    private final double stalkCostPerDay;
    private final int stalkMaxDays;
    private final String playtimePrimaryPlaceholder;
    private final String playtimeFallbackPlaceholder;
    private final EffectThresholds effectThresholds;

    public RepConfig(FileConfiguration config) {
        this.minActivePlaytimeHours = Math.max(0, config.getInt("rep.minActivePlaytimeHours", 12));
        this.editCooldownMillis = Math.max(0L, config.getLong("rep.editCooldownHours", 24L)) * 60L * 60L * 1000L;
        this.defaultInputMode = InputMode.from(config.getString("rep.input.default", "ANVIL"));
        this.maxReasonLength = Math.max(16, config.getInt("rep.input.maxReasonLength", 256));
        this.inputTimeoutMillis = Math.max(10L, config.getLong("rep.input.timeoutSeconds", 60L)) * 1000L;
        this.autoSaveIntervalTicks = Math.max(20L, config.getLong("storage.autoSaveSeconds", 60L)) * 20L;
        this.stalkCostPerDay = Math.max(0.0D, config.getDouble("stalk.costPerDay", config.getDouble("stalk.cost", 100.0D)));
        this.stalkMaxDays = Math.max(1, config.getInt("stalk.maxDays", 7));
        this.playtimePrimaryPlaceholder = config.getString("playtime.primaryPlaceholder", "%playtime_active%");
        this.playtimeFallbackPlaceholder = config.getString("playtime.fallbackFormattedPlaceholder", "%playtime_active_formatted%");
        this.effectThresholds = new EffectThresholds(config);
    }

    public int getMinActivePlaytimeHours() {
        return minActivePlaytimeHours;
    }

    public long getEditCooldownMillis() {
        return editCooldownMillis;
    }

    public InputMode getDefaultInputMode() {
        return defaultInputMode;
    }

    public int getMaxReasonLength() {
        return maxReasonLength;
    }

    public long getInputTimeoutMillis() {
        return inputTimeoutMillis;
    }

    public long getAutoSaveIntervalTicks() {
        return autoSaveIntervalTicks;
    }

    public double getStalkCostPerDay() {
        return stalkCostPerDay;
    }

    public int getStalkMaxDays() {
        return stalkMaxDays;
    }

    public String getPlaytimePrimaryPlaceholder() {
        return playtimePrimaryPlaceholder;
    }

    public String getPlaytimeFallbackPlaceholder() {
        return playtimeFallbackPlaceholder;
    }

    public EffectThresholds getEffectThresholds() {
        return effectThresholds;
    }

    public ChatColor colorForScore(int score) {
        if (score > 0) {
            return ChatColor.GREEN;
        }
        if (score < 0) {
            return ChatColor.RED;
        }
        return ChatColor.YELLOW;
    }

    public String formatColoredScore(int score) {
        return colorForScore(score) + String.valueOf(score);
    }

    public RepAppliedEffects resolveEffects(int score) {
        int movementSpeedPercent = 0;
        int potionDurationPercent = 0;
        int fireworkDurationPercent = 0;
        int pearlCooldownSeconds = 0;
        int windCooldownSeconds = 0;
        boolean glow = false;
        ChatColor glowColor = null;
        boolean stalkable = false;
        int cashbackPercent = 0;

        if (score <= effectThresholds.moveSpeedMinusOneAt) movementSpeedPercent = -1;
        if (score <= effectThresholds.pearlCooldownThreeSecondsAt) pearlCooldownSeconds = 3;
        if (score <= effectThresholds.fireworkDurationMinusFiveAt) fireworkDurationPercent = -5;
        if (score <= effectThresholds.moveSpeedMinusThreeAt) movementSpeedPercent = -3;
        if (score <= effectThresholds.windChargeCooldownTwoSecondsAt) windCooldownSeconds = 2;
        if (score <= effectThresholds.fireworkDurationMinusTenAt) fireworkDurationPercent = -10;
        if (score <= effectThresholds.moveSpeedMinusFiveAt) movementSpeedPercent = -5;
        if (score <= effectThresholds.glowAt) glow = true;
        if (score <= effectThresholds.stalkableAt) stalkable = true;
        if (score <= effectThresholds.potionDurationMinusTenAt) potionDurationPercent = -10;
        if (score <= effectThresholds.pearlCooldownSevenSecondsAt) pearlCooldownSeconds = 7;
        if (score <= effectThresholds.windChargeCooldownFiveSecondsAt) windCooldownSeconds = 5;
        if (score <= effectThresholds.fireworkDurationMinusFifteenAt) fireworkDurationPercent = -15;
        if (score <= effectThresholds.pearlCooldownTenSecondsAt) pearlCooldownSeconds = 10;
        if (score <= effectThresholds.windChargeCooldownTenSecondsAt) windCooldownSeconds = 10;
        if (score <= effectThresholds.moveSpeedMinusTenAt) movementSpeedPercent = -10;
        if (score <= effectThresholds.potionDurationMinusFifteenAt) potionDurationPercent = -15;
        if (score <= effectThresholds.fireworkDurationMinusTwentyFiveAt) fireworkDurationPercent = -25;
        if (score <= effectThresholds.redGlowAt) {
            glow = true;
            glowColor = ChatColor.RED;
        }

        if (score >= effectThresholds.moveSpeedPlusOneAt) movementSpeedPercent = 1;
        if (score >= effectThresholds.moveSpeedPlusThreeAt) movementSpeedPercent = 3;
        if (score >= effectThresholds.potionDurationPlusFiveAt) potionDurationPercent = 5;
        if (score >= effectThresholds.cashbackThreePercentAt) cashbackPercent = 3;
        if (score >= effectThresholds.moveSpeedPlusFiveAt) movementSpeedPercent = 5;
        if (score >= effectThresholds.potionDurationPlusTenAt) potionDurationPercent = 10;
        if (score >= effectThresholds.cashbackFivePercentAt) cashbackPercent = 5;

        return new RepAppliedEffects(
                movementSpeedPercent,
                potionDurationPercent,
                fireworkDurationPercent,
                pearlCooldownSeconds,
                windCooldownSeconds,
                glow,
                glowColor,
                stalkable,
                cashbackPercent
        );
    }

    public enum InputMode {
        ANVIL,
        CHAT;

        public static InputMode from(String raw) {
            if (raw == null) {
                return ANVIL;
            }
            try {
                return InputMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return ANVIL;
            }
        }
    }

    public static final class EffectThresholds {
        public final int moveSpeedMinusOneAt;
        public final int pearlCooldownThreeSecondsAt;
        public final int fireworkDurationMinusFiveAt;
        public final int moveSpeedMinusThreeAt;
        public final int windChargeCooldownTwoSecondsAt;
        public final int fireworkDurationMinusTenAt;
        public final int moveSpeedMinusFiveAt;
        public final int glowAt;
        public final int stalkableAt;
        public final int potionDurationMinusTenAt;
        public final int pearlCooldownSevenSecondsAt;
        public final int windChargeCooldownFiveSecondsAt;
        public final int fireworkDurationMinusFifteenAt;
        public final int pearlCooldownTenSecondsAt;
        public final int windChargeCooldownTenSecondsAt;
        public final int moveSpeedMinusTenAt;
        public final int potionDurationMinusFifteenAt;
        public final int fireworkDurationMinusTwentyFiveAt;
        public final int redGlowAt;
        public final int moveSpeedPlusOneAt;
        public final int moveSpeedPlusThreeAt;
        public final int potionDurationPlusFiveAt;
        public final int cashbackThreePercentAt;
        public final int moveSpeedPlusFiveAt;
        public final int potionDurationPlusTenAt;
        public final int cashbackFivePercentAt;

        private EffectThresholds(FileConfiguration config) {
            this.moveSpeedMinusOneAt = config.getInt("rep.effects.penalties.moveSpeedMinus1PercentAt", -5);
            this.pearlCooldownThreeSecondsAt = config.getInt("rep.effects.penalties.pearlCooldown3SecondsAt", -6);
            this.fireworkDurationMinusFiveAt = config.getInt("rep.effects.penalties.fireworkDurationMinus5PercentAt", -6);
            this.moveSpeedMinusThreeAt = config.getInt("rep.effects.penalties.moveSpeedMinus3PercentAt", -7);
            this.windChargeCooldownTwoSecondsAt = config.getInt("rep.effects.penalties.windChargeCooldown2SecondsAt", -7);
            this.fireworkDurationMinusTenAt = config.getInt("rep.effects.penalties.fireworkDurationMinus10PercentAt", -7);
            this.moveSpeedMinusFiveAt = config.getInt("rep.effects.penalties.moveSpeedMinus5PercentAt", -10);
            this.glowAt = config.getInt("rep.effects.penalties.glowAt", -10);
            this.stalkableAt = config.getInt("rep.effects.penalties.stalkableAt", -12);
            this.potionDurationMinusTenAt = config.getInt("rep.effects.penalties.potionDurationMinus10PercentAt", -12);
            this.pearlCooldownSevenSecondsAt = config.getInt("rep.effects.penalties.pearlCooldown7SecondsAt", -15);
            this.windChargeCooldownFiveSecondsAt = config.getInt("rep.effects.penalties.windChargeCooldown5SecondsAt", -15);
            this.fireworkDurationMinusFifteenAt = config.getInt("rep.effects.penalties.fireworkDurationMinus15PercentAt", -15);
            this.pearlCooldownTenSecondsAt = config.getInt("rep.effects.penalties.pearlCooldown10SecondsAt", -20);
            this.windChargeCooldownTenSecondsAt = config.getInt("rep.effects.penalties.windChargeCooldown10SecondsAt", -20);
            this.moveSpeedMinusTenAt = config.getInt("rep.effects.penalties.moveSpeedMinus10PercentAt", -20);
            this.potionDurationMinusFifteenAt = config.getInt("rep.effects.penalties.potionDurationMinus15PercentAt", -20);
            this.fireworkDurationMinusTwentyFiveAt = config.getInt("rep.effects.penalties.fireworkDurationMinus25PercentAt", -20);
            this.redGlowAt = config.getInt("rep.effects.penalties.redGlowAt", -20);
            this.moveSpeedPlusOneAt = config.getInt("rep.effects.benefits.moveSpeedPlus1PercentAt", 5);
            this.moveSpeedPlusThreeAt = config.getInt("rep.effects.benefits.moveSpeedPlus3PercentAt", 10);
            this.potionDurationPlusFiveAt = config.getInt("rep.effects.benefits.potionDurationPlus5PercentAt", 10);
            this.cashbackThreePercentAt = config.getInt("rep.effects.benefits.cashback3PercentAt", 10);
            this.moveSpeedPlusFiveAt = config.getInt("rep.effects.benefits.moveSpeedPlus5PercentAt", 15);
            this.potionDurationPlusTenAt = config.getInt("rep.effects.benefits.potionDurationPlus10PercentAt", 15);
            this.cashbackFivePercentAt = config.getInt("rep.effects.benefits.cashback5PercentAt", 15);
        }
    }
}
