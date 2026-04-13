package org.enthusia.rep.region;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.enthusia.rep.CommendPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RegionManager {

    private final List<CuboidRegion> spawnRegions = new ArrayList<>();
    private final List<CuboidRegion> warzoneRegions = new ArrayList<>();

    public RegionManager(CommendPlugin plugin) {
        reload(plugin);
    }

    public void reload(CommendPlugin plugin) {
        spawnRegions.clear();
        warzoneRegions.clear();

        FileConfiguration config = plugin.getConfig();

        loadRegionList(config, "regions.spawn", spawnRegions);
        loadRegionList(config, "regions.warzone", warzoneRegions);

        plugin.getLogger().info("Loaded " + spawnRegions.size() + " spawn regions and "
                + warzoneRegions.size() + " warzone regions.");
    }

    @SuppressWarnings("unchecked")
    private void loadRegionList(FileConfiguration config, String path, List<CuboidRegion> out) {
        List<Map<?, ?>> list = config.getMapList(path);
        if (list == null || list.isEmpty()) return;

        for (Map<?, ?> map : list) {
            if (map == null) continue;

            Object worldObj = map.get("world");
            Object minObj = map.get("min");
            Object maxObj = map.get("max");

            if (worldObj == null || minObj == null || maxObj == null) continue;

            String worldName = String.valueOf(worldObj);
            World world = Bukkit.getWorld(worldName);
            if (world == null) continue;

            int[] min = parseCoord(String.valueOf(minObj));
            int[] max = parseCoord(String.valueOf(maxObj));
            if (min == null || max == null) continue;

            CuboidRegion region = new CuboidRegion(
                    world.getName(),
                    Math.min(min[0], max[0]),
                    Math.min(min[1], max[1]),
                    Math.min(min[2], max[2]),
                    Math.max(min[0], max[0]),
                    Math.max(min[1], max[1]),
                    Math.max(min[2], max[2])
            );
            out.add(region);
        }
    }

    private int[] parseCoord(String value) {
        String[] parts = value.split(",");
        if (parts.length != 3) return null;
        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int z = Integer.parseInt(parts[2].trim());
            return new int[]{x, y, z};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean isInSpawn(Location loc) {
        if (loc == null) return false;
        for (CuboidRegion region : spawnRegions) {
            if (region.contains(loc)) return true;
        }
        return false;
    }

    public boolean isInWarzone(Location loc) {
        if (loc == null) return false;
        for (CuboidRegion region : warzoneRegions) {
            if (region.contains(loc)) return true;
        }
        return false;
    }

    public boolean isInSpawnOrWarzone(Location loc) {
        return isInSpawn(loc) || isInWarzone(loc);
    }
}
