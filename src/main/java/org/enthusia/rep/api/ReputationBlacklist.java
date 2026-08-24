package org.enthusia.rep.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ReputationBlacklist(
        UUID playerId,
        Instant startsAt,
        Optional<Instant> expirationAt,
        String caseId,
        String lastActionCaseId,
        Status status,
        long revision,
        Instant updatedAt
) {
    public ReputationBlacklist {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(startsAt, "startsAt");
        expirationAt = Objects.requireNonNull(expirationAt, "expirationAt");
        caseId = requireCaseId(caseId);
        lastActionCaseId = requireCaseId(lastActionCaseId);
        Objects.requireNonNull(status, "status");
        if (revision < 1L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean activeAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return status == Status.ACTIVE
                && expirationAt.map(expiration -> expiration.isAfter(now)).orElse(true);
    }

    public ReputationBlacklist effectiveAt(Instant now) {
        return status == Status.ACTIVE && !activeAt(now)
                ? new ReputationBlacklist(
                        playerId,
                        startsAt,
                        expirationAt,
                        caseId,
                        lastActionCaseId,
                        Status.EXPIRED,
                        revision,
                        updatedAt
                )
                : this;
    }

    private static String requireCaseId(String value) {
        Objects.requireNonNull(value, "caseId");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw new IllegalArgumentException("caseId must contain 1 to 64 characters");
        }
        return normalized;
    }

    public enum Status {
        ACTIVE,
        EXPIRED,
        REMOVED
    }
}
