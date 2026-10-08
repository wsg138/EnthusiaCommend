package org.enthusia.rep.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.enthusia.rep.storage.PluginDataSnapshot;
import org.junit.jupiter.api.Test;

class ReputationCorrectionProviderPreviewTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID OPERATION = UUID.fromString("00000000-0000-0000-0000-000000000402");
    private static final UUID REVIEWER = UUID.fromString("00000000-0000-0000-0000-000000000403");
    private static final UUID GIVER_A = UUID.fromString("00000000-0000-0000-0000-000000000411");
    private static final UUID GIVER_B = UUID.fromString("00000000-0000-0000-0000-000000000412");
    private static final UUID GIVER_C = UUID.fromString("00000000-0000-0000-0000-000000000413");
    private static final Instant REQUESTED = Instant.parse("2026-10-08T22:00:00Z");
    private static final String CASE_ID = "CASE-PREVIEW";

    @Test
    void selectsExactProviderVoteAndPreservesIndependentScoreAndIdentity() {
        var positive = commendation(GIVER_A, true, 2, 100L, 200L);
        var negative = commendation(GIVER_B, false, -3, 300L, 400L);
        RepIdentityState identity = new RepIdentityState(Set.of(), Set.of(), 250L,
                Map.of(GIVER_B.toString(), 250L, GIVER_C.toString(), 230L));
        PluginDataSnapshot data = data(13, List.of(positive, negative), identity);
        var before = expectedBefore(13, List.of(positive, negative));
        var selected = entry(negative);
        var intent = intent(before, List.of(selected));

        var plan = ReputationCorrectionProviderPreview.plan(intent, data);

        assertEquals(OPERATION, plan.operationId());
        assertEquals(before, plan.before());
        assertEquals(16, plan.projectedAfter().totalScore());
        assertEquals(List.of(entry(positive)), plan.projectedAfter().entries());
        assertEquals(List.of(selected), plan.exactEntriesToRemove());
        assertEquals(identity, plan.previousTargetIdentity());
        assertEquals(List.of(GIVER_B), plan.negativeGiversToForgive());
        assertEquals(230L, plan.projectedTargetIdentity().tarnishedAt());
        assertEquals(Map.of(GIVER_C.toString(), 230L), plan.projectedTargetIdentity().tarnishSources());

        // No primary-data, cache, historical, or identity mutation is permitted by a preview.
        assertEquals(13, data.scores().get(PLAYER));
        assertEquals(2, data.commendations().size());
        assertEquals(identity, data.identities().get(PLAYER));
        assertEquals(-3, negative.getScoreValue());
    }

    @Test
    void positiveCorrectionDoesNotForgiveUnrelatedNegativeIdentitySources() {
        var positive = commendation(GIVER_A, true, 2, 100L, 200L);
        var negative = commendation(GIVER_B, false, -3, 300L, 400L);
        var identity = new RepIdentityState(Set.of(), Set.of(), 250L,
                Map.of(GIVER_B.toString(), 250L));
        var data = data(13, List.of(positive, negative), identity);
        var intent = intent(expectedBefore(13, List.of(positive, negative)), List.of(entry(positive)));
        var preview = ReputationCorrectionProviderPreview.plan(intent, data);

        assertEquals(11, preview.projectedAfter().totalScore());
        assertEquals(List.of(entry(negative)), preview.projectedAfter().entries());
        assertEquals(List.of(), preview.negativeGiversToForgive());
        assertEquals(identity, preview.projectedTargetIdentity());
    }

    @Test
    void staleScoreEditedVoteOrMissingSelectedEntryRefusesOfflinePlan() {
        var positive = commendation(GIVER_A, true, 2, 100L, 200L);
        var negative = commendation(GIVER_B, false, -3, 300L, 400L);
        var identity = RepIdentityState.EMPTY;
        var before = expectedBefore(13, List.of(positive, negative));
        var intent = intent(before, List.of(entry(positive)));
        var edited = commendation(GIVER_A, true, 2, 100L, 201L);

        assertThrows(IllegalStateException.class,
                () -> ReputationCorrectionProviderPreview.plan(
                        intent, data(14, List.of(positive, negative), identity)));
        assertThrows(IllegalStateException.class,
                () -> ReputationCorrectionProviderPreview.plan(
                        intent, data(13, List.of(edited, negative), identity)));
        assertThrows(IllegalStateException.class,
                () -> ReputationCorrectionProviderPreview.plan(
                        intent, data(13, List.of(negative), identity)));
    }

    @Test
    void legacyUnattributedTarnishMustReconcileBeforeNegativeCorrection() {
        var negative = commendation(GIVER_B, false, -3, 300L, 400L);
        var legacy = new RepIdentityState(Set.of(), Set.of(), 500L, Map.of());
        var intent = intent(expectedBefore(10, List.of(negative)), List.of(entry(negative)));
        var data = data(10, List.of(negative), legacy);

        assertThrows(IllegalStateException.class,
                () -> ReputationCorrectionProviderPreview.plan(intent, data));
        assertEquals(500L, data.identities().get(PLAYER).tarnishedAt());
    }

    @Test
    void previewCannotMistakeSameScoreAndDifferentVotesForExactProviderData() {
        var positive = commendation(GIVER_A, true, 2, 100L, 200L);
        var negative = commendation(GIVER_B, false, -3, 300L, 400L);
        var old = expectedBefore(13, List.of(positive, negative));
        var intent = intent(old, List.of(entry(positive)));
        var changedNegative = commendation(GIVER_C, false, -3, 300L, 400L);
        var unrelated = data(13, List.of(positive, changedNegative), RepIdentityState.EMPTY);
        assertThrows(IllegalStateException.class,
                () -> ReputationCorrectionProviderPreview.plan(intent, unrelated));
        assertNotEquals(old.checksum(), expectedBefore(13, List.of(positive, changedNegative)).checksum());
    }

    private static PluginDataSnapshot data(
            int score, List<Commendation> commendations, RepIdentityState identity
    ) {
        return new PluginDataSnapshot(
                Map.of(PLAYER, score),
                commendations,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(PLAYER, identity));
    }

    private static Commendation commendation(
            UUID giver, boolean positive, int weight, long created, long edited
    ) {
        return new Commendation(giver, PLAYER, positive,
                positive ? RepCategory.WAS_KIND : RepCategory.SCAMMED,
                "test", created, edited, null, weight);
    }

    private static ReputationEntrySnapshot entry(Commendation commendation) {
        return new ReputationEntrySnapshot(
                commendation.getGiver(), commendation.getTarget(), commendation.isPositive(),
                commendation.getCategory().name(), commendation.getScoreValue(),
                commendation.getCreatedAt(), commendation.getLastEditedAt());
    }

    private static ReputationStateSnapshot expectedBefore(int score, List<Commendation> commendations) {
        List<ReputationEntrySnapshot> entries = commendations.stream()
                .map(ReputationCorrectionProviderPreviewTest::entry)
                .sorted(java.util.Comparator.comparing(value -> value.giverId().toString()))
                .toList();
        return new ReputationStateSnapshot(PLAYER, score, entries,
                ReputationSnapshotFactory.checksum(PLAYER, score, entries));
    }

    private static ReputationCorrectionIntentJournal.Prepared intent(
            ReputationStateSnapshot before, List<ReputationEntrySnapshot> selected
    ) {
        var preflight = ReputationCorrectionPreflight.select(
                before, PLAYER, before.checksum(), selected);
        return new ReputationCorrectionIntentJournal.Prepared(
                OPERATION, REVIEWER, CASE_ID, PLAYER, before.checksum(),
                before, selected, preflight.expectedTotalAfterRemoval(), REQUESTED);
    }
}
