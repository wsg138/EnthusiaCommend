package org.enthusia.rep.moderation;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.enthusia.rep.api.ReputationBlacklist;
import org.enthusia.rep.api.ReputationModerationApi;
import org.enthusia.rep.api.ReputationMutationResult;
import org.enthusia.rep.api.ReputationStateSnapshot;

public final class ReputationModerationService implements ReputationModerationApi {
    private static final int MAX_OPERATIONS = 4096;
    private static final long NO_BLACKLIST_REVISION = 0L;
    private static final long FIRST_BLACKLIST_REVISION = 1L;
    private static final long REVISION_INCREMENT = 1L;
    private static final String PLAYER_ID_ARGUMENT = "playerId";

    private final Clock clock;
    private final Function<UUID, ReputationStateSnapshot> snapshotProvider;
    private final ReputationModerationStore store;
    private final Object stateLock = new Object();
    private final Set<UUID> storageFailureFences = new HashSet<>();
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
    public boolean isReputationBlacklisted(UUID playerId) {
        synchronized (stateLock) {
            return getBlacklistLocked(playerId).map(value -> value.activeAt(clock.instant())).orElse(false);
        }
    }

    @Override
    public ReputationBlacklist blacklist(UUID playerId, Instant expirationAt, String caseId) {
        synchronized (stateLock) {
            Instant requiredExpiration = Objects.requireNonNull(expirationAt, "expirationAt");
            ReputationStateSnapshot before = snapshotLocked(playerId);
            long revision = currentRevision(playerId);
            ReputationMutationResult result = applyBlacklistLocked(
                    UUID.randomUUID(), playerId, Optional.of(requiredExpiration), caseId, revision, before.checksum());
            if (!result.success() || result.blacklist().isEmpty()) {
                throw new IllegalStateException("Reputation blacklist failed: " + result.detail());
            }
            return result.blacklist().orElseThrow();
        }
    }

    @Override
    public ReputationBlacklist blacklistPermanently(UUID playerId, String caseId) {
        synchronized (stateLock) {
            ReputationStateSnapshot before = snapshotLocked(playerId);
            long revision = currentRevision(playerId);
            ReputationMutationResult result = applyBlacklistLocked(
                    UUID.randomUUID(), playerId, Optional.empty(), caseId, revision, before.checksum());
            if (!result.success() || result.blacklist().isEmpty()) {
                throw new IllegalStateException("Permanent reputation blacklist failed: " + result.detail());
            }
            return result.blacklist().orElseThrow();
        }
    }

    @Override
    public boolean removeBlacklist(UUID playerId, String caseId) {
        synchronized (stateLock) {
            UUID requiredPlayerId = Objects.requireNonNull(playerId, PLAYER_ID_ARGUMENT);
            ReputationBlacklist current = state.blacklists().get(requiredPlayerId);
            if (current == null || current.status() == ReputationBlacklist.Status.REMOVED) {
                return false;
            }
            ReputationStateSnapshot before = snapshotLocked(requiredPlayerId);
            return removeBlacklistLocked(
                    UUID.randomUUID(), requiredPlayerId, caseId, current.revision(), before.checksum()).success();
        }
    }

    @Override
    public boolean canGiveReputation(UUID playerId) {
        synchronized (stateLock) {
            UUID requiredPlayerId = Objects.requireNonNull(playerId, PLAYER_ID_ARGUMENT);
            return !storageFailureFences.contains(requiredPlayerId)
                    && !state.reconciliationPending().contains(requiredPlayerId)
                    && getBlacklistLocked(requiredPlayerId)
                    .map(value -> !value.activeAt(clock.instant()))
                    .orElse(true);
        }
    }

    @Override
    public void markReconciliationPending(UUID playerId) {
        synchronized (stateLock) {
            UUID requiredPlayerId = Objects.requireNonNull(playerId, PLAYER_ID_ARGUMENT);
            if (state.reconciliationPending().contains(requiredPlayerId)) {
                storageFailureFences.remove(requiredPlayerId);
                return;
            }
            Set<UUID> pending = new HashSet<>(state.reconciliationPending());
            pending.add(requiredPlayerId);
            ReputationModerationStore.State candidate = new ReputationModerationStore.State(
                    state.blacklists(), state.operations(), pending);
            try {
                store.save(candidate);
            } catch (RuntimeException exception) {
                storageFailureFences.add(requiredPlayerId);
                throw exception;
            }
            state = candidate;
            storageFailureFences.remove(requiredPlayerId);
        }
    }

