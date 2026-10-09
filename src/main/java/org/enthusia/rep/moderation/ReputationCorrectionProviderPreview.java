package org.enthusia.rep.moderation;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.enthusia.rep.api.ReputationStateSnapshot;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepIdentityState;
import org.enthusia.rep.storage.PluginDataSnapshot;

/**
 * Read-only inspection of the real provider snapshot and projected correction effects.
 *
 * <p>This class does not build a persistable replacement data.yml, mutate any
 * provider cache, append an audit record, authorize a case, or issue a receipt.
 * In particular, a caller must not use a preview to mark a remedy completed.</p>
 */
public final class ReputationCorrectionProviderPreview {
    private ReputationCorrectionProviderPreview() {
    }

    public static Preview plan(
            ReputationCorrectionIntentJournal.Prepared prepared,
            PluginDataSnapshot providerData
    ) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(providerData, "providerData");
        UUID playerId = prepared.subjectId();
        ReputationStateSnapshot observed = providerSnapshot(providerData, playerId);
        var comparison = ReputationCorrectionRecoveryReadback.inspect(prepared, observed);
        if (comparison.outcome() != ReputationCorrectionRecoveryReadback.Outcome.ORIGINAL_DATA_UNCHANGED) {
            throw new IllegalStateException(
                    "Provider primary data no longer matches the prepared exact-entry correction");
        }
        RepIdentityState priorIdentity = providerData.identities()
                .getOrDefault(playerId, RepIdentityState.EMPTY);
        RepIdentityState nextIdentity = projectedIdentity(priorIdentity, prepared.exactEntries());
        List<UUID> negativeGivers = prepared.exactEntries().stream()
                .filter(entry -> !entry.positive())
                .map(ReputationEntrySnapshot::giverId)
                .toList();
        return new Preview(prepared.operationId(), observed, comparison.expectedAfter(),
                prepared.exactEntries(), priorIdentity, nextIdentity, negativeGivers);
    }

    private static ReputationStateSnapshot providerSnapshot(PluginDataSnapshot data, UUID playerId) {
        List<ReputationEntrySnapshot> entries = data.commendations().stream()
                .filter(entry -> entry.getTarget().equals(playerId))
                .map(ReputationCorrectionProviderPreview::entry)
                .sorted(Comparator.comparing((ReputationEntrySnapshot value) -> value.giverId().toString())
                        .thenComparingLong(ReputationEntrySnapshot::createdAt)
                        .thenComparing(ReputationEntrySnapshot::category))
                .toList();
        int score = data.scores().getOrDefault(playerId, 0);
        return new ReputationStateSnapshot(playerId, score, entries,
                ReputationSnapshotFactory.checksum(playerId, score, entries));
    }

    private static ReputationEntrySnapshot entry(Commendation value) {
        return new ReputationEntrySnapshot(
                value.getGiver(), value.getTarget(), value.isPositive(),
                value.getCategory().name(), value.getScoreValue(),
                value.getCreatedAt(), value.getLastEditedAt());
    }

    private static RepIdentityState projectedIdentity(
            RepIdentityState prior, List<ReputationEntrySnapshot> selected
    ) {
        boolean negativeSelection = selected.stream().anyMatch(entry -> !entry.positive());
        if (negativeSelection && prior.tarnishedAt() > 0 && prior.tarnishSources().isEmpty()) {
            // Historical state may need the migration normally performed by
            // RepService.loadSnapshot. Never forgive a partially migrated
            // identity by erasing its unattributed legacy tarnish.
            throw new IllegalStateException(
                    "Legacy tarnish sources require provider reconciliation before correction preview");
        }
        RepIdentityState result = prior;
        for (ReputationEntrySnapshot entry : selected) {
            if (!entry.positive()) {
                result = result.forgive(entry.giverId());
            }
        }
        return result;
    }

    public record Preview(
            UUID operationId,
            ReputationStateSnapshot before,
            ReputationStateSnapshot projectedAfter,
            List<ReputationEntrySnapshot> exactEntriesToRemove,
            RepIdentityState previousTargetIdentity,
            RepIdentityState projectedTargetIdentity,
            List<UUID> negativeGiversToForgive
    ) {
        public Preview {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(projectedAfter, "projectedAfter");
            exactEntriesToRemove = List.copyOf(Objects.requireNonNull(exactEntriesToRemove, "exactEntriesToRemove"));
            Objects.requireNonNull(previousTargetIdentity, "previousTargetIdentity");
            Objects.requireNonNull(projectedTargetIdentity, "projectedTargetIdentity");
            negativeGiversToForgive = List.copyOf(
                    Objects.requireNonNull(negativeGiversToForgive, "negativeGiversToForgive"));
        }
    }
}
