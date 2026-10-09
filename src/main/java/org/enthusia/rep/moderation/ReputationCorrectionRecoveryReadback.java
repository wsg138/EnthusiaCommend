package org.enthusia.rep.moderation;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.enthusia.rep.api.ReputationStateSnapshot;

/**
 * Read-only recovery evidence for a prepared exact-entry correction.
 *
 * <p>Matching the intended after-state does not establish who changed the data,
 * whether it was authorized or whether every secondary provider index and
 * journal was updated. Only a separate durable, provider-issued commit receipt
 * may eventually establish completion. This class cannot mutate reputation.</p>
 */
public final class ReputationCorrectionRecoveryReadback {
    private ReputationCorrectionRecoveryReadback() {
    }

    public static Report inspect(
            ReputationCorrectionIntentJournal.Prepared prepared,
            ReputationStateSnapshot observed
    ) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(observed, "observed");
        ReputationStateSnapshot before = prepared.before();
        if (!observed.playerId().equals(prepared.subjectId())) {
            throw new IllegalArgumentException("Recovery observation belongs to a different player");
        }
        verifyChecksum(observed);
        // Treat a manually constructed Prepared record as untrusted, too.
        var selection = ReputationCorrectionPreflight.select(
                before, prepared.subjectId(), prepared.expectedChecksum(), prepared.exactEntries());
        if (prepared.expectedTotalAfterRemoval() != selection.expectedTotalAfterRemoval()) {
            throw new IllegalStateException("Prepared correction expected total is inconsistent");
        }
        ReputationStateSnapshot expectedAfter = afterSnapshot(before, selection);
        Outcome outcome = determineOutcome(before, expectedAfter, observed);
        return new Report(prepared.operationId(), outcome, expectedAfter, observed);
    }

    private static void verifyChecksum(ReputationStateSnapshot observed) {
        String actual = ReputationSnapshotFactory.checksum(
                observed.playerId(), observed.totalScore(), observed.entries());
        if (!actual.equals(observed.checksum())) {
            throw new IllegalStateException("Provider recovery observation has inconsistent checksum");
        }
    }

    private static ReputationStateSnapshot afterSnapshot(
            ReputationStateSnapshot before,
            ReputationCorrectionPreflight.Selection selection
    ) {
        Set<UUID> selectedGivers = selection.entries().stream()
                .map(ReputationEntrySnapshot::giverId)
                .collect(Collectors.toUnmodifiableSet());
        List<ReputationEntrySnapshot> remaining = before.entries().stream()
                .filter(entry -> !selectedGivers.contains(entry.giverId()))
                .toList();
        int total = selection.expectedTotalAfterRemoval();
        return new ReputationStateSnapshot(before.playerId(), total, remaining,
                ReputationSnapshotFactory.checksum(before.playerId(), total, remaining));
    }

    private static Outcome determineOutcome(
            ReputationStateSnapshot before,
            ReputationStateSnapshot expectedAfter,
            ReputationStateSnapshot observed
    ) {
        if (observed.equals(before)) {
            return Outcome.ORIGINAL_DATA_UNCHANGED;
        }
        if (observed.equals(expectedAfter)) {
            return Outcome.EXPECTED_DATA_ONLY_NO_RECEIPT;
        }
        return Outcome.CONFLICT_REQUIRES_RECONCILIATION;
    }

    public enum Outcome {
        /** Data remains as it was prepared; not proof that nothing else happened. */
        ORIGINAL_DATA_UNCHANGED,
        /** Exact projected after-data is present; NOT proof of authorized execution. */
        EXPECTED_DATA_ONLY_NO_RECEIPT,
        /** Neither complete state matches: require human/provider reconciliation. */
        CONFLICT_REQUIRES_RECONCILIATION
    }

    public record Report(
            UUID operationId,
            Outcome outcome,
            ReputationStateSnapshot expectedAfter,
            ReputationStateSnapshot observed
    ) {
        public Report {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(expectedAfter, "expectedAfter");
            Objects.requireNonNull(observed, "observed");
        }
    }
}
