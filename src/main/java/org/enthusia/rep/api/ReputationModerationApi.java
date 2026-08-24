package org.enthusia.rep.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Stable moderation service registered with Bukkit's ServicesManager.
 *
 * <p>Blacklist operations are idempotent by operation ID and preserve reputation entries and
 * score. Callers must snapshot immediately before a mutation and pass that checksum back as a
 * compare-and-set guard. Every mutation also requires the exact blacklist revision observed by
 * the caller, using revision {@code 0} when no blacklist record exists.</p>
 */
public interface ReputationModerationApi {
    int API_VERSION = 2;

    int apiVersion();

    boolean isReputationBlacklisted(UUID playerId);

    ReputationBlacklist blacklist(UUID playerId, Instant expirationAt, String caseId);

    ReputationBlacklist blacklistPermanently(UUID playerId, String caseId);

    boolean removeBlacklist(UUID playerId, String caseId);

    boolean canGiveReputation(UUID playerId);

    Optional<ReputationBlacklist> getBlacklist(UUID playerId);

    ReputationStateSnapshot snapshot(UUID playerId);

    ReputationMutationResult applyBlacklist(
            UUID operationId,
            UUID playerId,
            Optional<Instant> expirationAt,
            String caseId,
            long expectedBlacklistRevision,
            String expectedReputationChecksum
    );

    ReputationMutationResult removeBlacklist(
            UUID operationId,
            UUID playerId,
            String caseId,
            long expectedBlacklistRevision,
            String expectedReputationChecksum
    );
}
