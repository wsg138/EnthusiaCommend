package org.enthusia.rep.stalk;

import org.enthusia.rep.region.RegionManager;

/** Decision policy kept free of Bukkit so every transition is unit-testable. */
public final class StalkZoneTransition {
    private StalkZoneTransition() { }

    public static boolean shouldAlert(RegionManager.LogicalZone previous, RegionManager.LogicalZone destination) {
        if (previous == null || destination != RegionManager.LogicalZone.WARZONE) {
            return false;
        }
        return previous == RegionManager.LogicalZone.SPAWN
                || previous == RegionManager.LogicalZone.MARKET
                || previous == RegionManager.LogicalZone.WILDERNESS;
    }
}
