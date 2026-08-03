package org.enthusia.rep.effects;

import org.bukkit.Material;
import org.enthusia.rep.region.LogicalZoneResolver;
import org.enthusia.rep.region.RegionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepEffectManagerTest {
    @Test
    void onlyNewRefreshedOrAmplifierChangedEffectsAreModified() {
        assertFalse(RepEffectManager.wasPotionEffectAppliedOrRefreshed(true, 200, 0, 199, 0));
        assertTrue(RepEffectManager.wasPotionEffectAppliedOrRefreshed(true, 200, 0, 240, 0));
        assertTrue(RepEffectManager.wasPotionEffectAppliedOrRefreshed(true, 200, 0, 199, 1));
        assertTrue(RepEffectManager.wasPotionEffectAppliedOrRefreshed(false, 0, 0, 200, 0));
    }

    @Test
    void marketRemainsEligibleForPearlAndWindChargeWarzonePenalties() {
        RegionManager.LogicalZone marketGameplayZone =
                LogicalZoneResolver.resolveGeneral(false, true, true);
        assertEquals(RegionManager.LogicalZone.WARZONE, marketGameplayZone);
        assertTrue(isWarzoneCooldownEligible(marketGameplayZone, Material.ENDER_PEARL));
        assertTrue(isWarzoneCooldownEligible(marketGameplayZone, Material.WIND_CHARGE));
        assertFalse(isWarzoneCooldownEligible(marketGameplayZone, Material.FIREWORK_ROCKET));
    }

    private boolean isWarzoneCooldownEligible(RegionManager.LogicalZone zone, Material material) {
        return zone == RegionManager.LogicalZone.WARZONE
                && (material == Material.ENDER_PEARL || material == Material.WIND_CHARGE);
    }
}
