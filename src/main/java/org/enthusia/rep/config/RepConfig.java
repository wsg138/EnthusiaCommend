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
    private final boolean planIntegrationEnabled;
    private final boolean planPageEnabled;
    private final int analyticsRetentionDays;
    private final int analyticsMaxRecords;
    private final String discordWebhookUrl;
    private final boolean repTradingAlertsEnabledByDefault;
    private final RepEffectRules effectRules;
    private final FileConfiguration settings;

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
        this.planIntegrationEnabled = config.getBoolean("integrations.plan.enabled", true);
        this.planPageEnabled = config.getBoolean("integrations.plan.page.enabled", true);
        this.analyticsRetentionDays = Math.max(1, config.getInt("analytics.retentionDays", 90));
        this.analyticsMaxRecords = Math.max(100, config.getInt("analytics.maxRecords", 5000));
        this.discordWebhookUrl = config.getString("discord.webhookUrl", "").trim();
        this.repTradingAlertsEnabledByDefault = config.getBoolean("rep-trading-alerts.enabled-by-default", true);
        this.effectRules = new RepEffectRules(config);
        this.settings = config;
    }

    public int getMinActivePlaytimeHours() { return minActivePlaytimeHours; }
    public long getEditCooldownMillis() { return editCooldownMillis; }
    public InputMode getDefaultInputMode() { return defaultInputMode; }
    public int getMaxReasonLength() { return maxReasonLength; }
    public long getInputTimeoutMillis() { return inputTimeoutMillis; }
    public long getAutoSaveIntervalTicks() { return autoSaveIntervalTicks; }
    public double getStalkCostPerDay() { return stalkCostPerDay; }
    public int getStalkMaxDays() { return stalkMaxDays; }
    public String getPlaytimePrimaryPlaceholder() { return playtimePrimaryPlaceholder; }
    public String getPlaytimeFallbackPlaceholder() { return playtimeFallbackPlaceholder; }
    public boolean isPlanIntegrationEnabled() { return planIntegrationEnabled; }
    public boolean isPlanPageEnabled() { return planPageEnabled; }
    public int getAnalyticsRetentionDays() { return analyticsRetentionDays; }
    public long getAnalyticsRetentionMillis() { return analyticsRetentionDays * 24L * 60L * 60L * 1000L; }
    public int getAnalyticsMaxRecords() { return analyticsMaxRecords; }
    public String getDiscordWebhookUrl() { return discordWebhookUrl; }
    public boolean areRepTradingAlertsEnabledByDefault() { return repTradingAlertsEnabledByDefault; }
    public long getRemovalCooldownMillis() { return Math.max(0L, settings.getLong("rep.removalCooldownHours", 24)) * 3_600_000L; }
    public boolean isIpProtectionEnabled() { return settings.getBoolean("rep.ipProtection.enabled", true); }
    public boolean requiresKnownAddresses() { return settings.getBoolean("rep.ipProtection.requireKnownAddresses", true); }
    public long getTarnishedMillis() { return Math.max(0L, settings.getLong("rep.tarnished.hours", 24)) * 3_600_000L; }
    public String getTarnishedLabel() { return settings.getString("rep.tarnished.label", "Tarnished"); }
    public ChatColor getTarnishedColor() {
        try { return ChatColor.valueOf(settings.getString("rep.tarnished.color", "GOLD").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return ChatColor.GOLD; }
    }
    public long getRecentWindowMillis(String window) {
        return Math.max(1L, settings.getLong("rep.recent." + window + "Hours", window.equals("week") ? 168 : 24)) * 3_600_000L;
    }

    public ChatColor colorForScore(int score) {
        if (score > 0) return ChatColor.GREEN;
        if (score < 0) return ChatColor.RED;
        return ChatColor.YELLOW;
    }

    public String formatColoredScore(int score) {
        return colorForScore(score).toString() + score;
    }

    public RepAppliedEffects resolveEffects(int score) {
        return resolveEffects(score, java.util.Map.of());
    }

    public RepAppliedEffects resolveEffects(int score, java.util.Map<org.enthusia.rep.rep.RepCategory, Integer> categories) {
        return effectRules.resolve(score, categories);
    }

    public boolean crossedEffectThreshold(int oldScore, int newScore) {
        return !resolveEffects(oldScore).equals(resolveEffects(newScore));
    }

    public enum InputMode {
        ANVIL,
        CHAT;

        public static InputMode from(String raw) {
            if (raw == null) return ANVIL;
            try {
                return InputMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return ANVIL;
            }
        }
    }

}
