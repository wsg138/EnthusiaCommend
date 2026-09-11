package org.enthusia.rep.command;

import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RecentReputationCommandTest {
    @Test
    void filtersTargetTimeAndPolarityWhileSortingNewestEditsFirst() {
        UUID target = UUID.randomUUID();
        Commendation early = entry(target, RepCategory.WAS_KIND, 100);
        Commendation late = entry(target, RepCategory.HELPED_ME, 300);
        Commendation negative = entry(target, RepCategory.GRIEFED, 400);
        Commendation different = entry(UUID.randomUUID(), RepCategory.WAS_KIND, 500);
        List<Commendation> all = List.of(early, late, negative, different);
        assertEquals(List.of(late), RecentReputationCommand.select(all, target, 200, "POSITIVE"));
        assertEquals(List.of(negative), RecentReputationCommand.select(all, target, 100, "NEGATIVE"));
        assertEquals(List.of(late), RecentReputationCommand.select(all, target, 0, "HELPED_ME"));
        assertEquals(List.of(different, negative, late), RecentReputationCommand.select(all, null, 300, "ALL"));
        assertThrows(IllegalArgumentException.class, () -> RecentReputationCommand.select(all, null, 0, "SCAM_STALL"));
    }

    private Commendation entry(UUID target, RepCategory category, long time) {
        return new Commendation(UUID.randomUUID(), target, category.isPositive(), category, "Reason", 1, time, "hash", category.defaultScoreValue());
    }
}
