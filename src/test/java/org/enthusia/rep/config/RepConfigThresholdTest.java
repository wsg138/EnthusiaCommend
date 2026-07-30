package org.enthusia.rep.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepConfigThresholdTest {
    @Test
    void detectsLeavingAnEffectAtItsExactBoundary() {
        RepConfig config = new RepConfig(new YamlConfiguration());
        assertTrue(config.crossedEffectThreshold(-10, -9));
        assertTrue(config.crossedEffectThreshold(10, 9));
        assertFalse(config.crossedEffectThreshold(-9, -8));
        assertFalse(config.crossedEffectThreshold(9, 8));
    }
}