    @Override
    public void clearReconciliationPending(UUID playerId) {
        synchronized (stateLock) {
            UUID requiredPlayerId = Objects.requireNonNull(playerId, PLAYER_ID_ARGUMENT);
            if (!state.reconciliationPending().contains(requiredPlayerId)) {
                storageFailureFences.remove(requiredPlayerId);
                return;
            }
            Set<UUID> pending = new HashSet<>(state.reconciliationPending());
            pending.remove(requiredPlayerId);
            ReputationModerationStore.State candidate = new ReputationModerationStore.State(
                    state.blacklists(), state.operations(), pending);
            store.save(candidate);
            state = candidate;
            storageFailureFences.remove(requiredPlayerId);
        }
    }

    @Override
    public Optional<ReputationBlacklist> getBlacklist(UUID playerId) {
        synchronized (stateLock) {
            return getBlacklistLocked(playerId);
        }
    }

    @Override
    public ReputationStateSnapshot snapshot(UUID playerId) {
        synchronized (stateLock) {
            return snapshotLocked(playerId);
        }
    }

    @Override
    public ReputationMutationResult applyBlacklist(
            UUID operationId,
            UUID playerId,
            Optional<Instant> expirationAt,
            String caseId,
            long expectedBlacklistRevision,
            String expectedReputationChecksum
    ) {
        synchronized (stateLock) {
            return applyBlacklistLocked(
                    operationId,
                    playerId,
                    expirationAt,
                    caseId,
                    expectedBlacklistRevision,
                    expectedReputationChecksum
            );
        }
    }

    private ReputationMutationResult applyBlacklistLocked(
            UUID operationId,
            UUID playerId,
            Optional<Instant> expirationAt,
            String caseId,
            long expectedBlacklistRevision,
            String expectedReputationChecksum
    ) {
        Objects.requireNonNull(operationId, "operationId");
        UUID requiredPlayerId = Objects.requireNonNull(playerId, PLAYER_ID_ARGUMENT);
        Optional<Instant> normalizedExpiration = Objects.requireNonNull(expirationAt, "expirationAt");
        requireApplyRevision(expectedBlacklistRevision);
        String normalizedCase = requireCaseId(caseId);
        String expectedChecksum = requireChecksum(expectedReputationChecksum);
        String fingerprint = "APPLY|" + requiredPlayerId + '|' + normalizedExpiration.map(Instant::toString).orElse("PERMANENT")
                + '|' + normalizedCase + '|' + expectedBlacklistRevision + '|' + expectedChecksum;
        ReputationMutationResult replay = replay(operationId, fingerprint);
        if (replay != null) {
            return replay;
        }
        ReputationStateSnapshot before = snapshotLocked(requiredPlayerId);
        if (!before.checksum().equals(expectedChecksum)) {
            return transientResult(ReputationMutationResult.Status.STALE_REPUTATION, before,
                    "Reputation changed after the moderation snapshot; retry from fresh state");
        }
        ReputationBlacklist previous = state.blacklists().get(requiredPlayerId);
        long actualRevision = previous == null ? NO_BLACKLIST_REVISION : previous.revision();
        if (actualRevision != expectedBlacklistRevision) {
            return transientResult(ReputationMutationResult.Status.STALE_BLACKLIST, before,
                    "Blacklist state changed after it was read; retry from fresh state");
        }
        Instant now = clock.instant();
        if (isExpiredAtCreation(normalizedExpiration, now)) {
            return transientResult(ReputationMutationResult.Status.REJECTED, before,
                    "Reputation blacklist expiration must be in the future");
        }
        long revision = Math.addExact(actualRevision, REVISION_INCREMENT);
        ReputationBlacklist blacklist = new ReputationBlacklist(
                requiredPlayerId, now, normalizedExpiration, normalizedCase, normalizedCase,
                ReputationBlacklist.Status.ACTIVE, revision, now);
        ReputationStateSnapshot after = snapshotLocked(requiredPlayerId);
        if (!after.checksum().equals(expectedChecksum)) {
            return driftResult(before, after);
        }
        return commit(operationId, fingerprint, ReputationMutationResult.Status.APPLIED,
                Optional.of(blacklist), before, after, "Reputation giving is blacklisted", blacklist);
    }

    @Override
    public ReputationMutationResult removeBlacklist(
            UUID operationId,
            UUID playerId,
            String caseId,
            long expectedBlacklistRevision,
            String expectedReputationChecksum
    ) {
        synchronized (stateLock) {
            return removeBlacklistLocked(
                    operationId,
                    playerId,
                    caseId,
                    expectedBlacklistRevision,
                    expectedReputationChecksum
            );
        }
    }

