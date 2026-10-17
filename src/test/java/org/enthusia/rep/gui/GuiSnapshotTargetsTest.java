package org.enthusia.rep.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;
import org.junit.jupiter.api.Test;

class GuiSnapshotTargetsTest {
    private static final UUID GIVER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TARGET = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Test
    void renderedEntryLookupRejectsMissingAndOutOfRangeIndices() {
        Object first = new Object();
        Object second = new Object();
        List<Object> rendered = List.of(first, second);

        assertSame(first, GuiSnapshotTargets.at(rendered, 0));
        assertSame(second, GuiSnapshotTargets.at(rendered, 1));
        assertNull(GuiSnapshotTargets.at(rendered, -1));
        assertNull(GuiSnapshotTargets.at(rendered, 2));
        assertNull(GuiSnapshotTargets.at(null, 0));
    }

    @Test
    void revisionComparisonAcceptsEquivalentSnapshotsAndRejectsChangedContent() {
        Commendation expected = commendation(GIVER, TARGET, true, RepCategory.WAS_KIND, "reason", 10L, 20L, "ip", 2);
        Commendation equivalent = commendation(GIVER, TARGET, true, RepCategory.WAS_KIND, "reason", 10L, 20L, "ip", 2);

        assertTrue(GuiSnapshotTargets.sameCommendationRevision(expected, equivalent));
        assertFalse(GuiSnapshotTargets.sameCommendationRevision(null, equivalent));
        assertFalse(GuiSnapshotTargets.sameCommendationRevision(expected, null));
        assertFalse(GuiSnapshotTargets.sameCommendationRevision(
                expected, commendation(GIVER, TARGET, true, RepCategory.WAS_KIND, "changed", 10L, 20L, "ip", 2)));
        assertFalse(GuiSnapshotTargets.sameCommendationRevision(
                expected, commendation(GIVER, TARGET, true, RepCategory.WAS_KIND, "reason", 10L, 21L, "ip", 2)));
        assertFalse(GuiSnapshotTargets.sameCommendationRevision(
                expected, commendation(GIVER, TARGET, true, RepCategory.WAS_KIND, "reason", 10L, 20L, "other", 2)));
        assertFalse(GuiSnapshotTargets.sameCommendationRevision(
                expected, commendation(GIVER, TARGET, true, RepCategory.WAS_KIND, "reason", 10L, 20L, "ip", 3)));
    }

    private static Commendation commendation(
            UUID giver,
            UUID target,
            boolean positive,
            RepCategory category,
            String reason,
            long created,
            long edited,
            String ipHash,
            int score
    ) {
        return new Commendation(giver, target, positive, category, reason, created, edited, ipHash, score);
    }
}
