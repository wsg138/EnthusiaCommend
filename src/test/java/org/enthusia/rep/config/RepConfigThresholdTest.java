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
    void defaultEffectsOnlyIncludeTeleportGlowAndStalk() {
        RepConfig config = new RepConfig(new YamlConfiguration());

        assertEquals(0, config.resolveEffects(-6).pearlCooldownSeconds());
        assertEquals(0, config.resolveEffects(-6).fireworkDurationPercent());
        assertEquals(0, config.resolveEffects(-20).pearlCooldownSeconds());
        assertEquals(0, config.resolveEffects(-20).windCooldownSeconds());
        assertEquals(0, config.resolveEffects(-20).fireworkDurationPercent());
        assertEquals(0, config.resolveEffects(-20).potionDurationPercent());
        assertTrue(config.resolveEffects(-20).glow());
        assertTrue(config.resolveEffects(-20).stalkable());
        assertEquals(0, config.resolveEffects(15).potionDurationPercent());
        assertEquals(2.0 / 3, config.resolveEffects(15).teleportCooldownMultiplier());
        assertEquals(1.6, config.resolveEffects(-20).teleportCooldownMultiplier());
    }

    @Test
    void categoryPenaltiesPersistAndOverrideOverallRewards() {
        RepConfig config = new RepConfig(new YamlConfiguration());
        var effects = config.resolveEffects(50, java.util.Map.of(org.enthusia.rep.rep.RepCategory.SPAWN_KILLED, -12));
        assertTrue(effects.glow());
        assertTrue(effects.stalkable());
        assertEquals(1.4, effects.teleportCooldownMultiplier());
    }

    @Test
    void scopeRulesCanBeAddedDisabledOrReplacedWithoutStacking() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("rep.effectRules.overall", java.util.List.of());
        yaml.set("rep.effectRules.categories.SPAWN_KILLED", java.util.List.of());
        yaml.set("rep.effectRules.categories.WAS_KIND", java.util.List.of(
                java.util.Map.of("effect", "potion", "threshold", 2, "value", 10),
                java.util.Map.of("effect", "teleport", "threshold", 2, "value", .2, "enabled", false)));
        var effects = new RepConfig(yaml).resolveEffects(-50, java.util.Map.of(
                org.enthusia.rep.rep.RepCategory.SPAWN_KILLED, -20,
                org.enthusia.rep.rep.RepCategory.WAS_KIND, 3));
        assertFalse(effects.glow());
        assertEquals(10, effects.potionDurationPercent());
        assertEquals(1, effects.teleportCooldownMultiplier());
    }

    @Test
    void globallyDisabledEffectsAreRemovedFromCategoryRulesToo() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("rep.effectRules.disabledEffects", java.util.List.of("teleport", "stalk", "glow", "redGlow"));
        assertEquals(org.enthusia.rep.effects.RepAppliedEffects.NONE, new RepConfig(yaml).resolveEffects(-50,
                java.util.Map.of(org.enthusia.rep.rep.RepCategory.SCAMMED, -50)));
    }
}
