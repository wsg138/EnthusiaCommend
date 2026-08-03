package org.enthusia.rep.stalk;

import org.enthusia.rep.region.RegionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StalkZoneTransitionTest {
    @Test void spawnToWarzoneAlerts() { assertTrue(alert(RegionManager.LogicalZone.SPAWN, RegionManager.LogicalZone.WARZONE)); }
    @Test void marketToWarzoneAlerts() { assertTrue(alert(RegionManager.LogicalZone.MARKET, RegionManager.LogicalZone.WARZONE)); }
    @Test void wildernessToWarzoneAlerts() { assertTrue(alert(RegionManager.LogicalZone.WILDERNESS, RegionManager.LogicalZone.WARZONE)); }
    @Test void warzoneToWildernessDoesNotAlert() { assertFalse(alert(RegionManager.LogicalZone.WARZONE, RegionManager.LogicalZone.WILDERNESS)); }
    @Test void warzoneToSpawnDoesNotAlert() { assertFalse(alert(RegionManager.LogicalZone.WARZONE, RegionManager.LogicalZone.SPAWN)); }
    @Test void warzoneToMarketDoesNotAlert() { assertFalse(alert(RegionManager.LogicalZone.WARZONE, RegionManager.LogicalZone.MARKET)); }
    @Test void warzoneToWarzoneDoesNotAlert() { assertFalse(alert(RegionManager.LogicalZone.WARZONE, RegionManager.LogicalZone.WARZONE)); }
    @Test void marketToMarketDoesNotAlert() { assertFalse(alert(RegionManager.LogicalZone.MARKET, RegionManager.LogicalZone.MARKET)); }
    @Test void initialStateAssignmentDoesNotAlert() { assertFalse(StalkZoneTransition.shouldAlert(null, RegionManager.LogicalZone.WARZONE)); }

    @Test
    void cancelledAttemptLeavesPreviousZoneForTheNextGenuineTransition() {
        RegionManager.LogicalZone remembered = RegionManager.LogicalZone.SPAWN;
        assertTrue(alert(remembered, RegionManager.LogicalZone.WARZONE));
        remembered = RegionManager.LogicalZone.WARZONE;
        assertFalse(alert(remembered, RegionManager.LogicalZone.WARZONE));
    }

    private boolean alert(RegionManager.LogicalZone from, RegionManager.LogicalZone to) {
        return StalkZoneTransition.shouldAlert(from, to);
    }
}
