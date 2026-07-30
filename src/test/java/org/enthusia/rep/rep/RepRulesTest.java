package org.enthusia.rep.rep;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RepRulesTest {
    @Test
    void detectsRecentReciprocityOnlyWithinWindow() {
        long now = 1_000_000_000L;
        Commendation recent = new Commendation(UUID.randomUUID(), UUID.randomUUID(), true,
                RepCategory.WAS_KIND, "", now - RepRules.RECIPROCITY_WINDOW_MILLIS + 1, now - 1000, null, 1);
        Commendation stale = new Commendation(UUID.randomUUID(), UUID.randomUUID(), true,
                RepCategory.WAS_KIND, "", now - RepRules.RECIPROCITY_WINDOW_MILLIS - 1,
                now - RepRules.RECIPROCITY_WINDOW_MILLIS - 1, null, 1);
        assertTrue(RepRules.isRecentReciprocal(recent, now));
        assertFalse(RepRules.isRecentReciprocal(stale, now));
    }

    @Test
    void clusterCountsDistinctRecentNegativeGivers() {
        long now = 2_000_000_000L;
        UUID target = UUID.randomUUID();
        UUID giverA = UUID.randomUUID();
        List<Commendation> entries = List.of(
                new Commendation(giverA, target, false, RepCategory.GRIEFED, "", now, now - 100, null, -2),
                new Commendation(giverA, target, false, RepCategory.SCAMMED, "", now, now - 50, null, -2),
                new Commendation(UUID.randomUUID(), target, false, RepCategory.TRAPPED, "", now, now - 20, null, -2),
                new Commendation(UUID.randomUUID(), target, true, RepCategory.WAS_KIND, "", now, now - 10, null, 1),
                new Commendation(UUID.randomUUID(), target, false, RepCategory.SCAM_STALL, "",
                        now, now - RepRules.CLUSTER_WINDOW_MILLIS - 1, null, -2)
        );
        Set<UUID> givers = RepRules.recentNegativeGivers(entries, now);
        assertEquals(2, givers.size());
        assertTrue(givers.contains(giverA));
    }

    @Test
    void legacyOtherCategoriesAreNotSelectable() {
        assertFalse(RepCategory.OTHER_POSITIVE.isSelectable());
        assertFalse(RepCategory.OTHER_NEGATIVE.isSelectable());
        assertEquals(RepCategory.WAS_KIND, RepCategory.OTHER_POSITIVE.migratedCategory());
        assertEquals(RepCategory.SCAMMED, RepCategory.OTHER_NEGATIVE.migratedCategory());
        assertEquals(-2, RepCategory.GRIEFED.defaultScoreValue());
    }
}
