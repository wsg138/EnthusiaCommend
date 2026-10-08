package org.enthusia.rep.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.enthusia.rep.api.ReputationStateSnapshot;
import org.junit.jupiter.api.Test;

class ReputationCorrectionPreflightTest {
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000021");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000022");
    private static final UUID GIVER_A = UUID.fromString("00000000-0000-0000-0000-000000000031");
    private static final UUID GIVER_B = UUID.fromString("00000000-0000-0000-0000-000000000032");

    private static final ReputationEntrySnapshot GOOD = new ReputationEntrySnapshot(
            GIVER_A, SUBJECT, true, "HELPFUL", 2, 100L, 200L);
    private static final ReputationEntrySnapshot BAD = new ReputationEntrySnapshot(
            GIVER_B, SUBJECT, false, "SCAMMED", -2, 300L, 400L);

    @Test
    void selectsOnlyExplicitExactEntriesAndPreservesIndependentTotal() {
        ReputationStateSnapshot state = snapshot(27, List.of(GOOD, BAD));
        var selection = ReputationCorrectionPreflight.select(state, SUBJECT, state.checksum(), List.of(BAD));
        assertEquals(SUBJECT, selection.subjectId());
        assertEquals(state.checksum(), selection.expectedChecksum());
        assertEquals(List.of(BAD), selection.entries());
        assertEquals(29, selection.expectedTotalAfterRemoval());
    }

    @Test
    void preservesExactOrderAndDefensivelyCopiesSelection() {
        ReputationStateSnapshot state = snapshot(10, List.of(GOOD, BAD));
        List<ReputationEntrySnapshot> requested = new ArrayList<>(List.of(BAD, GOOD));
        var selected = ReputationCorrectionPreflight.select(state, SUBJECT, state.checksum(), requested);
        requested.clear();
        assertEquals(List.of(BAD, GOOD), selected.entries());
        assertEquals(10, selected.expectedTotalAfterRemoval());
        assertThrows(UnsupportedOperationException.class, () -> selected.entries().clear());
    }

    @Test
    void refusesWrongSubjectOrEntry() {
        ReputationStateSnapshot state = snapshot(10, List.of(GOOD));
        assertThrows(IllegalArgumentException.class, () ->
                ReputationCorrectionPreflight.select(state, OTHER, state.checksum(), List.of(GOOD)));
        ReputationEntrySnapshot foreign = new ReputationEntrySnapshot(
                GIVER_A, OTHER, true, "HELPFUL", 2, 100L, 200L);
        assertThrows(IllegalArgumentException.class, () ->
                ReputationCorrectionPreflight.select(state, SUBJECT, state.checksum(), List.of(foreign)));
    }

    @Test
    void refusesStaleChecksumAndChangedOrMissingEntries() {
        ReputationStateSnapshot state = snapshot(10, List.of(GOOD));
        assertThrows(IllegalStateException.class, () ->
                ReputationCorrectionPreflight.select(state, SUBJECT, "0".repeat(64), List.of(GOOD)));
        ReputationEntrySnapshot edited = new ReputationEntrySnapshot(
                GIVER_A, SUBJECT, true, "HELPFUL", 2, 100L, 201L);
        assertThrows(IllegalStateException.class, () ->
                ReputationCorrectionPreflight.select(state, SUBJECT, state.checksum(), List.of(edited)));
        assertThrows(IllegalStateException.class, () ->
                ReputationCorrectionPreflight.select(state, SUBJECT, state.checksum(), List.of(BAD)));
    }

    @Test
    void refusesForgedSnapshotsEvenWhenCallerEchoesTheirChecksum() {
        ReputationStateSnapshot original = snapshot(10, List.of(GOOD));
        ReputationStateSnapshot alteredScore = new ReputationStateSnapshot(
                SUBJECT, 11, List.of(GOOD), original.checksum());
        ReputationStateSnapshot alteredEntry = new ReputationStateSnapshot(
                SUBJECT, 10, List.of(BAD), original.checksum());

        assertThrows(IllegalStateException.class, () ->
                ReputationCorrectionPreflight.select(
                        alteredScore, SUBJECT, alteredScore.checksum(), List.of(GOOD)));
        assertThrows(IllegalStateException.class, () ->
                ReputationCorrectionPreflight.select(
                        alteredEntry, SUBJECT, alteredEntry.checksum(), List.of(BAD)));
    }

    @Test
    void refusesEmptyDuplicateAndAmbiguousSelections() {
        ReputationStateSnapshot state = snapshot(10, List.of(GOOD));
        assertThrows(IllegalArgumentException.class, () ->
                ReputationCorrectionPreflight.select(state, SUBJECT, state.checksum(), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                ReputationCorrectionPreflight.select(state, SUBJECT, state.checksum(), List.of(GOOD, GOOD)));
        ReputationStateSnapshot ambiguous = snapshot(10, List.of(GOOD,
                new ReputationEntrySnapshot(GIVER_A, SUBJECT, true, "HELPFUL", 3, 101L, 202L)));
        assertThrows(IllegalStateException.class, () ->
                ReputationCorrectionPreflight.select(ambiguous, SUBJECT, ambiguous.checksum(), List.of(GOOD)));
    }

    @Test
    void rejectsScoreOverflowInsteadOfWrapping() {
        ReputationStateSnapshot state = snapshot(Integer.MIN_VALUE, List.of(GOOD));
        assertThrows(ArithmeticException.class, () ->
                ReputationCorrectionPreflight.select(state, SUBJECT, state.checksum(), List.of(GOOD)));
    }

    private static ReputationStateSnapshot snapshot(int total, List<ReputationEntrySnapshot> entries) {
        return new ReputationStateSnapshot(
                SUBJECT, total, entries, ReputationSnapshotFactory.checksum(SUBJECT, total, entries));
    }
}
