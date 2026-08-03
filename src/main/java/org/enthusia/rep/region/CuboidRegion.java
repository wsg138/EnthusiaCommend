// src/main/java/org/enthusia/commend/region/CuboidRegion.java
package org.enthusia.rep.region;

import org.bukkit.Location;

public class CuboidRegion {

    private final String worldName;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;

    public CuboidRegion(String worldName, int minX, int minY, int minZ,
                        int maxX, int maxY, int maxZ) {
        this.worldName = worldName;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public String worldName() {
        return worldName;
    }

    public boolean contains(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        return contains(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public boolean contains(String locationWorld, int x, int y, int z) {
        if (locationWorld == null || !locationWorld.equals(worldName)) return false;
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean containsHorizontally(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        return containsHorizontally(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
    }

    public boolean containsHorizontally(String locationWorld, int x, int z) {
        if (locationWorld == null || !locationWorld.equals(worldName)) return false;
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}
