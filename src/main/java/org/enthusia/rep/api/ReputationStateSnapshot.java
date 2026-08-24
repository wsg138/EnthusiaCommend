package org.enthusia.rep.api;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record ReputationStateSnapshot(
        UUID playerId,
        int totalScore,
        List<ReputationEntrySnapshot> entries,
        String checksum
) {
    public ReputationStateSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (entries.stream().anyMatch(entry -> !entry.targetId().equals(playerId))) {
            throw new IllegalArgumentException("every reputation entry must belong to playerId");
        }
        checksum = Objects.requireNonNull(checksum, "checksum").toLowerCase(Locale.ROOT);
        if (!checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checksum must be a lowercase SHA-256 hex value");
        }
    }
}
