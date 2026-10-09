package org.enthusia.rep.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.enthusia.rep.analytics.ReputationChangeAction;
import org.enthusia.rep.analytics.ReputationChangeSource;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.enthusia.rep.api.ReputationStateSnapshot;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;
import org.enthusia.rep.rep.RepIdentityState;
import org.enthusia.rep.rep.RepService;
import org.enthusia.rep.storage.PluginDataSnapshot;
import org.junit.jupiter.api.Test;

class ReputationCorrectionAuditProjectionTest {
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000702");
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000711");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000712");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000713");
    private static final UUID REVIEWER = UUID.fromString("00000000-0000-0000-0000-000000000714");
    private static final UUID OPERATION = UUID.fromString("00000000-0000-0000-0000-000000000715");
    private static final Instant PREPARED = Instant.parse("2026-10-08T22:00:00Z");
    private static final Instant CORRECTED = Instant.parse("2026-10-08T22:30:00Z");

    @Test
    void projectsAuditableMixedRemovalsWithoutChangingInputOrUnrelatedSections() {
        var good = vote(A, SUBJECT, 2);
        var bad = vote(B, SUBJECT, -3);
        var unrelated = vote(C, OTHER, 1);
        var oldHistory = new RepService.RemovedRep("prior", unrelated.snapshot(), 100L, REVIEWER);
        var identity = new RepIdentityState(Set.of(), Set.of(OTHER), 400L,
                Map.of(B.toString(), 400L));
        var oldCooldown = new PluginDataSnapshot.RemovalCooldownEntry(A, SUBJECT, 500L);
        var unrelatedCooldown = new PluginDataSnapshot.RemovalCooldownEntry(C, OTHER, 600L);
        var data = new PluginDataSnapshot(Map.of(SUBJECT, 10, OTHER, 99),
                List.of(good, bad, unrelated), List.of(oldHistory), List.of(), List.of(), List.of(),
                List.of(oldCooldown, unrelatedCooldown), Map.of(OTHER, false),
                Map.of(SUBJECT, identity));
        var prepared = prepared(10, List.of(good, bad), List.of(good, bad));

        var result = ReputationCorrectionAuditProjection.plan(prepared, data, CORRECTED, 100L);
        var projected = result.proposedData();

        assertEquals(11, projected.scores().get(SUBJECT));
        assertEquals(99, projected.scores().get(OTHER));
        assertEquals(1, projected.commendations().size());
        assertEquals(OTHER, projected.commendations().getFirst().getTarget());
        assertEquals(0L, projected.identities().get(SUBJECT).tarnishedAt());
        assertEquals(3, projected.removedEntries().size());
        assertEquals("prior", projected.removedEntries().getFirst().id());
        assertEquals(2, result.removalHistoryAdded().size());
        assertEquals(A, result.removalHistoryAdded().getFirst().commendation().getGiver());
        assertEquals(B, result.removalHistoryAdded().getLast().commendation().getGiver());
        assertEquals(List.of(-2, 3),
                result.changeHistoryAdded().stream().map(change -> change.amount()).toList());
        assertEquals(List.of(10, 8),
                result.changeHistoryAdded().stream().map(change -> change.oldTotal()).toList());
        assertEquals(List.of(8, 11),
                result.changeHistoryAdded().stream().map(change -> change.newTotal()).toList());
        assertTrue(result.changeHistoryAdded().stream().allMatch(change ->
                change.action() == ReputationChangeAction.REMOVE
                        && change.source() == ReputationChangeSource.ADMIN_CORRECTION));
        assertEquals(2, projected.reputationChanges().size());
        assertEquals(3, projected.removalCooldowns().size());
        assertTrue(projected.removalCooldowns().contains(unrelatedCooldown));
        assertTrue(projected.removalCooldowns().contains(
                new PluginDataSnapshot.RemovalCooldownEntry(A, SUBJECT, CORRECTED.toEpochMilli())));
        assertTrue(projected.removalCooldowns().contains(
                new PluginDataSnapshot.RemovalCooldownEntry(B, SUBJECT, CORRECTED.toEpochMilli())));
        assertEquals(data.repTradingAlertPreferences(), projected.repTradingAlertPreferences());
        assertEquals(10, data.scores().get(SUBJECT));
        assertEquals(3, data.commendations().size());
        assertEquals(1, data.removedEntries().size());
        assertEquals(2, data.removalCooldowns().size());
        assertEquals(identity, data.identities().get(SUBJECT));

        var repeated = ReputationCorrectionAuditProjection.plan(prepared, data, CORRECTED, 100L);
        assertEquals(result.changeHistoryAdded(), repeated.changeHistoryAdded());
        assertEquals(result.removalHistoryAdded().getFirst().id(),
                repeated.removalHistoryAdded().getFirst().id());
        assertNotEquals(result.removalHistoryAdded().get(0).id(),
                result.removalHistoryAdded().get(1).id());
    }

    @Test
    void disabledCooldownClearsOnlySelectedPairs() {
        var good = vote(A, SUBJECT, 2);
        var unrelated = new PluginDataSnapshot.RemovalCooldownEntry(C, OTHER, 600L);
        var oldSelected = new PluginDataSnapshot.RemovalCooldownEntry(A, SUBJECT, 500L);
        var data = new PluginDataSnapshot(Map.of(SUBJECT, 10), List.of(good),
                List.of(), List.of(), List.of(), List.of(), List.of(unrelated, oldSelected),
                Map.of(), Map.of());

        var projected = ReputationCorrectionAuditProjection.plan(
                prepared(10, List.of(good), List.of(good)), data, CORRECTED, 0L);

        assertEquals(List.of(unrelated), projected.proposedData().removalCooldowns());
    }

    @Test
    void refusesStaleScoreTimingAndUnrepresentableAuditDelta() {
        var good = vote(A, SUBJECT, 2);
        var data = new PluginDataSnapshot(Map.of(SUBJECT, 10), List.of(good),
                List.of(), List.of(), List.of(), List.of());
        var intent = prepared(10, List.of(good), List.of(good));
        assertThrows(IllegalStateException.class, () ->
                ReputationCorrectionAuditProjection.plan(intent,
                        new PluginDataSnapshot(Map.of(SUBJECT, 11), List.of(good),
                                List.of(), List.of(), List.of(), List.of()), CORRECTED, 0L));
        assertThrows(IllegalArgumentException.class, () ->
                ReputationCorrectionAuditProjection.plan(intent, data, PREPARED.minusSeconds(1), 0L));
        assertThrows(IllegalArgumentException.class, () ->
                ReputationCorrectionAuditProjection.plan(intent, data, CORRECTED, -1L));

        var minimum = vote(B, SUBJECT, Integer.MIN_VALUE);
        var enormous = new PluginDataSnapshot(Map.of(SUBJECT, Integer.MIN_VALUE),
                List.of(minimum), List.of(), List.of(), List.of(), List.of());
        var extremeIntent = prepared(Integer.MIN_VALUE, List.of(minimum), List.of(minimum));
        assertThrows(ArithmeticException.class, () ->
                ReputationCorrectionAuditProjection.plan(extremeIntent, enormous, CORRECTED, 0L));
    }

    private static ReputationCorrectionIntentJournal.Prepared prepared(
            int total, List<Commendation> all, List<Commendation> selected
    ) {
        List<ReputationEntrySnapshot> entries = all.stream()
                .map(ReputationCorrectionAuditProjectionTest::entry)
                .sorted(Comparator.comparing(e -> e.giverId().toString())).toList();
        var snapshot = new ReputationStateSnapshot(SUBJECT, total, entries,
                ReputationSnapshotFactory.checksum(SUBJECT, total, entries));
        var exact = selected.stream().map(ReputationCorrectionAuditProjectionTest::entry).toList();
        int expected = ReputationCorrectionPreflight.select(
                snapshot, SUBJECT, snapshot.checksum(), exact).expectedTotalAfterRemoval();
        return new ReputationCorrectionIntentJournal.Prepared(
                OPERATION, REVIEWER, "CASE-701", SUBJECT, snapshot.checksum(),
                snapshot, exact, expected, PREPARED);
    }

    private static ReputationEntrySnapshot entry(Commendation value) {
        return new ReputationEntrySnapshot(value.getGiver(), value.getTarget(),
                value.isPositive(), value.getCategory().name(), value.getScoreValue(),
                value.getCreatedAt(), value.getLastEditedAt());
    }

    private static Commendation vote(UUID giver, UUID target, int value) {
        return new Commendation(giver, target, value > 0,
                value > 0 ? RepCategory.WAS_KIND : RepCategory.SCAMMED,
                "original", 100L, 200L, null, value);
    }
}
