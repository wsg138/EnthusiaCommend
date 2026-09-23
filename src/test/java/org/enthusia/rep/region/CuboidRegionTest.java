package org.enthusia.rep.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CuboidRegionTest {

    @Test
    void threeDimensionalBoundsAreInclusiveAndWorldScoped() {
        CuboidRegion region = new CuboidRegion("world", 1, 2, 3, 10, 20, 30);

        assertEquals("world", region.worldName());
        assertTrue(region.contains("world", 1, 2, 3));
        assertTrue(region.contains("world", 10, 20, 30));
        assertTrue(region.contains("world", 5, 10, 15));
        assertFalse(region.contains("world", 0, 10, 15));
        assertFalse(region.contains("world", 5, 21, 15));
        assertFalse(region.contains("world", 5, 10, 31));
        assertFalse(region.contains("other", 5, 10, 15));
        assertFalse(region.contains(null, 5, 10, 15));
    }

    @Test
    void horizontalContainmentIgnoresHeightButKeepsWorldAndEdgeRules() {
        CuboidRegion region = new CuboidRegion("world", -10, -64, -20, 10, 320, 20);

        assertTrue(region.containsHorizontally("world", -10, -20));
        assertTrue(region.containsHorizontally("world", 10, 20));
        assertFalse(region.containsHorizontally("world", 11, 0));
        assertFalse(region.containsHorizontally("other", 0, 0));
        assertFalse(region.containsHorizontally(null, 0, 0));
    }
}
