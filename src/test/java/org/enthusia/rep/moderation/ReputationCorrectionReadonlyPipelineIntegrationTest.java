package org.enthusia.rep.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.enthusia.rep.api.ReputationStateSnapshot;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;
import org.enthusia.rep.rep.RepIdentityState;
import org.enthusia.rep.storage.PluginDataSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReputationCorrectionReadonlyPipelineIntegrationTest {
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000801");
    private static final UUID POSITIVE = UUID.fromString("00000000-0000-0000-0000-000000000802");
    private static final UUID NEGATIVE = UUID.fromString("00000000-0000-0000-0000-000000000803");
    private static final UUID REVIEWER = UUID.fromString("00000000-0000-0000-0000-000000000804");
    private static final UUID OPERATION = UUID.fromString("00000000-0000-0000-0000-000000000805");
    private static final Instant PREPARED = Instant.parse("2026-10-08T22:00:00Z");
    private static final Instant PROPOSED_AT = Instant.parse("2026-10-08T22:30:00Z");

    @TempDir
    Path folder;

    @Test
    void restartAndAllReadOnlyLayersAgreeWithoutClaimingACommittedRemedy() throws Exception {
        var positive = vote(POSITIVE, 2);
        var negative = vote(NEGATIVE, -3);
        var current = new PluginDataSnapshot(
                Map.of(SUBJECT, 9), List.of(positive, negative),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(),
                Map.of(SUBJECT, new RepIdentityState(Set.of(), Set.of(), 200L,
                        Map.of(NEGATIVE.toString(), 200L))));
        var snapshot = state(9, List.of(positive, negative));
        var selected = List.of(entry(negative));
        Path journalPath = folder.resolve("prepared-corrections.yml");
        var journal = new ReputationCorrectionIntentJournal(journalPath);
        var prepared = journal.prepare(OPERATION, REVIEWER, "CASE-801",
                snapshot, SUBJECT, snapshot.checksum(), selected, PREPARED);

        var reopened = new ReputationCorrectionIntentJournal(journalPath);
        var restored = reopened.findOperation(OPERATION).orElseThrow();
        assertEquals(prepared, restored);
        assertEquals(ReputationCorrectionRecoveryReadback.Outcome.ORIGINAL_DATA_UNCHANGED,
                ReputationCorrectionRecoveryReadback.inspect(restored, snapshot).outcome());

        var primary = ReputationCorrectionPrimaryDataProjection.project(restored, current);
        var audit = ReputationCorrectionAuditProjection.plan(restored, current, PROPOSED_AT, 0L);
        var proposed = audit.proposedData();
        assertEquals(primary.projectedData().scores(), proposed.scores());
        assertEquals(primary.projectedData().commendations().size(), proposed.commendations().size());
        assertEquals(12, proposed.scores().get(SUBJECT));
        assertEquals(1, proposed.commendations().size());
        assertEquals(POSITIVE, proposed.commendations().getFirst().getGiver());
        assertEquals(1, audit.removalHistoryAdded().size());
        assertEquals(1, audit.changeHistoryAdded().size());
        assertEquals(0L, proposed.identities().get(SUBJECT).tarnishedAt());
        assertEquals(primary.preview().projectedAfter(),
                ReputationCorrectionRecoveryReadback.inspect(restored, snapshot).expectedAfter());

        var expectedOnly = ReputationCorrectionRecoveryReadback.inspect(
                restored, primary.preview().projectedAfter());
        assertEquals(ReputationCorrectionRecoveryReadback.Outcome.EXPECTED_DATA_ONLY_NO_RECEIPT,
                expectedOnly.outcome());

        var drift = state(13, List.of(positive));
        assertEquals(ReputationCorrectionRecoveryReadback.Outcome.CONFLICT_REQUIRES_RECONCILIATION,
                ReputationCorrectionRecoveryReadback.inspect(restored, drift).outcome());

        assertEquals(9, current.scores().get(SUBJECT));
        assertEquals(2, current.commendations().size());
        assertTrue(current.removedEntries().isEmpty());
        assertTrue(current.reputationChanges().isEmpty());
        assertTrue(Files.exists(journalPath));
        // A PREPARED journal and exact after-data projection still do not
        // produce a committed primary data file or a provider-issued receipt.
        assertFalse(Files.exists(folder.resolve("data.yml")));
    }

    private static ReputationStateSnapshot state(int score, List<Commendation> votes) {
        List<ReputationEntrySnapshot> values = votes.stream()
                .map(ReputationCorrectionReadonlyPipelineIntegrationTest::entry)
                .sorted(Comparator.comparing(value -> value.giverId().toString()))
                .toList();
        return new ReputationStateSnapshot(SUBJECT, score, values,
                ReputationSnapshotFactory.checksum(SUBJECT, score, values));
    }

    private static ReputationEntrySnapshot entry(Commendation value) {
        return new ReputationEntrySnapshot(value.getGiver(), value.getTarget(), value.isPositive(),
                value.getCategory().name(), value.getScoreValue(),
                value.getCreatedAt(), value.getLastEditedAt());
    }

    private static Commendation vote(UUID giver, int amount) {
        return new Commendation(giver, SUBJECT, amount > 0,
                amount > 0 ? RepCategory.WAS_KIND : RepCategory.SCAMMED,
                "example", 100L, 200L, null, amount);
    }
}
