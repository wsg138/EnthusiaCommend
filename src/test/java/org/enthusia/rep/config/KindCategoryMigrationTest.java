package org.enthusia.rep.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KindCategoryMigrationTest {
    private static final String OVERALL_RULES = "rep.effectRules.overall";

    @Test
    void preservesCustomizedLegacyThresholdsWhenCreatingOverallRules() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("rep.effects.penalties.glowAt", -31);
        config.set("rep.effects.penalties.redGlowAt", -42);
        config.set("rep.effects.penalties.stalkableAt", -27);

        assertTrue(KindCategoryMigration.migrate(config));
        assertEquals(-31, threshold(config, "glow"));
        assertEquals(-42, threshold(config, "redGlow"));
        assertEquals(-27, threshold(config, "stalk"));
        assertEquals(10, config.getMapList(OVERALL_RULES).size());
    }

    @Test
    void doesNotOverwriteAnExplicitOverallRuleList() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("rep.effects.penalties.glowAt", -31);
        config.set(OVERALL_RULES, List.of(Map.of(
                "effect", "glow",
                "threshold", -99,
                "value", 1.0D
        )));

        assertFalse(KindCategoryMigration.migrate(config));
        assertEquals(-99, threshold(config, "glow"));
        assertEquals(1, config.getMapList(OVERALL_RULES).size());
    }

    private static int threshold(YamlConfiguration config, String effect) {
        return config.getMapList(OVERALL_RULES).stream()
                .filter(rule -> effect.equals(rule.get("effect")))
                .map(rule -> (Number) rule.get("threshold"))
                .mapToInt(Number::intValue)
                .findFirst()
                .orElseThrow();
    }
}
