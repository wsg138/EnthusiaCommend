package org.enthusia.rep.gui;

import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LeaderboardOrderTest {
    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);
    private static final List<Map.Entry<UUID, Integer>> SCORES = List.of(Map.entry(FIRST, 10), Map.entry(SECOND, 1));

    @Test
    void recentUsesLatestMatchingEditInsteadOfScore() {
        var reviews = List.of(entry(FIRST, RepCategory.WAS_KIND, 10), entry(SECOND, RepCategory.WAS_KIND, 20),
                entry(FIRST, RepCategory.SCAMMED, 30));
        assertEquals(List.of(SCORES.get(1), SCORES.get(0)), LeaderboardOrder.RECENT.sort(SCORES, reviews, RepProfileFilter.polarity(true), 0));
        assertEquals(SCORES, LeaderboardOrder.RECENT.sort(SCORES, reviews, RepProfileFilter.overall(), 0));
    }

    @Test
    void windowBoundaryAndCategoryFilterExcludeUnrelatedActivity() {
        var reviews = List.of(entry(FIRST, RepCategory.WAS_KIND, 19), entry(SECOND, RepCategory.WAS_KIND, 20),
                entry(FIRST, RepCategory.GAVE_ITEMS, 30));
        assertEquals(List.of(SCORES.get(1)), LeaderboardOrder.DAY.sort(SCORES, reviews, RepProfileFilter.category(RepCategory.WAS_KIND), 20));
        assertEquals(List.of(), LeaderboardOrder.WEEK.sort(SCORES, reviews, RepProfileFilter.polarity(false), 0));
    }

    @Test
    void scoreOrderRetainsPlayersWithoutRecentEntries() {
        assertSame(SCORES, LeaderboardOrder.SCORE.sort(SCORES, List.of(), RepProfileFilter.overall(), 100));
    }

    private Commendation entry(UUID target, RepCategory category, long edited) {
        return new Commendation(UUID.randomUUID(), target, category.isPositive(), category, "reason", 1L, edited, null, 1);
    }
}
