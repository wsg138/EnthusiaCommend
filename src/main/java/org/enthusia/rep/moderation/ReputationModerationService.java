package org.enthusia.rep.moderation;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.enthusia.rep.api.ReputationBlacklist;
import org.enthusia.rep.api.ReputationModerationApi;
import org.enthusia.rep.api.ReputationMutationResult;
import org.enthusia.rep.api.ReputationStateSnapshot;

public final class ReputationModerationService implements ReputationModerationApi {
    private static final int MAX_OPERATIONS = 4096;

    private final Clock clock;
    private final Function<UUID, ReputationStateSnapshot> snapshotProvider;
    private final ReputationModerationStore store;
    private ReputationModerationStore.State state;

    public ReputationModerationService(
            Clock clock,
            Function<UUID, ReputationStateSnapshot> snapshotProvider,
            java.nio.file.Path stateFile
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.snapshotProvider = Objects.requireNonNull(snapshotProvider, "snapshotProvider");
        this.store = new ReputationModerationStore(Objects.requireNonNull(stateFile, "stateFile"));
        this.state = store.load();
    }

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    @Override
    public synchronized boolean isReputationBlacklisted(UUID playerId) {
        return getBlacklist(playerId).map(value -> value.activeAt(clock.instant())).orElse(false);
    }

    @Override
    public synchronized ReputationBlacklist blacklist(UUID playerId, Instant expirationAt, String caseId) {
        ReputationStateSnapshot before = snapshot(playerId);
        long revision = currentRevision(playerId);
        ReputationMutationResult result = applyBlacklist(
                UUID.randomUUID(), playerId, Optional.ofNullable(expirationAt), caseId, revision, before.checksum());
        if (!result.success() || result.blacklist().isEmpty()) {
            throw new IllegalStateException("Reputation blacklist failed: " + result.detail());
        }
        return result.blacklist().orElseThrow();
    }

    @Override
    public synchronized ReputationBlacklist blacklistPermanently(UUID playerId, String caseId) {
        ReputationStateSnapshot before = snapshot(playerId);
        long revision = currentRevision(playerId);
        ReputationMutationResult result = applyBlacklist(
                UUID.randomUUID(), playerId, Optional.empty(), caseId, revision, before.checksum());
        if (!result.success() || result.blacklist().isEmpty()) {
            throw new IllegalStateException("Permanent reputation blacklist failed: " + result.detail());
        }
        return result.blacklist().orElseThrow();
    }

    @Override
    public synchronized boolean removeBlacklist(UUID playerId, String caseId) {
        ReputationBlacklist current = state.blacklists().get(Objects.requireNonNull(playerId, "playerId"));
        if (current == null || current.status() == ReputationBlacklist.Status.REMOVED) {
            return false;
        }
        ReputationStateSnapshot before = snapshot(playerId);
        return removeBlacklist(
                UUID.randomUUID(), playerId, caseId, current.revision(), before.checksum()).success();
    }

    @Override
    public synchronized boolean canGiveReputation(UUID playerId) {
        return !isReputationBlacklisted(playerId);
    }

    @Override
    public synchronized Optional<ReputationBlacklist> getBlacklist(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        ReputationBlacklist value = state.blacklists().get(playerId);
        return value == null ? Optional.empty() : Optional.of(value.effectiveAt(clock.instant()));
    }

    @Override
    public synchronized ReputationStateSnapshot snapshot(UUID playerId) {
        ReputationStateSnapshot snapshot = Objects.requireNonNull(
                snapshotProvider.apply(Objects.requireNonNull(playerId, "playerId")),
                "snapshotProvider result"
        );
        if (!snapshot.playerId().equals(playerId)) {
            throw new IllegalStateException("snapshot provider returned a different player");
        }
        return snapshot;
    }

