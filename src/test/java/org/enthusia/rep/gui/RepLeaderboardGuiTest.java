package org.enthusia.rep.gui;

import org.enthusia.rep.rep.RepCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RepLeaderboardGuiTest {
    @Test
    void categoryAndOverallEmptyStatesAreClear() {
        assertTrue(RepLeaderboardGui.emptyStateLabel(null).contains("No reputation entries"));
        assertTrue(RepLeaderboardGui.emptyStateLabel(RepCategory.HELPED_ME).contains("No entries in this category"));
    }
}
