package org.enthusia.rep.rep;

import org.bukkit.Material;

import java.util.Arrays;
import java.util.List;

/**
 * Authoritative registry for reputation categories shown in commands, GUIs,
 * leaderboards, analytics, and Discord logs. Legacy OTHER values remain
 * readable for data migration but are never presented as selectable views.
 */
public enum RepCategory {
    WAS_KIND(true, true, "Was Kind", "Friendly or considerate behavior.", Material.PINK_TULIP),
    HELPED_ME(true, true, "Helped Me", "Provided useful help or support.", Material.GOLDEN_CARROT),
    GAVE_ITEMS(true, true, "Gave Items/Money", "Gave items or money fairly.", Material.EMERALD),
    TRUSTWORTHY(true, true, "Trustworthy", "Kept promises and acted reliably.", Material.SHIELD),
    GOOD_STALL(true, true, "Good Stall", "Ran a fair and reliable market stall.", Material.CHEST),
    OTHER_POSITIVE(true, false, "Was Kind (migrated)", "Legacy positive reputation migrated to Was Kind.", Material.LIME_DYE),

    SCAMMED(false, true, "Scammed", "Scammed or deliberately misled another player.", Material.TRIPWIRE_HOOK),
    SPAWN_KILLED(false, true, "Spawn Killed", "Killed players unfairly around spawn.", Material.IRON_SWORD),
    GRIEFED(false, true, "Griefed", "Damaged or destroyed another player's build.", Material.TNT),
    TRAPPED(false, true, "Trapped", "Used a trap unfairly against another player.", Material.COBWEB),
    SCAM_STALL(false, true, "Scam Stall", "Ran a misleading or dishonest market stall.", Material.BARREL),
    OTHER_NEGATIVE(false, false, "Scammed (migrated)", "Legacy negative reputation migrated to Scammed.", Material.RED_DYE);

    private static final List<RepCategory> SELECTABLE = Arrays.stream(values())
            .filter(RepCategory::isSelectable)
            .toList();

    private final boolean positive;
    private final boolean selectable;
    private final String displayName;
    private final String description;
    private final Material icon;

    RepCategory(boolean positive, boolean selectable, String displayName, String description, Material icon) {
        this.positive = positive;
        this.selectable = selectable;
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
    }

    public boolean isPositive() { return positive; }
    public boolean isSelectable() { return selectable; }
    public String displayName() { return displayName; }
    public String description() { return description; }
    public Material icon() { return icon; }
    public int defaultScoreValue() { return positive ? 1 : -2; }

    public RepCategory migratedCategory() {
        return switch (this) {
            case OTHER_POSITIVE -> WAS_KIND;
            case OTHER_NEGATIVE -> SCAMMED;
            default -> this;
        };
    }

    public static List<RepCategory> selectableValues() {
        return SELECTABLE;
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
