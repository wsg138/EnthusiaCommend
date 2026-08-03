package org.enthusia.rep.rep;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepLeaderboardPopulationTest {
    @Test
    void positiveAndNegativeCategoriesSortOnlyTheirOwnMembers() {
        UUID positiveA = new UUID(0, 1);
        UUID positiveB = new UUID(0, 2);
        UUID negativeA = new UUID(0, 3);
        List<RepLeaderboardPopulation.CategoryEntry> entries = List.of(
                new RepLeaderboardPopulation.CategoryEntry(positiveA, RepCategory.WAS_KIND, 1),
                new RepLeaderboardPopulation.CategoryEntry(positiveB, RepCategory.WAS_KIND, 3),
                new RepLeaderboardPopulation.CategoryEntry(negativeA, RepCategory.SCAMMED, -2));

        Map<UUID, Integer> positive = RepLeaderboardPopulation.categoryTotalsFromEntries(entries, RepCategory.WAS_KIND);
        assertEquals(List.of(positiveB, positiveA), keys(RepLeaderboardSorter.sort(positive, false)));
        assertEquals(List.of(positiveA, positiveB), keys(RepLeaderboardSorter.sort(positive, true)));

        Map<UUID, Integer> negative = RepLeaderboardPopulation.categoryTotalsFromEntries(entries, RepCategory.SCAMMED);
        assertEquals(List.of(negativeA), keys(RepLeaderboardSorter.sort(negative, false)));
        assertEquals(List.of(negativeA), keys(RepLeaderboardSorter.sort(negative, true)));
    }

    @Test
    void unrelatedPlayersAreExcludedAndRealZeroTotalsRemain() {
        UUID zero = new UUID(0, 10);
        UUID unrelated = new UUID(0, 11);
        List<RepLeaderboardPopulation.CategoryEntry> entries = List.of(
                new RepLeaderboardPopulation.CategoryEntry(zero, RepCategory.WAS_KIND, 1),
                new RepLeaderboardPopulation.CategoryEntry(zero, RepCategory.WAS_KIND, -1),
                new RepLeaderboardPopulation.CategoryEntry(unrelated, RepCategory.SCAMMED, -2));

        Map<UUID, Integer> totals =
                RepLeaderboardPopulation.categoryTotalsFromEntries(entries, RepCategory.WAS_KIND);
        assertEquals(0, totals.get(zero));
        assertFalse(totals.containsKey(unrelated));
    }

    @Test
    void paginationSizeUsesFilteredPopulationAndEmptyCategoriesStayEmpty() {
        List<RepLeaderboardPopulation.CategoryEntry> entries = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            entries.add(new RepLeaderboardPopulation.CategoryEntry(
                    new UUID(1, index), RepCategory.HELPED_ME, 1));
        }
        for (int index = 0; index < 100; index++) {
            entries.add(new RepLeaderboardPopulation.CategoryEntry(
                    new UUID(2, index), RepCategory.SCAMMED, -2));
        }

        Map<UUID, Integer> filtered =
                RepLeaderboardPopulation.categoryTotalsFromEntries(entries, RepCategory.HELPED_ME);
        assertEquals(30, filtered.size());
        assertEquals(2, (filtered.size() + 27) / 28);
        assertTrue(RepLeaderboardPopulation.categoryTotalsFromEntries(entries, RepCategory.GAVE_ITEMS)
                .isEmpty());
    }

    @Test
    void offlinePlayersAreRepresentedByUuidWithoutBukkitLookup() {
        UUID offline = UUID.fromString("f84c6a79-47df-4c35-9f4f-0b5ce52e7d39");
        Map<UUID, Integer> totals = RepLeaderboardPopulation.categoryTotalsFromEntries(
                List.of(new RepLeaderboardPopulation.CategoryEntry(
                        offline, RepCategory.HELPED_ME, 1)),
                RepCategory.HELPED_ME);
        assertEquals(1, totals.get(offline));
    }

    private List<UUID> keys(List<Map.Entry<UUID, Integer>> entries) {
        return entries.stream().map(Map.Entry::getKey).toList();
    }
}
