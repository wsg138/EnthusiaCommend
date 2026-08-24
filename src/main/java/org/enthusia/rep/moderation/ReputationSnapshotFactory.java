package org.enthusia.rep.moderation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.enthusia.rep.api.ReputationStateSnapshot;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepService;

public final class ReputationSnapshotFactory {
    private ReputationSnapshotFactory() {
    }

    public static ReputationStateSnapshot snapshot(RepService service, UUID playerId) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(playerId, "playerId");
        List<ReputationEntrySnapshot> entries = service.getCommendationSnapshotsAbout(playerId).stream()
                .map(ReputationSnapshotFactory::entry)
                .sorted(Comparator.comparing((ReputationEntrySnapshot value) -> value.giverId().toString())
                        .thenComparingLong(ReputationEntrySnapshot::createdAt)
                        .thenComparing(ReputationEntrySnapshot::category))
                .toList();
        int totalScore = service.getScore(playerId);
        return new ReputationStateSnapshot(playerId, totalScore, entries, checksum(playerId, totalScore, entries));
    }

    private static ReputationEntrySnapshot entry(Commendation value) {
        return new ReputationEntrySnapshot(
                value.getGiver(),
                value.getTarget(),
                value.isPositive(),
                value.getCategory().name(),
                value.getScoreValue(),
                value.getCreatedAt(),
                value.getLastEditedAt()
        );
    }

    static String checksum(UUID playerId, int totalScore, List<ReputationEntrySnapshot> entries) {
        StringBuilder canonical = new StringBuilder(playerId.toString()).append('|').append(totalScore);
        for (ReputationEntrySnapshot entry : entries) {
            canonical.append('\n')
                    .append(entry.giverId()).append('|')
                    .append(entry.targetId()).append('|')
                    .append(entry.positive()).append('|')
                    .append(entry.category()).append('|')
                    .append(entry.scoreValue()).append('|')
                    .append(entry.createdAt()).append('|')
                    .append(entry.lastEditedAt());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
