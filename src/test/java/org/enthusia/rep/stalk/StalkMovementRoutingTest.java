package org.enthusia.rep.stalk;

import org.enthusia.rep.region.RegionManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StalkMovementRoutingTest {
    @Test
    void sameWorldMovementIntoWarzoneAlertsOnceFromEachSourceZone() {
        for (RegionManager.LogicalZone source : alertingSourceZones()) {
            assertAlertsOnce(StalkMovementRouting.forMove(false, false), source);
        }
    }

    @Test
    void sameWorldTeleportIntoWarzoneAlertsOnceFromEachSourceZone() {
        for (RegionManager.LogicalZone source : alertingSourceZones()) {
            assertAlertsOnce(StalkMovementRouting.forTeleport(true), source);
        }
    }

    @Test
    void teleportIsIgnoredByTheRegularMoveHandlerAndProcessedOnceByTeleportHandler() {
        StalkMovementRouting.Observation ignored = StalkMovementRouting.observe(
                StalkMovementRouting.forMove(true, false),
                RegionManager.LogicalZone.SPAWN,
                RegionManager.LogicalZone.WARZONE);
        assertFalse(ignored.alert());
        assertEquals(RegionManager.LogicalZone.SPAWN, ignored.rememberedZone());

        StalkMovementRouting.Observation teleported = StalkMovementRouting.observe(
                StalkMovementRouting.forTeleport(true),
                ignored.rememberedZone(),
                RegionManager.LogicalZone.WARZONE);
        assertTrue(teleported.alert());
        assertEquals(RegionManager.LogicalZone.WARZONE, teleported.rememberedZone());
    }

    @Test
    void crossWorldTeleportEstablishesWarzoneBaselineWithoutAlerting() {
        StalkMovementRouting.Observation baseline = StalkMovementRouting.observe(
                StalkMovementRouting.forTeleport(false),
                RegionManager.LogicalZone.SPAWN,
                RegionManager.LogicalZone.WARZONE);
        assertFalse(baseline.alert());
        assertEquals(RegionManager.LogicalZone.WARZONE, baseline.rememberedZone());

        StalkMovementRouting.Observation movementWithinWarzone = StalkMovementRouting.observe(
                StalkMovementRouting.forMove(false, false),
                baseline.rememberedZone(),
                RegionManager.LogicalZone.WARZONE);
        assertFalse(movementWithinWarzone.alert());
    }

    @Test
    void worldChangeEstablishesBaselineThenNextGenuineWarzoneEntryAlertsOnce() {
        StalkMovementRouting.Observation baseline = StalkMovementRouting.observe(
                StalkMovementRouting.forWorldChange(),
                RegionManager.LogicalZone.OTHER,
                RegionManager.LogicalZone.SPAWN);
        assertFalse(baseline.alert());
        assertEquals(RegionManager.LogicalZone.SPAWN, baseline.rememberedZone());

        StalkMovementRouting.Observation entry = StalkMovementRouting.observe(
                StalkMovementRouting.forMove(false, false),
                baseline.rememberedZone(),
                RegionManager.LogicalZone.WARZONE);
        assertTrue(entry.alert());

        StalkMovementRouting.Observation duplicate = StalkMovementRouting.observe(
                StalkMovementRouting.forMove(false, false),
                entry.rememberedZone(),
                RegionManager.LogicalZone.WARZONE);
        assertFalse(duplicate.alert());
    }

    @Test
    void movementWithinMarketAndWithinWarzoneDoesNotAlert() {
        assertNoAlertWithin(RegionManager.LogicalZone.MARKET);
        assertNoAlertWithin(RegionManager.LogicalZone.WARZONE);
    }

    private void assertAlertsOnce(StalkMovementRouting.Mode mode, RegionManager.LogicalZone source) {
        StalkMovementRouting.Observation entry = StalkMovementRouting.observe(
                mode, source, RegionManager.LogicalZone.WARZONE);
        assertTrue(entry.alert(), () -> source + " should alert on entry");
        assertEquals(RegionManager.LogicalZone.WARZONE, entry.rememberedZone());

        StalkMovementRouting.Observation duplicate = StalkMovementRouting.observe(
                mode, entry.rememberedZone(), RegionManager.LogicalZone.WARZONE);
        assertFalse(duplicate.alert(), () -> source + " should alert only once");
    }

    private void assertNoAlertWithin(RegionManager.LogicalZone zone) {
        StalkMovementRouting.Observation observation = StalkMovementRouting.observe(
                StalkMovementRouting.forMove(false, false), zone, zone);
        assertFalse(observation.alert());
        assertEquals(zone, observation.rememberedZone());
    }

    private List<RegionManager.LogicalZone> alertingSourceZones() {
        return List.of(
                RegionManager.LogicalZone.SPAWN,
                RegionManager.LogicalZone.MARKET,
                RegionManager.LogicalZone.WILDERNESS);
    }
}
