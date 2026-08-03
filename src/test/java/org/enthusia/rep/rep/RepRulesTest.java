package org.enthusia.rep.rep;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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

    @Test
    void publicCategoryValidationRejectsLegacyAndMismatchedCategories() {
        assertEquals(RepCategory.WAS_KIND, RepRules.acceptedCategory(null, true));
        assertEquals(RepCategory.SCAMMED, RepRules.acceptedCategory(null, false));
        assertEquals(RepCategory.GRIEFED, RepRules.acceptedCategory(RepCategory.GRIEFED, false));
        assertNull(RepRules.acceptedCategory(RepCategory.OTHER_NEGATIVE, false));
        assertNull(RepRules.acceptedCategory(RepCategory.OTHER_POSITIVE, true));
        assertNull(RepRules.acceptedCategory(RepCategory.WAS_KIND, false));
    }

    @Test
    void removalCooldownSurvivesUntilExactExpiry() {
        long removedAt = 10_000L;
        long duration = 5_000L;
        assertTrue(RepRules.isCooldownActive(removedAt, 14_999L, duration));
        assertFalse(RepRules.isCooldownActive(removedAt, 15_000L, duration));
        assertFalse(RepRules.isCooldownActive(removedAt, 9_999L, duration));
    }

    @Test
    void categoryScoresUseTheActualCategoryValuesAndMigrateLegacyEntries() {
        UUID target = UUID.randomUUID();
        List<Commendation> entries = List.of(
                new Commendation(UUID.randomUUID(), target, true, RepCategory.HELPED_ME, "", 1L, 1L, null, 1),
                new Commendation(UUID.randomUUID(), target, true, RepCategory.HELPED_ME, "", 2L, 2L, null, 1),
                new Commendation(UUID.randomUUID(), target, false, RepCategory.SCAMMED, "", 3L, 3L, null, -2),
                new Commendation(UUID.randomUUID(), target, true, RepCategory.OTHER_POSITIVE, "", 4L, 4L, null, 1)
        );
        Map<RepCategory, Integer> scores = RepRules.categoryScores(entries);
        assertEquals(2, scores.get(RepCategory.HELPED_ME));
        assertEquals(-2, scores.get(RepCategory.SCAMMED));
        assertEquals(1, scores.get(RepCategory.WAS_KIND));
    }

    @Test
    void selectableRegistryProvidesDisplayMetadataWithoutLegacyDuplicates() {
        assertEquals(10, RepCategory.selectableValues().size());
        assertFalse(RepCategory.selectableValues().contains(RepCategory.OTHER_POSITIVE));
        assertFalse(RepCategory.selectableValues().contains(RepCategory.OTHER_NEGATIVE));
        assertTrue(RepCategory.selectableValues().stream().allMatch(category -> !category.displayName().isBlank()));
        assertTrue(RepCategory.selectableValues().stream().allMatch(category -> !category.description().isBlank()));
    }
}
