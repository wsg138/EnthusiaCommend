package org.enthusia.rep.rep;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class Commendation {
    private final UUID giver;
    private final UUID target;
    private boolean positive;
    private RepCategory category;
    private String reasonText;
    private final long createdAt;
    private long lastEditedAt;
    private String ipHash;
    private int scoreValue;

    /**
     * Compatibility constructor for existing code and legacy persisted entries.
     * Negative entries created through this constructor retain the historical -1
     * value. New entries should use the constructor that accepts scoreValue.
     */
    public Commendation(UUID giver,
                        UUID target,
                        boolean positive,
                        RepCategory category,
                        String reasonText,
                        long createdAt,
                        long lastEditedAt,
                        String ipHash) {
        this(giver, target, positive, category, reasonText, createdAt, lastEditedAt, ipHash, positive ? 1 : -1);
    }

    public Commendation(UUID giver,
                        UUID target,
                        boolean positive,
                        RepCategory category,
                        String reasonText,
                        long createdAt,
                        long lastEditedAt,
                        String ipHash,
                        int scoreValue) {
        this.giver = giver;
        this.target = target;
        this.positive = positive;
        this.category = category == null ? (positive ? RepCategory.WAS_KIND : RepCategory.SCAMMED) : category.migratedCategory();
        this.reasonText = reasonText == null ? "" : reasonText;
        this.createdAt = createdAt;
        this.lastEditedAt = lastEditedAt;
        this.ipHash = ipHash;
        this.scoreValue = normalizeScoreValue(positive, scoreValue);
    }

    public UUID getGiver() { return giver; }
    public UUID getTarget() { return target; }
    public boolean isPositive() { return positive; }
    public RepCategory getCategory() { return category; }
    public String getReasonText() { return reasonText; }
    public long getCreatedAt() { return createdAt; }
    public long getLastEditedAt() { return lastEditedAt; }
    public String getIpHash() { return ipHash; }
    public int getScoreValue() { return scoreValue; }

    public synchronized void setPositive(boolean positive) { this.positive = positive; }
    public synchronized void setCategory(RepCategory category) {
        this.category = category == null ? (positive ? RepCategory.WAS_KIND : RepCategory.SCAMMED) : category.migratedCategory();
    }
    public synchronized void setReasonText(String reasonText) { this.reasonText = reasonText == null ? "" : reasonText; }
    public synchronized void setLastEditedAt(long lastEditedAt) { this.lastEditedAt = lastEditedAt; }
    public synchronized void setIpHash(String ipHash) { this.ipHash = ipHash; }
    public synchronized void setScoreValue(int scoreValue) { this.scoreValue = normalizeScoreValue(positive, scoreValue); }

    public synchronized int applyUpdate(boolean newPositive, RepCategory newCategory, String newReasonText,
                                        long newLastEditedAt, String newIpHash) {
        int oldValue = scoreValue;
        boolean polarityChanged = positive != newPositive;
        RepCategory normalizedCategory = newCategory == null
                ? (newPositive ? RepCategory.WAS_KIND : RepCategory.SCAMMED)
                : newCategory.migratedCategory();
        int newValue = polarityChanged ? normalizedCategory.defaultScoreValue() : oldValue;
        positive = newPositive;
        category = normalizedCategory;
        reasonText = newReasonText == null ? "" : newReasonText;
        lastEditedAt = newLastEditedAt;
        ipHash = newIpHash;
        scoreValue = normalizeScoreValue(newPositive, newValue);
        return scoreValue - oldValue;
    }

    public synchronized Commendation snapshot() {
        return new Commendation(giver, target, positive, category, reasonText,
                createdAt, lastEditedAt, ipHash, scoreValue);
    }

    public synchronized Map<String, Object> serialize() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("giver", giver.toString());
        map.put("target", target.toString());
        map.put("positive", positive);
        map.put("category", category.name());
        map.put("reason", reasonText);
        map.put("createdAt", createdAt);
        map.put("lastEditedAt", lastEditedAt);
        map.put("scoreValue", scoreValue);
        if (ipHash != null) {
            map.put("ipHash", ipHash);
        }
        return map;
    }

    public static Commendation fromSection(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        try {
            UUID giver = UUID.fromString(section.getString("giver"));
            UUID target = UUID.fromString(section.getString("target"));
            boolean positive = section.getBoolean("positive", true);
            RepCategory category = RepCategory.fromStored(section.getString("category"), positive);
            String reason = section.getString("reason", "");
            long createdAt = section.getLong("createdAt", System.currentTimeMillis());
            long lastEditedAt = section.getLong("lastEditedAt", createdAt);
            String ipHash = section.getString("ipHash", null);
            int scoreValue = section.isSet("scoreValue")
                    ? section.getInt("scoreValue")
                    : positive ? 1 : -1;
            return new Commendation(giver, target, positive, category, reason, createdAt, lastEditedAt, ipHash, scoreValue);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int normalizeScoreValue(boolean positive, int value) {
        if (positive) {
            return value > 0 ? value : 1;
        }
        return value < 0 ? value : -1;
    }
}
