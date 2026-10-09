package org.enthusia.rep.moderation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.enthusia.rep.analytics.ReputationChangeAction;
import org.enthusia.rep.analytics.ReputationChangeOutcome;
import org.enthusia.rep.analytics.ReputationChangeRecord;
import org.enthusia.rep.analytics.ReputationChangeSource;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepService;
import org.enthusia.rep.storage.PluginDataSnapshot;

/**
 * Pure projection of removal history, change audit, and cooldown effects for a
 * proposed Policy v2 correction. No provider state or file is modified.
 *
 * <p>The output is NOT safe to save or publish. There is no trusted Staff
 * case/revision authorization, atomic primary-file receipt, provider-wide
 * serialization lock, or replay/recovery proof. A repeated projection is not
 * proof that a correction was ever executed.</p>
 */
public final class ReputationCorrectionAuditProjection {
    private ReputationCorrectionAuditProjection() {
    }

    public static Projection plan(
            ReputationCorrectionIntentJournal.Prepared prepared,
            PluginDataSnapshot currentData,
            Instant occurredAt,
            long removalCooldownMillis
    ) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(currentData, "currentData");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (removalCooldownMillis < 0L || occurredAt.isBefore(prepared.preparedAt())) {
            throw new IllegalArgumentException("Correction timing or cooldown policy is invalid");
        }

        var base = ReputationCorrectionPrimaryDataProjection.project(prepared, currentData);
        var before = base.beforeData();
        var after = base.projectedData();
        var additions = buildAddedHistory(prepared, before, occurredAt);
        List<RepService.RemovedRep> removed = new ArrayList<>(after.removedEntries());
        removed.addAll(additions.removed());
        List<ReputationChangeRecord> changes = new ArrayList<>(after.reputationChanges());
        changes.addAll(additions.changes());

        List<PluginDataSnapshot.RemovalCooldownEntry> cooldowns =
                buildCooldowns(prepared, before, occurredAt, removalCooldownMillis);
        PluginDataSnapshot proposed = new PluginDataSnapshot(
                after.scores(), after.commendations(), removed,
                after.stalkEntries(), changes, after.suspiciousCases(), cooldowns,
                after.repTradingAlertPreferences(), after.identities());
        return new Projection(base, proposed, additions.removed(), additions.changes());
    }

    private static AddedHistory buildAddedHistory(
            ReputationCorrectionIntentJournal.Prepared prepared,
            PluginDataSnapshot before,
            Instant occurredAt
    ) {
        List<RepService.RemovedRep> removed = new ArrayList<>();
        List<ReputationChangeRecord> changes = new ArrayList<>();
        int runningTotal = before.scores().getOrDefault(prepared.subjectId(), 0);
        for (ReputationEntrySnapshot selected : prepared.exactEntries()) {
            Commendation full = before.commendations().stream()
                    .filter(entry -> entry.getGiver().equals(selected.giverId())
                            && entry.getTarget().equals(prepared.subjectId()))
                    .findFirst().orElseThrow();
            int nextTotal = Math.toIntExact((long) runningTotal - selected.scoreValue());
            removed.add(new RepService.RemovedRep(
                    "p2-removed-" + stableId(prepared.operationId(), selected.giverId(), "removed"),
                    full.snapshot(), occurredAt.toEpochMilli(), prepared.reviewerId()));
            changes.add(new ReputationChangeRecord(
                    "p2-change-" + stableId(prepared.operationId(), selected.giverId(), "change"),
                    occurredAt.toEpochMilli(), prepared.subjectId(), prepared.reviewerId(),
                    null, -selected.scoreValue(), ReputationChangeAction.REMOVE,
                    ReputationChangeSource.ADMIN_CORRECTION, ReputationChangeOutcome.SUCCEEDED,
                    "Policy v2 case " + prepared.caseId(), full.getCategory(), runningTotal, nextTotal));
            runningTotal = nextTotal;
        }
        if (runningTotal != prepared.expectedTotalAfterRemoval()) {
            throw new IllegalStateException("Projected audit totals diverge from verified correction");
        }
        return new AddedHistory(List.copyOf(removed), List.copyOf(changes));
    }

    private static List<PluginDataSnapshot.RemovalCooldownEntry> buildCooldowns(
            ReputationCorrectionIntentJournal.Prepared prepared,
            PluginDataSnapshot before,
            Instant occurredAt,
            long removalCooldownMillis
    ) {
        Set<UUID> selectedGivers = prepared.exactEntries().stream()
                .map(ReputationEntrySnapshot::giverId)
                .collect(Collectors.toUnmodifiableSet());
        List<PluginDataSnapshot.RemovalCooldownEntry> updated = new ArrayList<>(
                before.removalCooldowns().stream()
                        .filter(entry -> !entry.targetId().equals(prepared.subjectId())
                                || !selectedGivers.contains(entry.giverId()))
                        .toList());
        if (removalCooldownMillis > 0L) {
            for (UUID giver : selectedGivers.stream()
                    .sorted(java.util.Comparator.comparing(UUID::toString)).toList()) {
                updated.add(new PluginDataSnapshot.RemovalCooldownEntry(
                        giver, prepared.subjectId(), occurredAt.toEpochMilli()));
            }
        }
        return List.copyOf(updated);
    }

    private static UUID stableId(UUID operation, UUID giver, String phase) {
        String input = operation + "|" + giver + "|" + phase;
        return UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8));
    }

    private record AddedHistory(
            List<RepService.RemovedRep> removed,
            List<ReputationChangeRecord> changes
    ) {
    }

    /** Candidate only: MUST NOT be persisted or treated as a durable receipt. */
    public record Projection(
            ReputationCorrectionPrimaryDataProjection.Projection primary,
            PluginDataSnapshot proposedData,
            List<RepService.RemovedRep> removalHistoryAdded,
            List<ReputationChangeRecord> changeHistoryAdded
    ) {
        public Projection {
            Objects.requireNonNull(primary, "primary");
            Objects.requireNonNull(proposedData, "proposedData");
            removalHistoryAdded = List.copyOf(removalHistoryAdded);
            changeHistoryAdded = List.copyOf(changeHistoryAdded);
        }
    }
}
