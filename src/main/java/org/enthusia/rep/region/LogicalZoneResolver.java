package org.enthusia.rep.region;

/** Pure precedence rules for overlapping configured logical regions. */
public final class LogicalZoneResolver {
    private LogicalZoneResolver() { }

    public static RegionManager.LogicalZone resolveGeneral(boolean inSpawn, boolean inWarzone,
                                                           boolean managedWorld) {
        if (!managedWorld) {
            return RegionManager.LogicalZone.OTHER;
        }
        if (inSpawn) {
            return RegionManager.LogicalZone.SPAWN;
        }
        if (inWarzone) {
            return RegionManager.LogicalZone.WARZONE;
        }
        return RegionManager.LogicalZone.WILDERNESS;
    }

    public static RegionManager.LogicalZone resolveStalking(boolean inMarket, boolean inSpawn,
                                                            boolean inWarzone, boolean managedWorld) {
        if (!managedWorld) {
            return RegionManager.LogicalZone.OTHER;
        }
        if (inMarket) {
            return RegionManager.LogicalZone.MARKET;
        }
        if (inSpawn) {
            return RegionManager.LogicalZone.SPAWN;
        }
        if (inWarzone) {
            return RegionManager.LogicalZone.WARZONE;
        }
        return RegionManager.LogicalZone.WILDERNESS;
    }
}
