package org.enthusia.rep.moderation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.enthusia.rep.api.ReputationStateSnapshot;

/**
 * Pure, non-mutating preflight for a future case-authorized correction of
 * individually identified reputation entries.
 *
 * <p>Selected entries must match the current provider-owned snapshot exactly.
 * A later mutation must perform this check again under the provider's own
 * serialization/commit boundary; passing preflight alone is never an
 * authorization, a durable receipt, or proof of correction.</p>
 */
@SuppressWarnings("PMD.UseConcurrentHashMap") // Indexes are method-local and never shared concurrently.
public final class ReputationCorrectionPreflight {
    private static final int MAX_SELECTED_ENTRIES = 64;

    private ReputationCorrectionPreflight() {
    }

    public static Selection select(
            ReputationStateSnapshot current,
            UUID subjectId,
            String expectedChecksum,
            List<ReputationEntrySnapshot> requestedEntries
    ) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(expectedChecksum, "expectedChecksum");
        List<ReputationEntrySnapshot> requested = List.copyOf(
                Objects.requireNonNull(requestedEntries, "requestedEntries")
        );
        verifySnapshot(current, subjectId, expectedChecksum, requested);
        Map<UUID, ReputationEntrySnapshot> byGiver = indexEntries(current, subjectId);
        long adjustedTotal = adjustedTotal(current.totalScore(), requested, byGiver, subjectId);
        return new Selection(subjectId, expectedChecksum, requested, Math.toIntExact(adjustedTotal));
    }

    private static void verifySnapshot(
            ReputationStateSnapshot current, UUID subjectId, String expectedChecksum,
            List<ReputationEntrySnapshot> requested
    ) {
        if (!current.playerId().equals(subjectId)) {
            throw new IllegalArgumentException("Correction subject does not match snapshot subject");
        }
        if (requested.isEmpty() || requested.size() > MAX_SELECTED_ENTRIES) {
            throw new IllegalArgumentException("Correction selection must contain 1 to 64 entries");
        }
        if (!current.checksum().equals(expectedChecksum)) {
            throw new IllegalStateException("Reputation snapshot changed before correction preflight");
        }
        if (!current.checksum().equals(ReputationSnapshotFactory.checksum(
                current.playerId(), current.totalScore(), current.entries()))) {
            throw new IllegalStateException("Provider reputation snapshot checksum is inconsistent");
        }
    }

    private static Map<UUID, ReputationEntrySnapshot> indexEntries(
            ReputationStateSnapshot current, UUID subjectId
    ) {
        // One active commendation is permitted for any giver/target pair;
        // reject a corrupt provider snapshot instead of choosing an arbitrary vote.
        Map<UUID, ReputationEntrySnapshot> byGiver = new HashMap<>();
        for (ReputationEntrySnapshot entry : current.entries()) {
            if (!entry.targetId().equals(subjectId)
                    || byGiver.putIfAbsent(entry.giverId(), entry) != null) {
                throw new IllegalStateException("Provider reputation snapshot has ambiguous entries");
            }
        }
        return byGiver;
    }

    private static long adjustedTotal(
            int originalTotal, List<ReputationEntrySnapshot> requested,
            Map<UUID, ReputationEntrySnapshot> byGiver, UUID subjectId
    ) {
        Set<UUID> selectedGivers = new HashSet<>();
        long result = originalTotal;
        for (ReputationEntrySnapshot selected : requested) {
            if (!selected.targetId().equals(subjectId)) {
                throw new IllegalArgumentException("Selected reputation entry belongs to another player");
            }
            if (!selectedGivers.add(selected.giverId())) {
                throw new IllegalArgumentException("Duplicate reputation entry in correction selection");
            }
            if (!selected.equals(byGiver.get(selected.giverId()))) {
                throw new IllegalStateException("Selected reputation entry is absent or has changed");
            }
            result -= selected.scoreValue();
        }
        return result;
    }

    /**
     * An immutable, verified candidate—not a committed provider mutation.
     * Exact selected snapshots preserve the original giver, polarity, category,
     * weight and version timestamps needed for CAS at commit time.
     */
    public static final class Selection {
        private final UUID selectedSubject;
        private final String observedChecksum;
        private final List<ReputationEntrySnapshot> selectedEntries;
        private final int adjustedScore;

        private Selection(UUID subjectId, String expectedChecksum,
                List<ReputationEntrySnapshot> entries, int expectedTotalAfterRemoval) {
            this.selectedSubject = Objects.requireNonNull(subjectId, "subjectId");
            this.observedChecksum = Objects.requireNonNull(expectedChecksum, "expectedChecksum");
            this.selectedEntries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            this.adjustedScore = expectedTotalAfterRemoval;
        }

        public UUID subjectId() {
            return selectedSubject;
        }

        public String expectedChecksum() {
            return observedChecksum;
        }

        public List<ReputationEntrySnapshot> entries() {
            return selectedEntries;
        }

        public int expectedTotalAfterRemoval() {
            return adjustedScore;
        }
    }
}
