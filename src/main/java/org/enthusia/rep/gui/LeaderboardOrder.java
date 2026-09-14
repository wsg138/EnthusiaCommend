package org.enthusia.rep.gui;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

enum LeaderboardOrder {
    SCORE("Score"), RECENT("Most recent"), DAY("Recent: day"), WEEK("Recent: week");

    private final String displayLabel;
    LeaderboardOrder(String label) { this.displayLabel = label; }
    String label() { return displayLabel; }
    LeaderboardOrder next() { return values()[(ordinal() + 1) % values().length]; }

    List<Map.Entry<UUID, Integer>> sort(List<Map.Entry<UUID, Integer>> scores, Map<UUID, Long> latest) {
        if (this == SCORE) return scores;
        return scores.stream().filter(entry -> latest.containsKey(entry.getKey()))
                .sorted(Comparator.<Map.Entry<UUID, Integer>>comparingLong(entry -> latest.get(entry.getKey()))
                        .reversed().thenComparing(entry -> entry.getKey().toString())).toList();
    }
}
