package org.enthusia.rep.rep;

/**
 * Categories for commendations (positive and negative).
 */
public enum RepCategory {
    // Positive
    WAS_KIND(true),
    HELPED_ME(true),
    GAVE_ITEMS(true),
    TRUSTWORTHY(true),
    GOOD_STALL(true),
    OTHER_POSITIVE(true),

    // Negative
    SCAMMED(false),
    SPAWN_KILLED(false),
    GRIEFED(false),
    TRAPPED(false),
    SCAM_STALL(false),
    OTHER_NEGATIVE(false);

    private final boolean positive;

    RepCategory(boolean positive) {
        this.positive = positive;
    }

    public boolean isPositive() {
        return positive;
    }
}
