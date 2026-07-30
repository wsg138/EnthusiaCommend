package org.enthusia.rep.rep;

/**
 * Categories for player commendations. Legacy OTHER values remain readable so
 * existing data can be migrated, but they are not offered for new entries.
 */
public enum RepCategory {
    WAS_KIND(true, true),
    HELPED_ME(true, true),
    GAVE_ITEMS(true, true),
    TRUSTWORTHY(true, true),
    GOOD_STALL(true, true),
    OTHER_POSITIVE(true, false),

    SCAMMED(false, true),
    SPAWN_KILLED(false, true),
    GRIEFED(false, true),
    TRAPPED(false, true),
    SCAM_STALL(false, true),
    OTHER_NEGATIVE(false, false);

    private final boolean positive;
    private final boolean selectable;

    RepCategory(boolean positive, boolean selectable) {
        this.positive = positive;
        this.selectable = selectable;
    }

    public boolean isPositive() {
        return positive;
    }

    public boolean isSelectable() {
        return selectable;
    }

    public int defaultScoreValue() {
        return positive ? 1 : -2;
    }

    public RepCategory migratedCategory() {
        return switch (this) {
            case OTHER_POSITIVE -> WAS_KIND;
            case OTHER_NEGATIVE -> SCAMMED;
            default -> this;
        };
    }

    public static RepCategory fromStored(String raw, boolean positive) {
        if (raw == null || raw.isBlank()) {
            return positive ? WAS_KIND : SCAMMED;
        }
        try {
            return RepCategory.valueOf(raw).migratedCategory();
        } catch (IllegalArgumentException ignored) {
            return positive ? WAS_KIND : SCAMMED;
        }
    }
}
