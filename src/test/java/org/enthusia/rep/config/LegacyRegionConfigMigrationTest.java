package org.enthusia.rep.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyRegionConfigMigrationTest {
    @Test
    void untouchedOldSpawnDefaultMigrates() {
        YamlConfiguration config = configWith("regions.spawn",
                region("world", "-50, 0, -50", "50, 256, 50"));
        assertTrue(LegacyRegionConfigMigration.migrate(config));
        assertEquals(List.of(region("world", "-48, 0, -33", "69, 256, 84")),
                config.getMapList("regions.spawn"));
    }

    @Test
    void untouchedOldWarzoneDefaultMigrates() {
        YamlConfiguration config = configWith("regions.warzone",
                region("world", "-500, 0, -500", "500, 256, 500"));
        assertTrue(LegacyRegionConfigMigration.migrate(config));
        assertEquals(List.of(region("world", "-218, 0, -404", "219, 256, 188")),
                config.getMapList("regions.warzone"));
    }

    @Test
    void bothUntouchedDefaultsMigrateTogetherAndOnlyOnce() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("regions.spawn", List.of(region("world", "-50, 0, -50", "50, 256, 50")));
        config.set("regions.warzone", List.of(region("world", "-500, 0, -500", "500, 256, 500")));
        assertTrue(LegacyRegionConfigMigration.migrate(config));
        assertFalse(LegacyRegionConfigMigration.migrate(config));
    }

    @Test
    void customizedWorldCoordinatesAndMultipleRegionsArePreserved() {
        YamlConfiguration config = new YamlConfiguration();
        List<Map<String, Object>> customSpawn = List.of(
                region("custom", "-50, 0, -50", "50, 256, 50"),
                region("custom", "100, 0, 100", "120, 256, 120"));
        Map<String, Object> customWarzone = region("world", "-501, 0, -500", "500, 256, 500");
        config.set("regions.spawn", customSpawn);
        config.set("regions.warzone", List.of(customWarzone));

        assertFalse(LegacyRegionConfigMigration.migrate(config));
        assertEquals(customSpawn, config.getMapList("regions.spawn"));
        assertEquals(List.of(customWarzone), config.getMapList("regions.warzone"));
    }

    @Test
    void editedProductionCoordinatesAreNotRewrittenOnReload() {
        YamlConfiguration config = configWith("regions.spawn",
                region("world", "-40, 0, -30", "70, 256, 90"));
        assertFalse(LegacyRegionConfigMigration.migrate(config));
        assertEquals("-40, 0, -30", config.getMapList("regions.spawn").getFirst().get("min"));
    }

    private YamlConfiguration configWith(String path, Map<String, Object> value) {
        YamlConfiguration config = new YamlConfiguration();
        config.set(path, List.of(value));
        return config;
    }

    private Map<String, Object> region(String world, String min, String max) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("world", world);
        value.put("min", min);
        value.put("max", max);
        return value;
    }
}
