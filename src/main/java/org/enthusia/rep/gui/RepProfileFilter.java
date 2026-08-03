package org.enthusia.rep.gui;

import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;

import java.util.List;

/**
 * Immutable filter used by the reputation profile GUI. A null polarity with no
 * category represents the full profile; a polarity without a category
 * represents all positive or all negative entries.
 */
record RepProfileFilter(Boolean positive, RepCategory category) {

    RepProfileFilter {
        if (category != null) {
            category = category.migratedCategory();
            if (!category.isSelectable()) {
                throw new IllegalArgumentException("Profile filters require a selectable category");
            }
            positive = category.isPositive();
        }
    }

    static RepProfileFilter overall() {
        return new RepProfileFilter(null, null);
    }

    static RepProfileFilter polarity(boolean positive) {
        return new RepProfileFilter(positive, null);
    }

    static RepProfileFilter category(RepCategory category) {
        if (category == null) {
            return overall();
        }
        return new RepProfileFilter(category.isPositive(), category);
    }

    boolean isOverall() {
        return positive == null && category == null;
    }

    boolean isPolarity() {
        return positive != null && category == null;
    }

    boolean matches(Commendation entry) {
        if (entry == null) {
            return false;
        }
        if (category != null) {
            return entry.getCategory().migratedCategory() == category;
        }
        return positive == null || entry.isPositive() == positive;
    }

    List<Commendation> apply(List<Commendation> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        if (isOverall()) {
            return List.copyOf(entries);
        }
        return entries.stream().filter(this::matches).toList();
    }

    int score(List<Commendation> entries) {
        return apply(entries).stream().mapToInt(Commendation::getScoreValue).sum();
    }

    long count(List<Commendation> entries) {
        return apply(entries).size();
    }

    String displayName() {
        if (category != null) {
            return category.displayName();
        }
        if (positive == null) {
            return "All Reputation";
        }
        return positive ? "All Positive Reputation" : "All Negative Reputation";
    }
}