    @Override
    public synchronized ReputationMutationResult applyBlacklist(
            UUID operationId,
            UUID playerId,
            Optional<Instant> expirationAt,
            String caseId,
            long expectedBlacklistRevision,
            String expectedReputationChecksum
    ) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        expirationAt = Objects.requireNonNull(expirationAt, "expirationAt");
        if (expectedBlacklistRevision < 0L) {
            throw new IllegalArgumentException("expectedBlacklistRevision cannot be negative");
        }
        String normalizedCase = requireCaseId(caseId);
        String expectedChecksum = requireChecksum(expectedReputationChecksum);
        String fingerprint = "APPLY|" + playerId + '|' + expirationAt.map(Instant::toString).orElse("PERMANENT")
                + '|' + normalizedCase + '|' + expectedBlacklistRevision + '|' + expectedChecksum;
        ReputationMutationResult replay = replay(operationId, fingerprint);
        if (replay != null) {
            return replay;
        }
        ReputationStateSnapshot before = snapshot(playerId);
        if (!before.checksum().equals(expectedChecksum)) {
            return transientResult(ReputationMutationResult.Status.STALE_REPUTATION, before,
                    "Reputation changed after the moderation snapshot; retry from fresh state");
        }
        ReputationBlacklist previous = state.blacklists().get(playerId);
        long actualRevision = previous == null ? 0L : previous.revision();
        if (actualRevision != expectedBlacklistRevision) {
            return transientResult(ReputationMutationResult.Status.STALE_BLACKLIST, before,
                    "Blacklist state changed after it was read; retry from fresh state");
        }
        Instant now = clock.instant();
        if (expirationAt.isPresent() && !expirationAt.orElseThrow().isAfter(now)) {
            return transientResult(ReputationMutationResult.Status.REJECTED, before,
                    "Reputation blacklist expiration must be in the future");
        }
        long revision = Math.addExact(actualRevision, 1L);
        ReputationBlacklist blacklist = new ReputationBlacklist(
                playerId, now, expirationAt, normalizedCase, normalizedCase,
                ReputationBlacklist.Status.ACTIVE, revision, now);
        ReputationStateSnapshot after = snapshot(playerId);
        if (!after.checksum().equals(expectedChecksum)) {
            return driftResult(before, after);
        }
        return commit(operationId, fingerprint, ReputationMutationResult.Status.APPLIED,
                Optional.of(blacklist), before, after, "Reputation giving is blacklisted", blacklist);
    }

    @Override
    public synchronized ReputationMutationResult removeBlacklist(
            UUID operationId,
            UUID playerId,
            String caseId,
            long expectedBlacklistRevision,
            String expectedReputationChecksum
    ) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        if (expectedBlacklistRevision < 1L) {
            throw new IllegalArgumentException("expectedBlacklistRevision must be positive for removal");
        }
        String normalizedCase = requireCaseId(caseId);
        String expectedChecksum = requireChecksum(expectedReputationChecksum);
        String fingerprint = "REMOVE|" + playerId + '|' + normalizedCase + '|'
                + expectedBlacklistRevision + '|' + expectedChecksum;
        ReputationMutationResult replay = replay(operationId, fingerprint);
        if (replay != null) {
            return replay;
        }
        ReputationStateSnapshot before = snapshot(playerId);
        if (!before.checksum().equals(expectedChecksum)) {
            return transientResult(ReputationMutationResult.Status.STALE_REPUTATION, before,
                    "Reputation changed after the moderation snapshot; retry from fresh state");
        }
        ReputationBlacklist current = state.blacklists().get(playerId);
        if (current == null || current.revision() != expectedBlacklistRevision
                || current.status() == ReputationBlacklist.Status.REMOVED) {
            return transientResult(ReputationMutationResult.Status.STALE_BLACKLIST, before,
                    "Blacklist state changed after it was read; retry from fresh state");
        }
        Instant now = clock.instant();
        ReputationBlacklist removed = new ReputationBlacklist(
                playerId,
                current.startsAt(),
                current.expirationAt(),
                current.caseId(),
                normalizedCase,
                ReputationBlacklist.Status.REMOVED,
                Math.addExact(current.revision(), 1L),
                now
        );
        ReputationStateSnapshot after = snapshot(playerId);
        if (!after.checksum().equals(expectedChecksum)) {
            return driftResult(before, after);
        }
        return commit(operationId, fingerprint, ReputationMutationResult.Status.REMOVED,
                Optional.of(removed), before, after, "Reputation blacklist removed", removed);
    }

    private long currentRevision(UUID playerId) {
        ReputationBlacklist value = state.blacklists().get(playerId);
        return value == null ? 0L : value.revision();
    }

    private ReputationMutationResult replay(UUID operationId, String fingerprint) {
        ReputationModerationStore.Operation existing = state.operations().get(operationId);
        if (existing == null) {
            return null;
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            return new ReputationMutationResult(
                    ReputationMutationResult.Status.REJECTED,
                    existing.blacklist(),
                    existing.before(),
                    existing.after(),
                    "Operation ID was already used for different reputation moderation input"
            );
        }
        return new ReputationMutationResult(
                ReputationMutationResult.Status.REPLAYED,
                existing.blacklist(),
                existing.before(),
                existing.after(),
                "Idempotent replay of committed reputation moderation operation"
        );
    }

    private ReputationMutationResult transientResult(
            ReputationMutationResult.Status status,
            ReputationStateSnapshot snapshot,
            String detail
    ) {
        return new ReputationMutationResult(status, Optional.empty(), snapshot, snapshot, detail);
    }

    private ReputationMutationResult driftResult(
            ReputationStateSnapshot before,
            ReputationStateSnapshot after
    ) {
        return new ReputationMutationResult(
                ReputationMutationResult.Status.STALE_REPUTATION,
                Optional.empty(),
                before,
                after,
                "Reputation changed during blacklist reconciliation; retry from fresh state"
        );
    }

    private ReputationMutationResult commit(
            UUID operationId,
            String fingerprint,
            ReputationMutationResult.Status status,
            Optional<ReputationBlacklist> resultBlacklist,
            ReputationStateSnapshot before,
            ReputationStateSnapshot after,
            String detail,
            ReputationBlacklist persistedBlacklist
    ) {
        Map<UUID, ReputationBlacklist> blacklists = new LinkedHashMap<>(state.blacklists());
        blacklists.put(persistedBlacklist.playerId(), persistedBlacklist);
        LinkedHashMap<UUID, ReputationModerationStore.Operation> operations = new LinkedHashMap<>(state.operations());
        while (operations.size() >= MAX_OPERATIONS) {
            UUID oldestOperation = operations.keySet().iterator().next();
            operations.remove(oldestOperation);
        }
        operations.put(operationId, new ReputationModerationStore.Operation(
                operationId, fingerprint, status, resultBlacklist, before, after, detail));
        ReputationModerationStore.State candidate = new ReputationModerationStore.State(blacklists, operations);
        store.save(candidate);
        state = candidate;
        return new ReputationMutationResult(status, resultBlacklist, before, after, detail);
    }

    private static String requireCaseId(String value) {
        Objects.requireNonNull(value, "caseId");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw new IllegalArgumentException("caseId must contain 1 to 64 characters");
        }
        return normalized;
    }

    private static String requireChecksum(String value) {
        Objects.requireNonNull(value, "expectedReputationChecksum");
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expectedReputationChecksum must be SHA-256 hex");
        }
        return normalized;
    }
}
