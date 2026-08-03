package org.enthusia.rep.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Migrates only the exact region lists shipped before the production-zone update. */
public final class LegacyRegionConfigMigration {
    private static final Set<String> EXPECTED_KEYS = Set.of("world", "min", "max");
    private static final RegionValue OLD_SPAWN =
            new RegionValue("world", "-50, 0, -50", "50, 256, 50");
    private static final RegionValue NEW_SPAWN =
            new RegionValue("world", "-48, 0, -33", "69, 256, 84");
    private static final RegionValue OLD_WARZONE =
            new RegionValue("world", "-500, 0, -500", "500, 256, 500");
    private static final RegionValue NEW_WARZONE =
            new RegionValue("world", "-218, 0, -404", "219, 256, 188");

    private LegacyRegionConfigMigration() { }

    public static boolean migrate(FileConfiguration config) {
        if (config == null) {
            return false;
        }
        boolean changed = migrateExact(config, "regions.spawn", OLD_SPAWN, NEW_SPAWN);
        return migrateExact(config, "regions.warzone", OLD_WARZONE, NEW_WARZONE) || changed;
    }

    private static boolean migrateExact(FileConfiguration config, String path,
                                        RegionValue oldValue, RegionValue newValue) {
        List<Map<?, ?>> regions = config.getMapList(path);
        if (regions.size() != 1 || !matches(regions.getFirst(), oldValue)) {
            return false;
        }
        config.set(path, List.of(newValue.asMap()));
        return true;
    }

    private static boolean matches(Map<?, ?> raw, RegionValue expected) {
        if (raw == null || !raw.keySet().stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.toSet()).equals(EXPECTED_KEYS)) {
            return false;
        }
        return expected.world().equals(String.valueOf(raw.get("world")))
                && sameCoordinates(raw.get("min"), expected.minimum())
                && sameCoordinates(raw.get("max"), expected.maximum());
    }

    private static boolean sameCoordinates(Object actual, String expected) {
        int[] actualCoordinates = parseCoordinates(actual);
        int[] expectedCoordinates = parseCoordinates(expected);
        return actualCoordinates != null && expectedCoordinates != null
                && java.util.Arrays.equals(actualCoordinates, expectedCoordinates);
    }

    private static int[] parseCoordinates(Object value) {
        if (value == null) {
            return null;
        }
        String[] parts = String.valueOf(value).split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new int[] {
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                     Integer.parseInt(parts[2].trim())
            };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record RegionValue(String world, String minimum, String maximum) {
        private Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("world", world);
            value.put("min", minimum);
            value.put("max", maximum);
            return value;
        }
    }
}
