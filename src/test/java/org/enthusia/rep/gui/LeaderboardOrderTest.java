package org.enthusia.rep.gui;

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
        Map<UUID, Long> latest = Map.of(FIRST, 10L, SECOND, 20L);
        assertEquals(List.of(SCORES.get(1), SCORES.get(0)), LeaderboardOrder.RECENT.sort(SCORES, latest));
    }

    @Test
    void recentExcludesPlayersWithoutMatchingWindowEntries() {
        assertEquals(List.of(SCORES.get(1)), LeaderboardOrder.DAY.sort(SCORES, Map.of(SECOND, 20L)));
        assertEquals(List.of(), LeaderboardOrder.WEEK.sort(SCORES, Map.of()));
    }

    @Test
    void scoreOrderRetainsPlayersWithoutRecentEntries() {
        assertSame(SCORES, LeaderboardOrder.SCORE.sort(SCORES, Map.of()));
    }
}
