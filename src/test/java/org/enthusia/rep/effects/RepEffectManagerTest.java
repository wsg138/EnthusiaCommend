package org.enthusia.rep.effects;

import org.junit.jupiter.api.Test;

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
}
