package org.enthusia.rep.region;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionDefaultsTest {
    private static final String WORLD = "world";

    @Test
    void packagedProductionDefaultsResolveOverlapsWithRequiredPrecedence() {
        Regions regions = loadRegions();

        assertEquals(RegionManager.LogicalZone.MARKET, resolve(regions, 0, 64, -200));
        assertEquals(RegionManager.LogicalZone.SPAWN, resolve(regions, 0, 64, 0));
        assertEquals(RegionManager.LogicalZone.WARZONE, resolve(regions, 200, 64, 100));
        assertEquals(RegionManager.LogicalZone.WILDERNESS, resolve(regions, 300, 64, 300));
    }

    @Test
    void packagedCuboidBoundariesAreInclusiveAndNormalized() {
        Regions regions = loadRegions();

        assertInclusive(regions.market(), -72, 102, -281, -162);
        assertInclusive(regions.spawn(), -48, 69, -33, 84);
        assertInclusive(regions.warzone(), -218, 219, -404, 188);
    }

    @Test
    void editedCoordinatesCanBeReparsedForRuntimeReload() {
        CuboidRegion edited = RegionManager.parseRegion(WORLD, "10, 5, 20", "-10, 90, -20");

        assertNotNull(edited);
        assertTrue(edited.contains(WORLD, -10, 5, -20));
        assertTrue(edited.contains(WORLD, 10, 90, 20));
        assertFalse(edited.contains(WORLD, 11, 90, 20));
    }

    private void assertInclusive(CuboidRegion region, int minX, int maxX, int minZ, int maxZ) {
        assertTrue(region.contains(WORLD, minX, 0, minZ));
        assertTrue(region.contains(WORLD, maxX, 256, maxZ));
        assertFalse(region.contains(WORLD, minX - 1, 0, minZ));
        assertFalse(region.contains(WORLD, maxX + 1, 256, maxZ));
        assertFalse(region.contains(WORLD, minX, -1, minZ));
        assertFalse(region.contains(WORLD, maxX, 257, maxZ));
        assertFalse(region.contains("different-world", minX, 0, minZ));
    }

    private RegionManager.LogicalZone resolve(Regions regions, int x, int y, int z) {
        return LogicalZoneResolver.resolve(
                regions.market().contains(WORLD, x, y, z),
                regions.spawn().contains(WORLD, x, y, z),
                regions.warzone().contains(WORLD, x, y, z),
                true);
    }

    private Regions loadRegions() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("config.yml");
        assertNotNull(stream);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        return new Regions(
                loadRegion(config, "regions.market"),
                loadRegion(config, "regions.spawn"),
                loadRegion(config, "regions.warzone"));
    }

    private CuboidRegion loadRegion(YamlConfiguration config, String path) {
        List<Map<?, ?>> entries = config.getMapList(path);
        assertEquals(1, entries.size());
        Map<?, ?> entry = entries.getFirst();
        CuboidRegion region = RegionManager.parseRegion(
                String.valueOf(entry.get("world")), entry.get("min"), entry.get("max"));
        assertNotNull(region);
        return region;
    }

    private record Regions(CuboidRegion market, CuboidRegion spawn, CuboidRegion warzone) { }
}
