package org.enthusia.rep.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogicalZoneResolverTest {
    @Test
    void overlappingMarketAndWarzoneResolvesAsMarket() {
        assertEquals(RegionManager.LogicalZone.MARKET,
                LogicalZoneResolver.resolve(true, false, true, true));
    }

    @Test
    void unmanagedWorldResolvesAsOther() {
        assertEquals(RegionManager.LogicalZone.OTHER,
                LogicalZoneResolver.resolve(true, true, true, false));
    }

    @Test
    void managedSpaceOutsideConfiguredProtectedCuboidsIsWilderness() {
        assertEquals(RegionManager.LogicalZone.WILDERNESS,
                LogicalZoneResolver.resolve(false, false, false, true));
    }
}
