package org.enthusia.rep.rep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RepIdentityStateTest {
    private static final UUID GIVER_ONE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID GIVER_TWO = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID TARGET = UUID.fromString("30000000-0000-0000-0000-000000000003");

    @Test
    void rememberIpIgnoresMissingValuesAndPreservesImmutableHistory() {
        RepIdentityState empty = RepIdentityState.EMPTY;
        assertSame(empty, empty.rememberIp(null));
        assertSame(empty, empty.rememberIp("   "));

        RepIdentityState remembered = empty.rememberIp("sha256:a");
        assertNotSame(empty, remembered);
        assertTrue(remembered.ipHashes().contains("sha256:a"));
        assertTrue(empty.ipHashes().isEmpty());
        assertSame(remembered, remembered.rememberIp("sha256:a"));
    }

    @Test
    void voteHistoryIsCopiedForwardWithoutMutatingPriorState() {
        RepIdentityState empty = RepIdentityState.EMPTY;
        RepIdentityState voted = empty.rememberVote(TARGET);

        assertFalse(empty.givenTargets().contains(TARGET));
        assertEquals(Set.of(TARGET), voted.givenTargets());
    }

    @Test
    void sourceSpecificTarnishTracksLatestSourceAndForgivenessRecomputesTimestamp() {
        RepIdentityState first = RepIdentityState.EMPTY.tarnish(GIVER_ONE, 100L);
        RepIdentityState second = first.tarnish(GIVER_TWO, 80L);

        assertEquals(100L, second.tarnishedAt());
        assertEquals(Map.of(GIVER_ONE.toString(), 100L, GIVER_TWO.toString(), 80L), second.tarnishSources());

        RepIdentityState afterFirstForgiven = second.forgive(GIVER_ONE);
        assertEquals(80L, afterFirstForgiven.tarnishedAt());
        assertEquals(Map.of(GIVER_TWO.toString(), 80L), afterFirstForgiven.tarnishSources());

        RepIdentityState clean = afterFirstForgiven.forgive(GIVER_TWO);
        assertEquals(0L, clean.tarnishedAt());
        assertTrue(clean.tarnishSources().isEmpty());
    }

    @Test
    void legacyTarnishMigratesOnlyNegativeGiversAndCapsTimestampsAtLegacyValue() {
        Commendation positive = new Commendation(
                GIVER_ONE, TARGET, true, RepCategory.WAS_KIND, "good", 10L, 90L, null);
        Commendation negative = new Commendation(
                GIVER_TWO, TARGET, false, RepCategory.SCAMMED, "bad", 20L, 150L, null);
        RepIdentityState legacy = new RepIdentityState(Set.of(), Set.of(), 100L);

        RepIdentityState migrated = legacy.migrateSources(List.of(positive, negative));

        assertEquals(Map.of(GIVER_TWO.toString(), 100L), migrated.tarnishSources());
        assertEquals(100L, migrated.tarnishedAt());
    }

    @Test
    void migrationIsNoOpWhenLegacyStateWasNeverTarnished() {
        assertSame(RepIdentityState.EMPTY, RepIdentityState.EMPTY.migrateSources(List.of()));
    }
}
