package org.enthusia.rep.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.enthusia.rep.api.ReputationStateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReputationCorrectionRecoveryReadbackTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID OPERATION = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID REVIEWER = UUID.fromString("00000000-0000-0000-0000-000000000303");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000304");
    private static final String CASE_ID = "CASE-RECOVERY";
    private static final Instant PREPARED_AT = Instant.parse("2026-10-08T21:00:00Z");
    private static final ReputationEntrySnapshot REMOVE = new ReputationEntrySnapshot(
            UUID.fromString("00000000-0000-0000-0000-000000000311"), PLAYER,
            true, "HELPFUL", 2, 100L, 200L);
    private static final ReputationEntrySnapshot RETAIN = new ReputationEntrySnapshot(
            UUID.fromString("00000000-0000-0000-0000-000000000312"), PLAYER,
            false, "SCAMMED", -3, 300L, 400L);

    @TempDir
    Path temporaryDirectory;

    @Test
    void unchangedBeforeDataIsNotMisreportedAsCompleted() {
        ReputationStateSnapshot before = snapshot(13, List.of(REMOVE, RETAIN));
        var intent = prepare(before, List.of(REMOVE));
        var report = ReputationCorrectionRecoveryReadback.inspect(intent, before);

        assertEquals(OPERATION, report.operationId());
        assertEquals(ReputationCorrectionRecoveryReadback.Outcome.ORIGINAL_DATA_UNCHANGED, report.outcome());
        assertEquals(11, report.expectedAfter().totalScore());
        assertEquals(List.of(RETAIN), report.expectedAfter().entries());
        assertEquals(before, report.observed());
        assertNotEquals(before.checksum(), report.expectedAfter().checksum());
    }

    @Test
    void exactAfterDataIsOnlyAnUnattributedMatchNotACommitReceipt() {
        var before = snapshot(13, List.of(REMOVE, RETAIN));
        var intent = prepare(before, List.of(REMOVE));
        var observed = snapshot(11, List.of(RETAIN));
        var report = ReputationCorrectionRecoveryReadback.inspect(intent, observed);

        assertEquals(ReputationCorrectionRecoveryReadback.Outcome.EXPECTED_DATA_ONLY_NO_RECEIPT, report.outcome());
        assertEquals(observed, report.expectedAfter());
        assertEquals(13, intent.before().totalScore());
        assertEquals(List.of(REMOVE, RETAIN), intent.before().entries());
    }

    @Test
    void negativeSelectionProjectsCorrectlyWithoutChangingOtherEntries() {
        var before = snapshot(13, List.of(REMOVE, RETAIN));
        var intent = prepare(before, List.of(RETAIN));
        var report = ReputationCorrectionRecoveryReadback.inspect(intent, snapshot(16, List.of(REMOVE)));
        assertEquals(ReputationCorrectionRecoveryReadback.Outcome.EXPECTED_DATA_ONLY_NO_RECEIPT, report.outcome());
        assertEquals(16, report.expectedAfter().totalScore());
        assertEquals(List.of(REMOVE), report.expectedAfter().entries());
    }

    @Test
    void partialRemovalScoreDriftAndOtherVoteChangesRequireReconciliation() {
        var before = snapshot(13, List.of(REMOVE, RETAIN));
        var intent = prepare(before, List.of(REMOVE));
        var partial = snapshot(13, List.of(RETAIN)); // Entry removed without expected score change
        var changedTotal = snapshot(12, List.of(RETAIN));
        var alteredOther = new ReputationEntrySnapshot(
                RETAIN.giverId(), PLAYER, RETAIN.positive(), RETAIN.category(), -3, 300L, 401L);
        for (var observed : List.of(partial, changedTotal, snapshot(11, List.of(alteredOther)))) {
            assertEquals(ReputationCorrectionRecoveryReadback.Outcome.CONFLICT_REQUIRES_RECONCILIATION,
                    ReputationCorrectionRecoveryReadback.inspect(intent, observed).outcome());
        }
    }

    @Test
    void forgedOrForeignObservedSnapshotCannotCountAsRecoveryEvidence() {
        var before = snapshot(13, List.of(REMOVE, RETAIN));
        var intent = prepare(before, List.of(REMOVE));
        var invalidChecksum = new ReputationStateSnapshot(
                PLAYER, 11, List.of(RETAIN), before.checksum());
        assertThrows(IllegalStateException.class,
                () -> ReputationCorrectionRecoveryReadback.inspect(intent, invalidChecksum));
        var foreign = new ReputationStateSnapshot(OTHER, 0, List.of(),
                ReputationSnapshotFactory.checksum(OTHER, 0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ReputationCorrectionRecoveryReadback.inspect(intent, foreign));
    }

    @Test
    void forgedPreparedTotalAndEntryChecksumsFailClosed() {
        var before = snapshot(13, List.of(REMOVE, RETAIN));
        var intent = prepare(before, List.of(REMOVE));
        var falseTotal = new ReputationCorrectionIntentJournal.Prepared(
                OPERATION, REVIEWER, CASE_ID, PLAYER, before.checksum(), before,
                List.of(REMOVE), 999, PREPARED_AT);
        assertThrows(IllegalStateException.class,
                () -> ReputationCorrectionRecoveryReadback.inspect(falseTotal, before));
        var falseChecksum = new ReputationCorrectionIntentJournal.Prepared(
                OPERATION, REVIEWER, CASE_ID, PLAYER, "0".repeat(64), before,
                List.of(REMOVE), 11, PREPARED_AT);
        assertThrows(IllegalStateException.class,
                () -> ReputationCorrectionRecoveryReadback.inspect(falseChecksum, before));
        assertEquals(ReputationCorrectionRecoveryReadback.Outcome.ORIGINAL_DATA_UNCHANGED,
                ReputationCorrectionRecoveryReadback.inspect(intent, before).outcome());
    }

    private ReputationCorrectionIntentJournal.Prepared prepare(
            ReputationStateSnapshot before, List<ReputationEntrySnapshot> selected
    ) {
        return new ReputationCorrectionIntentJournal(temporaryDirectory.resolve("correction-recovery.yml"))
                .prepare(OPERATION, REVIEWER, CASE_ID, before, PLAYER, before.checksum(),
                        selected, PREPARED_AT);
    }

    private static ReputationStateSnapshot snapshot(int score, List<ReputationEntrySnapshot> entries) {
        return new ReputationStateSnapshot(PLAYER, score, entries,
                ReputationSnapshotFactory.checksum(PLAYER, score, entries));
    }
}
