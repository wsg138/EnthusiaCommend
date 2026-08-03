package org.enthusia.rep.gui;

import org.enthusia.rep.rep.Commendation;

import java.util.List;
import java.util.Objects;

/** Pure helpers for resolving clicks against the immutable content rendered in a GUI. */
final class GuiSnapshotTargets {
    private GuiSnapshotTargets() { }

    static <T> T at(List<T> renderedEntries, int relativeIndex) {
        if (renderedEntries == null || relativeIndex < 0 || relativeIndex >= renderedEntries.size()) {
            return null;
        }
        return renderedEntries.get(relativeIndex);
    }

    static boolean sameCommendationRevision(Commendation expected, Commendation current) {
        if (expected == null || current == null) {
            return false;
        }
        return Objects.equals(expected.getGiver(), current.getGiver())
                && Objects.equals(expected.getTarget(), current.getTarget())
                && expected.isPositive() == current.isPositive()
                && expected.getCategory() == current.getCategory()
                && expected.getScoreValue() == current.getScoreValue()
                && expected.getCreatedAt() == current.getCreatedAt()
                && expected.getLastEditedAt() == current.getLastEditedAt()
                && Objects.equals(expected.getReasonText(), current.getReasonText())
                && Objects.equals(expected.getIpHash(), current.getIpHash());
    }
}
