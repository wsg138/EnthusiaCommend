package org.enthusia.rep.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReputationApiContractTest {
    private static final UUID PLAYER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String CHECKSUM = "a".repeat(64);

    @Test
    void blacklistNormalizesCaseIdsAndExpiresAtTheExactBoundary() {
        Instant start = Instant.parse("2026-09-23T12:00:00Z");
        Instant expiration = start.plusSeconds(60);
        ReputationBlacklist blacklist = new ReputationBlacklist(
                PLAYER,
                start,
                Optional.of(expiration),
                "  CASE-1  ",
                " LAST-2 ",
                ReputationBlacklist.Status.ACTIVE,
                1L,
                start
        );

        assertEquals("CASE-1", blacklist.caseId());
        assertEquals("LAST-2", blacklist.lastActionCaseId());
        assertTrue(blacklist.activeAt(start));
        assertTrue(blacklist.activeAt(expiration.minusNanos(1)));
        assertFalse(blacklist.activeAt(expiration));
        assertSame(blacklist, blacklist.effectiveAt(expiration.minusNanos(1)));
        assertEquals(ReputationBlacklist.Status.EXPIRED, blacklist.effectiveAt(expiration).status());
    }

    @Test
    void blacklistRejectsInvalidRevisionAndCaseIdsAndRemovedEntriesStayInactive() {
        Instant now = Instant.parse("2026-09-23T12:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> new ReputationBlacklist(
                PLAYER, now, Optional.empty(), "case", "case", ReputationBlacklist.Status.ACTIVE, 0L, now));
        assertThrows(IllegalArgumentException.class, () -> new ReputationBlacklist(
                PLAYER, now, Optional.empty(), "   ", "case", ReputationBlacklist.Status.ACTIVE, 1L, now));
        assertThrows(IllegalArgumentException.class, () -> new ReputationBlacklist(
                PLAYER, now, Optional.empty(), "x".repeat(65), "case", ReputationBlacklist.Status.ACTIVE, 1L, now));

        ReputationBlacklist removed = new ReputationBlacklist(
                PLAYER, now, Optional.empty(), "case", "case",
                ReputationBlacklist.Status.REMOVED, 1L, now);
        assertFalse(removed.activeAt(now));
        assertSame(removed, removed.effectiveAt(now));
    }

    @Test
    void entrySnapshotNormalizesCategoryAndEnforcesPolarityAndTimeInvariants() {
        ReputationEntrySnapshot positive = new ReputationEntrySnapshot(
                OTHER, PLAYER, true, "  kindness  ", 2, 10L, 20L);
        ReputationEntrySnapshot negative = new ReputationEntrySnapshot(
                OTHER, PLAYER, false, "scam", -3, 10L, 10L);

        assertEquals("kindness", positive.category());
        assertEquals(2, positive.scoreValue());
        assertEquals(-3, negative.scoreValue());

        assertThrows(IllegalArgumentException.class, () -> new ReputationEntrySnapshot(
                OTHER, PLAYER, true, "kindness", 0, 10L, 10L));
        assertThrows(IllegalArgumentException.class, () -> new ReputationEntrySnapshot(
                OTHER, PLAYER, true, "kindness", -1, 10L, 10L));
        assertThrows(IllegalArgumentException.class, () -> new ReputationEntrySnapshot(
                OTHER, PLAYER, false, "scam", 1, 10L, 10L));
        assertThrows(IllegalArgumentException.class, () -> new ReputationEntrySnapshot(
                OTHER, PLAYER, true, "kindness", 1, -1L, 10L));
        assertThrows(IllegalArgumentException.class, () -> new ReputationEntrySnapshot(
                OTHER, PLAYER, true, "kindness", 1, 10L, 9L));
    }

    @Test
    void stateSnapshotDefensivelyCopiesEntriesAndValidatesOwnershipAndChecksum() {
        ReputationEntrySnapshot entry = new ReputationEntrySnapshot(
                OTHER, PLAYER, true, "kindness", 1, 10L, 10L);
        List<ReputationEntrySnapshot> mutable = new ArrayList<>();
        mutable.add(entry);

        ReputationStateSnapshot snapshot = new ReputationStateSnapshot(
                PLAYER, 1, mutable, "A".repeat(64));
        mutable.clear();

        assertEquals(List.of(entry), snapshot.entries());
        assertEquals(CHECKSUM, snapshot.checksum());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entries().clear());
        assertThrows(IllegalArgumentException.class, () -> new ReputationStateSnapshot(
                PLAYER,
                1,
                List.of(new ReputationEntrySnapshot(PLAYER, OTHER, true, "kindness", 1, 10L, 10L)),
                CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> new ReputationStateSnapshot(
                PLAYER, 0, List.of(), "not-a-sha256"));
    }

    @Test
    void mutationSuccessIsLimitedToAppliedRemovedAndReplayedStatuses() {
        ReputationStateSnapshot before = new ReputationStateSnapshot(PLAYER, 0, List.of(), CHECKSUM);
        ReputationStateSnapshot after = new ReputationStateSnapshot(PLAYER, 1, List.of(), "b".repeat(64));

        for (ReputationMutationResult.Status status : ReputationMutationResult.Status.values()) {
            ReputationMutationResult result = new ReputationMutationResult(
                    status, Optional.empty(), before, after, "detail");
            boolean expected = status == ReputationMutationResult.Status.APPLIED
                    || status == ReputationMutationResult.Status.REMOVED
                    || status == ReputationMutationResult.Status.REPLAYED;
            assertEquals(expected, result.success(), status.name());
        }
    }
}
