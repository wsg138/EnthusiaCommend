package org.enthusia.rep.region;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.enthusia.rep.CommendPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RegionManager {
    private volatile List<CuboidRegion> marketRegions = List.of();
    private volatile List<CuboidRegion> spawnRegions = List.of();
    private volatile List<CuboidRegion> warzoneRegions = List.of();
    private volatile Set<String> managedWorlds = Set.of();

    public RegionManager(CommendPlugin plugin) {
        reload(plugin.getConfig(), plugin);
    }

    public void reload(FileConfiguration config, CommendPlugin plugin) {
        List<CuboidRegion> market = new ArrayList<>();
        List<CuboidRegion> spawn = new ArrayList<>();
        List<CuboidRegion> warzone = new ArrayList<>();
        loadRegionList(config, "regions.market", market);
        loadRegionList(config, "regions.spawn", spawn);
        loadRegionList(config, "regions.warzone", warzone);
        this.marketRegions = List.copyOf(market);
        this.spawnRegions = List.copyOf(spawn);
        this.warzoneRegions = List.copyOf(warzone);
        Set<String> worlds = new HashSet<>();
        market.forEach(region -> worlds.add(region.worldName()));
        spawn.forEach(region -> worlds.add(region.worldName()));
        warzone.forEach(region -> worlds.add(region.worldName()));
        this.managedWorlds = Set.copyOf(worlds);
        plugin.getLogger().info("Loaded logical reputation zones: " + marketRegions.size() + " market, "
                + spawnRegions.size() + " spawn, and " + warzoneRegions.size() + " warzone cuboids.");
    }

    private void loadRegionList(FileConfiguration config, String path, List<CuboidRegion> out) {
        List<Map<?, ?>> list = config.getMapList(path);
        for (Map<?, ?> map : list) {
            if (map == null || map.get("world") == null) {
                continue;
            }
            String worldName = String.valueOf(map.get("world"));
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                continue;
            }
            int[] min = parseCoord(String.valueOf(map.get("min")));
            int[] max = parseCoord(String.valueOf(map.get("max")));
            if (min == null || max == null) {
                continue;
            }
            out.add(new CuboidRegion(worldName,
                    Math.min(min[0], max[0]), Math.min(min[1], max[1]), Math.min(min[2], max[2]),
                    Math.max(min[0], max[0]), Math.max(min[1], max[1]), Math.max(min[2], max[2])));
        }
    }

    private int[] parseCoord(String value) {
        String[] parts = value.split(",");
        if (parts.length != 3) return null;
        try {
            return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public LogicalZone resolveZone(Location location) {
        boolean managed = location != null && location.getWorld() != null
                && managedWorlds.contains(location.getWorld().getName());
        return LogicalZoneResolver.resolve(isInMarket(location), isInSpawn(location), isInWarzone(location), managed);
    }

    public boolean isInMarket(Location location) { return contains(marketRegions, location); }
    public boolean isInSpawn(Location location) { return contains(spawnRegions, location); }
    public boolean isInWarzone(Location location) { return contains(warzoneRegions, location); }

    public boolean isInSpawnOrWarzone(Location location) {
        LogicalZone zone = resolveZone(location);
        return zone == LogicalZone.SPAWN || zone == LogicalZone.MARKET || zone == LogicalZone.WARZONE;
    }

    private boolean contains(List<CuboidRegion> regions, Location location) {
        if (location == null) return false;
        for (CuboidRegion region : regions) {
            if (region.contains(location)) return true;
        }
        return false;
    }

    public enum LogicalZone {
        SPAWN,
        MARKET,
        WARZONE,
        WILDERNESS,
        OTHER
    }
}
