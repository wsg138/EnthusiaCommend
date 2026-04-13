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

    public boolean contains(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        if (!loc.getWorld().getName().equals(worldName)) return false;
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }
}
