package org.enthusia.rep.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.enthusia.rep.api.ReputationStateSnapshot;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;
import org.enthusia.rep.rep.RepIdentityState;
import org.enthusia.rep.rep.RepService;
import org.enthusia.rep.storage.PluginDataSnapshot;
import org.junit.jupiter.api.Test;

class ReputationCorrectionPrimaryDataProjectionTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000502");
    private static final UUID POSITIVE_GIVER = UUID.fromString("00000000-0000-0000-0000-000000000511");
    private static final UUID NEGATIVE_GIVER = UUID.fromString("00000000-0000-0000-0000-000000000512");
    private static final UUID REVIEWER = UUID.fromString("00000000-0000-0000-0000-000000000513");
    private static final UUID OPERATION = UUID.fromString("00000000-0000-0000-0000-000000000514");
    private static final Instant PREPARED_AT = Instant.parse("2026-10-08T23:00:00Z");

    @Test
    void targetedRemovalProjectsScoreAndIdentityWithoutAlteringOtherProviderData() {
        var positive = commendation(POSITIVE_GIVER, PLAYER, 2);
        var negative = commendation(NEGATIVE_GIVER, PLAYER, -3);
        var unrelated = commendation(POSITIVE_GIVER, OTHER, 1);
        var identity = new RepIdentityState(Set.of("hash"), Set.of(OTHER), 400L,
                Map.of(NEGATIVE_GIVER.toString(), 400L));
        var otherIdentity = new RepIdentityState(Set.of(), Set.of(PLAYER), 0);
        var historical = new RepService.RemovedRep("previous", unrelated.snapshot(), 500L, REVIEWER);
        var suspicious = new RepService.SuspiciousRepCase(OTHER, "ALT_IP", "old",
                List.of(POSITIVE_GIVER), 300L, false, "prior case");
        var cooldown = new PluginDataSnapshot.RemovalCooldownEntry(POSITIVE_GIVER, OTHER, 450L);
        var data = new PluginDataSnapshot(Map.of(PLAYER, 10, OTHER, 99),
                List.of(positive, negative, unrelated),
                List.of(historical), List.of(new PluginDataSnapshot.StalkEntry(POSITIVE_GIVER, OTHER, 700L)),
                List.of(), List.of(suspicious), List.of(cooldown),
                Map.of(OTHER, true), Map.of(PLAYER, identity, OTHER, otherIdentity));
        var intent = prepared(10, List.of(positive, negative), List.of(negative));

        var projection = ReputationCorrectionPrimaryDataProjection.project(intent, data);
        var before = projection.beforeData();
        var after = projection.projectedData();

        assertEquals(10, before.scores().get(PLAYER));
        assertEquals(13, after.scores().get(PLAYER));
        assertEquals(99, after.scores().get(OTHER));
        assertEquals(2, after.commendations().size());
        assertTrue(after.commendations().stream().anyMatch(entry ->
                entry.getTarget().equals(PLAYER) && entry.getGiver().equals(POSITIVE_GIVER)));
        assertTrue(after.commendations().stream().anyMatch(entry -> entry.getTarget().equals(OTHER)));
        assertFalse(after.commendations().stream().anyMatch(entry ->
                entry.getTarget().equals(PLAYER) && entry.getGiver().equals(NEGATIVE_GIVER)));

        assertEquals(0L, after.identities().get(PLAYER).tarnishedAt());
        assertEquals(otherIdentity, after.identities().get(OTHER));
        assertEquals(identity, before.identities().get(PLAYER));
        assertEquals(1, after.removedEntries().size()); // No claimed correction history yet.
        assertEquals("previous", after.removedEntries().getFirst().id());
        assertEquals(data.stalkEntries(), after.stalkEntries());
        assertEquals(data.removalCooldowns(), after.removalCooldowns());
        assertEquals(data.repTradingAlertPreferences(), after.repTradingAlertPreferences());
        assertEquals(suspicious.serialize(), after.suspiciousCases().getFirst().serialize());
        assertEquals(10, data.scores().get(PLAYER));
        assertEquals(3, data.commendations().size());
        assertEquals(identity, data.identities().get(PLAYER));

        // Projected objects do not alias mutable commendations or historical cases.
        assertNotSame(positive, before.commendations().getFirst());
        assertNotSame(positive, after.commendations().getFirst());
        assertNotSame(suspicious, after.suspiciousCases().getFirst());
        positive.setReasonText("changed after projection");
        assertEquals("original", before.commendations().getFirst().getReasonText());
        assertEquals("original", after.commendations().getFirst().getReasonText());
    }

    @Test
    void scoreDriftAndChangedEntryRefuseProjection() {
        var positive = commendation(POSITIVE_GIVER, PLAYER, 2);
        var negative = commendation(NEGATIVE_GIVER, PLAYER, -3);
        var intent = prepared(10, List.of(positive, negative), List.of(positive));

        assertThrows(IllegalStateException.class,
                () -> ReputationCorrectionPrimaryDataProjection.project(intent,
                        emptyHistory(11, List.of(positive, negative))));
        var edited = negative.snapshot();
        edited.setLastEditedAt(999L);
        assertThrows(IllegalStateException.class,
                () -> ReputationCorrectionPrimaryDataProjection.project(intent,
                        emptyHistory(10, List.of(positive, edited))));
    }

    @Test
    void ambiguousLegacyTarnishCannotBeProjectedAway() {
        var negative = commendation(NEGATIVE_GIVER, PLAYER, -3);
        var intent = prepared(10, List.of(negative), List.of(negative));
        var legacy = new RepIdentityState(Set.of(), Set.of(), 200L);
        var data = new PluginDataSnapshot(Map.of(PLAYER, 10), List.of(negative), List.of(),
                List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(PLAYER, legacy));
        assertThrows(IllegalStateException.class,
                () -> ReputationCorrectionPrimaryDataProjection.project(intent, data));
        assertEquals(200L, data.identities().get(PLAYER).tarnishedAt());
    }

    private static PluginDataSnapshot emptyHistory(int score, List<Commendation> entries) {
        return new PluginDataSnapshot(Map.of(PLAYER, score), entries,
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
    }

    private static ReputationCorrectionIntentJournal.Prepared prepared(
            int score, List<Commendation> entries, List<Commendation> selected
    ) {
        List<ReputationEntrySnapshot> originals = entries.stream()
                .map(ReputationCorrectionPrimaryDataProjectionTest::entry)
                .sorted(java.util.Comparator.comparing(value -> value.giverId().toString()))
                .toList();
        var before = new ReputationStateSnapshot(PLAYER, score, originals,
                ReputationSnapshotFactory.checksum(PLAYER, score, originals));
        var exact = selected.stream().map(ReputationCorrectionPrimaryDataProjectionTest::entry).toList();
        var selectedResult = ReputationCorrectionPreflight.select(before, PLAYER, before.checksum(), exact);
        return new ReputationCorrectionIntentJournal.Prepared(
                OPERATION, REVIEWER, "CASE-PROJECTION", PLAYER, before.checksum(),
                before, exact, selectedResult.expectedTotalAfterRemoval(), PREPARED_AT);
    }

    private static ReputationEntrySnapshot entry(Commendation value) {
        return new ReputationEntrySnapshot(value.getGiver(), value.getTarget(), value.isPositive(),
                value.getCategory().name(), value.getScoreValue(),
                value.getCreatedAt(), value.getLastEditedAt());
    }

    private static Commendation commendation(UUID giver, UUID target, int weight) {
        return new Commendation(giver, target, weight > 0,
                weight > 0 ? RepCategory.WAS_KIND : RepCategory.SCAMMED,
                "original", 100L, 200L, null, weight);
    }
}
