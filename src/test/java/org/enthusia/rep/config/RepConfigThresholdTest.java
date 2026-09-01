package org.enthusia.rep.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void preservesConfiguredEffectProgression() {
        RepConfig config = new RepConfig(new YamlConfiguration());

        assertEquals(3, config.resolveEffects(-6).pearlCooldownSeconds());
        assertEquals(-5, config.resolveEffects(-6).fireworkDurationPercent());
        assertEquals(10, config.resolveEffects(-20).pearlCooldownSeconds());
        assertEquals(10, config.resolveEffects(-20).windCooldownSeconds());
        assertEquals(-25, config.resolveEffects(-20).fireworkDurationPercent());
        assertEquals(-15, config.resolveEffects(-20).potionDurationPercent());
        assertTrue(config.resolveEffects(-20).glow());
        assertTrue(config.resolveEffects(-20).stalkable());
        assertEquals(10, config.resolveEffects(15).potionDurationPercent());
        assertEquals(5, config.resolveEffects(15).cashbackPercent());
    }
}
