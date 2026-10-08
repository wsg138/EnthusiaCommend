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
        if (!current.playerId().equals(subjectId)) {
            throw new IllegalArgumentException("Correction subject does not match snapshot subject");
        }
        if (requested.isEmpty() || requested.size() > MAX_SELECTED_ENTRIES) {
            throw new IllegalArgumentException("Correction selection must contain 1 to 64 entries");
        }
        if (!current.checksum().equals(expectedChecksum)) {
            throw new IllegalStateException("Reputation snapshot changed before correction preflight");
        }

        // EnthusiaCommend holds at most one active commendation per giver/target pair.
        // A corrupt or ambiguous snapshot must never choose an arbitrary entry.
        Map<UUID, ReputationEntrySnapshot> byGiver = new HashMap<>();
        for (ReputationEntrySnapshot entry : current.entries()) {
            if (!entry.targetId().equals(subjectId)
                    || byGiver.putIfAbsent(entry.giverId(), entry) != null) {
                throw new IllegalStateException("Provider reputation snapshot has ambiguous entries");
            }
        }

        Set<UUID> selectedGivers = new HashSet<>();
        int adjustedTotal = current.totalScore();
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
            adjustedTotal = Math.subtractExact(adjustedTotal, selected.scoreValue());
        }

        return new Selection(subjectId, expectedChecksum, requested, adjustedTotal);
    }

    /**
     * An immutable, verified candidate—not a committed provider mutation.
     * Exact selected snapshots preserve the original giver, polarity, category,
     * weight and version timestamps needed for CAS at commit time.
     */
    public static final class Selection {
        private final UUID subjectId;
        private final String expectedChecksum;
        private final List<ReputationEntrySnapshot> entries;
        private final int expectedTotalAfterRemoval;

        private Selection(UUID subjectId, String expectedChecksum,
                List<ReputationEntrySnapshot> entries, int expectedTotalAfterRemoval) {
            this.subjectId = Objects.requireNonNull(subjectId, "subjectId");
            this.expectedChecksum = Objects.requireNonNull(expectedChecksum, "expectedChecksum");
            this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            this.expectedTotalAfterRemoval = expectedTotalAfterRemoval;
        }

        public UUID subjectId() {
            return subjectId;
        }

        public String expectedChecksum() {
            return expectedChecksum;
        }

        public List<ReputationEntrySnapshot> entries() {
            return entries;
        }

        public int expectedTotalAfterRemoval() {
            return expectedTotalAfterRemoval;
        }
    }
}