    private ReputationMutationResult removeBlacklistLocked(
            UUID operationId,
            UUID playerId,
            String caseId,
            long expectedBlacklistRevision,
            String expectedReputationChecksum
    ) {
        Objects.requireNonNull(operationId, "operationId");
        UUID requiredPlayerId = Objects.requireNonNull(playerId, PLAYER_ID_ARGUMENT);
        requireRemovalRevision(expectedBlacklistRevision);
        String normalizedCase = requireCaseId(caseId);
        String expectedChecksum = requireChecksum(expectedReputationChecksum);
        String fingerprint = "REMOVE|" + requiredPlayerId + '|' + normalizedCase + '|'
                + expectedBlacklistRevision + '|' + expectedChecksum;
        ReputationMutationResult replay = replay(operationId, fingerprint);
        if (replay != null) {
            return replay;
        }
        ReputationStateSnapshot before = snapshotLocked(requiredPlayerId);
        if (!before.checksum().equals(expectedChecksum)) {
            return transientResult(ReputationMutationResult.Status.STALE_REPUTATION, before,
                    "Reputation changed after the moderation snapshot; retry from fresh state");
        }
        ReputationBlacklist current = state.blacklists().get(requiredPlayerId);
        if (isStaleRemoval(current, expectedBlacklistRevision)) {
            return transientResult(ReputationMutationResult.Status.STALE_BLACKLIST, before,
                    "Blacklist state changed after it was read; retry from fresh state");
        }
        Instant now = clock.instant();
        ReputationBlacklist removed = new ReputationBlacklist(
                requiredPlayerId,
                current.startsAt(),
                current.expirationAt(),
                current.caseId(),
                normalizedCase,
                ReputationBlacklist.Status.REMOVED,
                Math.addExact(current.revision(), REVISION_INCREMENT),
                now
        );
        ReputationStateSnapshot after = snapshotLocked(requiredPlayerId);
        if (!after.checksum().equals(expectedChecksum)) {
            return driftResult(before, after);
        }
        return commit(operationId, fingerprint, ReputationMutationResult.Status.REMOVED,
                Optional.of(removed), before, after, "Reputation blacklist removed", removed);
    }

    private Optional<ReputationBlacklist> getBlacklistLocked(UUID playerId) {
        UUID requiredPlayerId = Objects.requireNonNull(playerId, PLAYER_ID_ARGUMENT);
        ReputationBlacklist value = state.blacklists().get(requiredPlayerId);
        return value == null ? Optional.empty() : Optional.of(value.effectiveAt(clock.instant()));
    }

    private ReputationStateSnapshot snapshotLocked(UUID playerId) {
        UUID requiredPlayerId = Objects.requireNonNull(playerId, PLAYER_ID_ARGUMENT);
        ReputationStateSnapshot snapshot = Objects.requireNonNull(
                snapshotProvider.apply(requiredPlayerId),
                "snapshotProvider result"
        );
        if (!snapshot.playerId().equals(requiredPlayerId)) {
            throw new IllegalStateException("snapshot provider returned a different player");
        }
        return snapshot;
    }

    private long currentRevision(UUID playerId) {
        ReputationBlacklist value = state.blacklists().get(playerId);
        return value == null ? NO_BLACKLIST_REVISION : value.revision();
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

    @SuppressWarnings("PMD.UseConcurrentHashMap")
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
        // These maps are copy-on-write candidates protected by stateLock; preserving operation order is required for bounded eviction.
        Map<UUID, ReputationBlacklist> blacklists = new LinkedHashMap<>(state.blacklists());
        blacklists.put(persistedBlacklist.playerId(), persistedBlacklist);
        LinkedHashMap<UUID, ReputationModerationStore.Operation> operations = new LinkedHashMap<>(state.operations());
        Iterator<UUID> operationIterator = operations.keySet().iterator();
        while (operations.size() >= MAX_OPERATIONS && operationIterator.hasNext()) {
            operationIterator.next();
            operationIterator.remove();
        }
        operations.put(operationId, new ReputationModerationStore.Operation(
                operationId, fingerprint, status, resultBlacklist, before, after, detail));
        ReputationModerationStore.State candidate = new ReputationModerationStore.State(
                blacklists, operations, state.reconciliationPending());
        store.save(candidate);
        state = candidate;
        return new ReputationMutationResult(status, resultBlacklist, before, after, detail);
    }

    private static boolean isExpiredAtCreation(Optional<Instant> expirationAt, Instant now) {
        return expirationAt.map(expiration -> !expiration.isAfter(now)).orElse(false);
    }

    private static boolean isStaleRemoval(ReputationBlacklist current, long expectedRevision) {
        return current == null
                || current.revision() != expectedRevision
                || current.status() == ReputationBlacklist.Status.REMOVED;
    }

    private static void requireApplyRevision(long expectedBlacklistRevision) {
        if (expectedBlacklistRevision < NO_BLACKLIST_REVISION) {
            throw new IllegalArgumentException("expectedBlacklistRevision cannot be negative");
        }
    }

    private static void requireRemovalRevision(long expectedBlacklistRevision) {
        if (expectedBlacklistRevision < FIRST_BLACKLIST_REVISION) {
            throw new IllegalArgumentException("expectedBlacklistRevision must be positive for removal");
        }
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
