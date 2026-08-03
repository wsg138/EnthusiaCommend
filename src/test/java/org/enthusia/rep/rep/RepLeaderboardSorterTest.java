package org.enthusia.rep.rep;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepLeaderboardSorterTest {
    @Test
    void sortsHighestAndLowestByProvidedViewValues() {
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        UUID c = new UUID(0, 3);
        Map<UUID, Integer> values = new LinkedHashMap<>();
        values.put(a, 4);
        values.put(b, -2);
        values.put(c, 9);
        assertEquals(List.of(c, a, b), RepLeaderboardSorter.sort(values, false).stream().map(Map.Entry::getKey).toList());
        assertEquals(List.of(b, a, c), RepLeaderboardSorter.sort(values, true).stream().map(Map.Entry::getKey).toList());
    }

    @Test
    void equalValuesUseStableUuidOrdering() {
        UUID first = new UUID(0, 1);
        UUID second = new UUID(0, 2);
        assertEquals(List.of(first, second), RepLeaderboardSorter.sort(Map.of(second, 0, first, 0), false)
                .stream().map(Map.Entry::getKey).toList());
    }
}
