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
    void freshPackagedConfigurationContainsAllProductionDefaults() {
        Regions regions = loadRegions();
        assertNotNull(regions.market());
        assertNotNull(regions.spawn());
        assertNotNull(regions.warzone());
    }

    @Test
    void defaultsResolveGeneralAndStalkingOverlapsAtAnyHeight() {
        Regions regions = loadRegions();

        assertEquals(RegionManager.LogicalZone.WARZONE, resolveGeneral(regions, 0, -200));
        assertEquals(RegionManager.LogicalZone.MARKET, resolveStalking(regions, 0, -200));

        assertEquals(RegionManager.LogicalZone.SPAWN, resolveGeneral(regions, 0, 0));
        assertEquals(RegionManager.LogicalZone.SPAWN, resolveStalking(regions, 0, 0));

        assertEquals(RegionManager.LogicalZone.WARZONE, resolveGeneral(regions, 200, 100));
        assertEquals(RegionManager.LogicalZone.WARZONE, resolveStalking(regions, 200, 100));

        assertEquals(RegionManager.LogicalZone.WILDERNESS, resolveGeneral(regions, 300, 300));
        assertEquals(RegionManager.LogicalZone.WILDERNESS, resolveStalking(regions, 300, 300));
    }

    @Test
    void horizontalBoundariesCornersAndWorldChecksAreInclusive() {
        Regions regions = loadRegions();
        assertHorizontalInclusive(regions.market(), -72, 102, -281, -162);
        assertHorizontalInclusive(regions.spawn(), -48, 69, -33, 84);
        assertHorizontalInclusive(regions.warzone(), -218, 219, -404, 188);
    }

    @Test
    void generalPurposeCuboidStillHonorsY() {
        CuboidRegion region = RegionManager.parseRegion(WORLD, "-10, 5, -20", "10, 90, 20");
        assertNotNull(region);
        assertTrue(region.contains(WORLD, -10, 5, -20));
        assertTrue(region.contains(WORLD, 10, 90, 20));
        assertFalse(region.contains(WORLD, 0, 4, 0));
        assertFalse(region.contains(WORLD, 0, 91, 0));
        assertTrue(region.containsHorizontally(WORLD, 0, 0));
    }

    @Test
    void editedCoordinatesCanBeReparsedForRuntimeReload() {
        CuboidRegion edited = RegionManager.parseRegion(WORLD, "10, 5, 20", "-10, 90, -20");
        assertNotNull(edited);
        assertTrue(edited.containsHorizontally(WORLD, -10, -20));
        assertTrue(edited.containsHorizontally(WORLD, 10, 20));
        assertFalse(edited.containsHorizontally(WORLD, 11, 20));
    }

    private void assertHorizontalInclusive(CuboidRegion region, int minX, int maxX, int minZ, int maxZ) {
        assertTrue(region.containsHorizontally(WORLD, minX, minZ));
        assertTrue(region.containsHorizontally(WORLD, maxX, maxZ));
        assertTrue(region.containsHorizontally(WORLD, minX, maxZ));
        assertTrue(region.containsHorizontally(WORLD, maxX, minZ));
        assertFalse(region.containsHorizontally(WORLD, minX - 1, minZ));
        assertFalse(region.containsHorizontally(WORLD, maxX + 1, maxZ));
        assertFalse(region.containsHorizontally("different-world", minX, minZ));
    }

    private RegionManager.LogicalZone resolveGeneral(Regions regions, int x, int z) {
        return LogicalZoneResolver.resolveGeneral(
                regions.spawn().containsHorizontally(WORLD, x, z),
                regions.warzone().containsHorizontally(WORLD, x, z),
                true);
    }

    private RegionManager.LogicalZone resolveStalking(Regions regions, int x, int z) {
        return LogicalZoneResolver.resolveStalking(
                regions.market().containsHorizontally(WORLD, x, z),
                regions.spawn().containsHorizontally(WORLD, x, z),
                regions.warzone().containsHorizontally(WORLD, x, z),
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
