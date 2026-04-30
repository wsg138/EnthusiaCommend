package org.enthusia.rep.analytics;

import org.enthusia.rep.rep.RepCategory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ReputationChangeRecord(
        String id,
        long timestamp,
        UUID targetId,
        UUID actorId,
        String actorName,
        int amount,
        ReputationChangeAction action,
        ReputationChangeSource source,
        ReputationChangeOutcome outcome,
        String reason,
        RepCategory category,
        int oldTotal,
        int newTotal
) {
    public Map<String, Object> serialize() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("timestamp", timestamp);
        map.put("target", targetId.toString());
        if (actorId != null) {
            map.put("actor", actorId.toString());
        }
        if (actorName != null && !actorName.isBlank()) {
            map.put("actorName", actorName);
        }
        map.put("amount", amount);
        map.put("action", action.name());
        map.put("source", source.name());
        map.put("outcome", outcome.name());
        map.put("reason", reason == null ? "" : reason);
        if (category != null) {
            map.put("category", category.name());
        }
        map.put("oldTotal", oldTotal);
        map.put("newTotal", newTotal);
        return map;
    }

    public static ReputationChangeRecord fromMap(Map<?, ?> raw) {
        try {
            String id = Objects.toString(raw.get("id"), UUID.randomUUID().toString());
            long timestamp = raw.get("timestamp") instanceof Number value ? value.longValue() : Instant.now().toEpochMilli();
            UUID targetId = UUID.fromString(String.valueOf(raw.get("target")));
            UUID actorId = null;
            if (raw.get("actor") != null) {
                actorId = UUID.fromString(String.valueOf(raw.get("actor")));
            }
            String actorName = raw.get("actorName") != null ? String.valueOf(raw.get("actorName")) : null;
            int amount = raw.get("amount") instanceof Number value ? value.intValue() : Integer.parseInt(String.valueOf(raw.get("amount")));
            ReputationChangeAction action = ReputationChangeAction.valueOf(String.valueOf(raw.get("action")));
            ReputationChangeSource source = ReputationChangeSource.valueOf(String.valueOf(raw.get("source")));
            Object rawOutcome = raw.get("outcome");
            ReputationChangeOutcome outcome = ReputationChangeOutcome.valueOf(rawOutcome != null ? String.valueOf(rawOutcome) : ReputationChangeOutcome.SUCCEEDED.name());
            String reason = Objects.toString(raw.get("reason"), "");
            RepCategory category = raw.get("category") != null ? RepCategory.valueOf(String.valueOf(raw.get("category"))) : null;
            int oldTotal = raw.get("oldTotal") instanceof Number value ? value.intValue() : Integer.parseInt(String.valueOf(raw.get("oldTotal")));
            int newTotal = raw.get("newTotal") instanceof Number value ? value.intValue() : Integer.parseInt(String.valueOf(raw.get("newTotal")));
            return new ReputationChangeRecord(id, timestamp, targetId, actorId, actorName, amount, action, source, outcome, reason, category, oldTotal, newTotal);
        } catch (Exception ignored) {
            return null;
        }
    }
}
