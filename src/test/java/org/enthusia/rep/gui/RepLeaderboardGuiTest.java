package org.enthusia.rep.gui;

import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepLeaderboardGuiTest {
    @Test
    void categoryAndOverallEmptyStatesAreClear() {
        assertTrue(RepLeaderboardGui.emptyStateLabel(null).contains("No reputation entries"));
        assertTrue(RepLeaderboardGui.emptyStateLabel(RepCategory.HELPED_ME).contains("No entries in this category"));
    }

    @Test
    void positiveAndNegativeMenusOnlyExposeTheirOwnCategories() {
        List<RepCategory> positive = RepLeaderboardGui.categories(true);
        List<RepCategory> negative = RepLeaderboardGui.categories(false);

        assertEquals(5, positive.size());
        assertEquals(5, negative.size());
        assertTrue(positive.stream().allMatch(RepCategory::isPositive));
        assertTrue(negative.stream().noneMatch(RepCategory::isPositive));
        assertEquals(RepCategory.WAS_KIND, positive.get(0));
        assertEquals(RepCategory.SCAMMED, negative.get(0));
    }

    @Test
    void filterMenuProvidesOverallAndEveryCategoryWithoutAcceptingOtherSlots() {
        assertTrue(RepLeaderboardGui.isFilterOptionSlot(10));
        assertTrue(RepLeaderboardGui.isFilterOptionSlot(16));
        assertFalse(RepLeaderboardGui.isFilterOptionSlot(13));
        assertNull(RepLeaderboardGui.filterCategoryAt(true, 10));
        assertEquals(RepCategory.WAS_KIND, RepLeaderboardGui.filterCategoryAt(true, 11));
        assertEquals(RepCategory.GOOD_STALL, RepLeaderboardGui.filterCategoryAt(true, 16));
        assertEquals(RepCategory.SCAMMED, RepLeaderboardGui.filterCategoryAt(false, 11));
        assertEquals(RepCategory.SCAM_STALL, RepLeaderboardGui.filterCategoryAt(false, 16));
        assertNull(RepLeaderboardGui.filterCategoryAt(false, 13));
    }

    @Test
    void clickTargetUsesRenderedSnapshotInsteadOfAReorderedLiveList() {
        UUID displayedFirst = new UUID(0L, 1L);
        UUID displayedSecond = new UUID(0L, 2L);
        List<UUID> rendered = List.of(displayedFirst, displayedSecond);
        List<UUID> laterLiveOrder = List.of(displayedSecond, displayedFirst);

        assertEquals(displayedFirst, GuiSnapshotTargets.at(rendered, 0));
        assertFalse(GuiSnapshotTargets.at(rendered, 0).equals(GuiSnapshotTargets.at(laterLiveOrder, 0)));
        assertNull(GuiSnapshotTargets.at(rendered, -1));
        assertNull(GuiSnapshotTargets.at(rendered, 2));
    }

    @Test
    void changedCommendationCannotPassRemovalConfirmationRevisionCheck() {
        UUID giver = new UUID(0L, 10L);
        UUID target = new UUID(0L, 11L);
        Commendation current = new Commendation(giver, target, true, RepCategory.HELPED_ME,
                "Original", 100L, 100L, "hash", 1);
        Commendation rendered = current.snapshot();

        assertTrue(GuiSnapshotTargets.sameCommendationRevision(rendered, current));
        current.applyUpdate(true, RepCategory.HELPED_ME, "Edited", 101L, "new-hash");
        assertFalse(GuiSnapshotTargets.sameCommendationRevision(rendered, current));
        assertFalse(GuiSnapshotTargets.sameCommendationRevision(rendered, null));
    }
}
