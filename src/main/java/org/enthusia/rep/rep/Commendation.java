package org.enthusia.rep.rep;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Commendation {
    private final UUID giver;
    private final UUID target;
    private boolean positive;
    private RepCategory category;
    private String reasonText;
    private long createdAt;
    private long lastEditedAt;
    private String ipHash;

    public Commendation(UUID giver,
                        UUID target,
                        boolean positive,
                        RepCategory category,
                        String reasonText,
                        long createdAt,
                        long lastEditedAt,
                        String ipHash) {
        this.giver = giver;
        this.target = target;
        this.positive = positive;
        this.category = category;
        this.reasonText = reasonText;
        this.createdAt = createdAt;
        this.lastEditedAt = lastEditedAt;
        this.ipHash = ipHash;
    }

    public UUID getGiver() {
        return giver;
    }

    public UUID getTarget() {
        return target;
    }

    public boolean isPositive() {
        return positive;
    }

    public RepCategory getCategory() {
        return category;
    }

    public String getReasonText() {
        return reasonText;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastEditedAt() {
        return lastEditedAt;
    }

    public String getIpHash() {
        return ipHash;
    }

    public void setPositive(boolean positive) {
        this.positive = positive;
    }

    public void setCategory(RepCategory category) {
        this.category = category;
    }

    public void setReasonText(String reasonText) {
        this.reasonText = reasonText;
    }

    public void setLastEditedAt(long lastEditedAt) {
        this.lastEditedAt = lastEditedAt;
    }

    public void setIpHash(String ipHash) {
        this.ipHash = ipHash;
    }

    public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();
        map.put("giver", giver.toString());
        map.put("target", target.toString());
        map.put("positive", positive);
        map.put("category", category.name());
        map.put("reason", reasonText);
        map.put("createdAt", createdAt);
        map.put("lastEditedAt", lastEditedAt);
        if (ipHash != null) {
            map.put("ipHash", ipHash);
        }
        return map;
    }

    public static Commendation fromSection(ConfigurationSection sec) {
        if (sec == null) return null;
        try {
            UUID giver = UUID.fromString(sec.getString("giver"));
            UUID target = UUID.fromString(sec.getString("target"));
            boolean positive = sec.getBoolean("positive", true);
            RepCategory category = RepCategory.valueOf(sec.getString("category", RepCategory.OTHER_POSITIVE.name()));
            String reason = sec.getString("reason", "");
            long createdAt = sec.getLong("createdAt", System.currentTimeMillis());
            long lastEditedAt = sec.getLong("lastEditedAt", createdAt);
            String ipHash = sec.getString("ipHash", null);
            return new Commendation(giver, target, positive, category, reason, createdAt, lastEditedAt, ipHash);
        } catch (Exception ex) {
            return null;
        }
    }
}
