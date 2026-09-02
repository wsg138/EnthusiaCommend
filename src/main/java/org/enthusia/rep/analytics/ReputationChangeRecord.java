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
        if (raw == null) {
            return null;
        }
        try {
            String id = Objects.toString(raw.get("id"), UUID.randomUUID().toString());
            long timestamp = longValueOrDefault(raw.get("timestamp"), Instant.now().toEpochMilli());
            UUID targetId = requiredUuid(raw.get("target"));
            UUID actorId = optionalUuid(raw.get("actor"));
            String actorName = optionalText(raw.get("actorName"));
            int amount = requiredInt(raw.get("amount"));
            ReputationChangeAction action = requiredEnum(ReputationChangeAction.class, raw.get("action"));
            ReputationChangeSource source = requiredEnum(ReputationChangeSource.class, raw.get("source"));
            ReputationChangeOutcome outcome = enumOrDefault(
                    ReputationChangeOutcome.class,
                    raw.get("outcome"),
                    ReputationChangeOutcome.SUCCEEDED
            );
            String reason = Objects.toString(raw.get("reason"), "");
            RepCategory category = optionalEnum(RepCategory.class, raw.get("category"));
            int oldTotal = requiredInt(raw.get("oldTotal"));
            int newTotal = requiredInt(raw.get("newTotal"));
            return new ReputationChangeRecord(id, timestamp, targetId, actorId, actorName, amount, action, source, outcome, reason, category, oldTotal, newTotal);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long longValueOrDefault(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static int requiredInt(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static UUID requiredUuid(Object value) {
        return UUID.fromString(String.valueOf(value));
    }

    private static UUID optionalUuid(Object value) {
        return value == null ? null : requiredUuid(value);
    }

    private static String optionalText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static <E extends Enum<E>> E requiredEnum(Class<E> type, Object value) {
        return Enum.valueOf(type, String.valueOf(value));
    }

    private static <E extends Enum<E>> E optionalEnum(Class<E> type, Object value) {
        return value == null ? null : requiredEnum(type, value);
    }

    private static <E extends Enum<E>> E enumOrDefault(Class<E> type, Object value, E fallback) {
        return value == null ? fallback : requiredEnum(type, value);
    }
}
