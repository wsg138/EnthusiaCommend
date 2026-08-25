package org.enthusia.rep.api;

import java.util.Objects;
import java.util.UUID;

public record ReputationEntrySnapshot(
        UUID giverId,
        UUID targetId,
        boolean positive,
        String category,
        int scoreValue,
        long createdAt,
        long lastEditedAt
) {
    public ReputationEntrySnapshot {
        Objects.requireNonNull(giverId, "giverId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(category, "category");
        category = category.trim();
        if (category.isEmpty() || category.length() > 64) {
            throw new IllegalArgumentException("category must contain 1 to 64 characters");
        }
        if (scoreValue == 0 || positive != (scoreValue > 0)) {
            throw new IllegalArgumentException("scoreValue sign must match reputation polarity");
        }
        if (createdAt < 0L || lastEditedAt < createdAt) {
            throw new IllegalArgumentException("reputation timestamps are invalid");
        }
    }
}
