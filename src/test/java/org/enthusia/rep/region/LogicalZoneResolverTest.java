package org.enthusia.rep.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogicalZoneResolverTest {
    @Test
    void overlappingMarketAndWarzoneIsWarzoneForGeneralGameplay() {
        assertEquals(RegionManager.LogicalZone.WARZONE,
                LogicalZoneResolver.resolveGeneral(false, true, true));
    }

    @Test
    void overlappingMarketAndWarzoneIsMarketForStalking() {
        assertEquals(RegionManager.LogicalZone.MARKET,
                LogicalZoneResolver.resolveStalking(true, false, true, true));
    }

    @Test
    void overlappingSpawnAndWarzoneResolvesAsSpawnForBothClassifications() {
        assertEquals(RegionManager.LogicalZone.SPAWN,
                LogicalZoneResolver.resolveGeneral(true, true, true));
        assertEquals(RegionManager.LogicalZone.SPAWN,
                LogicalZoneResolver.resolveStalking(false, true, true, true));
    }

    @Test
    void warzoneOnlySpaceResolvesAsWarzoneForBothClassifications() {
        assertEquals(RegionManager.LogicalZone.WARZONE,
                LogicalZoneResolver.resolveGeneral(false, true, true));
        assertEquals(RegionManager.LogicalZone.WARZONE,
                LogicalZoneResolver.resolveStalking(false, false, true, true));
    }

    @Test
    void unmanagedWorldResolvesAsOtherForBothClassifications() {
        assertEquals(RegionManager.LogicalZone.OTHER,
                LogicalZoneResolver.resolveGeneral(true, true, false));
        assertEquals(RegionManager.LogicalZone.OTHER,
                LogicalZoneResolver.resolveStalking(true, true, true, false));
    }

    @Test
    void managedSpaceOutsideConfiguredProtectedCuboidsIsWilderness() {
        assertEquals(RegionManager.LogicalZone.WILDERNESS,
                LogicalZoneResolver.resolveGeneral(false, false, true));
        assertEquals(RegionManager.LogicalZone.WILDERNESS,
                LogicalZoneResolver.resolveStalking(false, false, false, true));
    }
}
