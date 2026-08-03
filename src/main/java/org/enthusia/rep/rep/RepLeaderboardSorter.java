package org.enthusia.rep.rep;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure leaderboard sorting used by both runtime code and focused tests. */
public final class RepLeaderboardSorter {
    private RepLeaderboardSorter() { }

    public static List<Map.Entry<UUID, Integer>> sort(Map<UUID, Integer> values, boolean lowest) {
        Map<UUID, Integer> safe = values == null ? Map.of() : new LinkedHashMap<>(values);
        Comparator<Map.Entry<UUID, Integer>> comparator = Map.Entry.comparingByValue();
        if (!lowest) {
            comparator = comparator.reversed();
        }
        comparator = comparator.thenComparing(entry -> entry.getKey().toString());
        List<Map.Entry<UUID, Integer>> result = new ArrayList<>(safe.entrySet());
        result.sort(comparator);
        return List.copyOf(result);
    }
}
