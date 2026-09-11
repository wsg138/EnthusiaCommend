package org.enthusia.rep.gui;

import org.enthusia.rep.rep.Commendation;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

enum LeaderboardOrder {
    SCORE("Score"), RECENT("Most recent"), DAY("Recent: day"), WEEK("Recent: week");

    private final String label;
    LeaderboardOrder(String label) { this.label = label; }
    String label() { return label; }
    LeaderboardOrder next() { return values()[(ordinal() + 1) % values().length]; }

    List<Map.Entry<UUID, Integer>> sort(List<Map.Entry<UUID, Integer>> scores,
            List<Commendation> reviews, RepProfileFilter filter, long since) {
        if (this == SCORE) return scores;
        Map<UUID, Long> latest = reviews.stream().filter(filter::matches)
                .filter(review -> review.getLastEditedAt() >= since)
                .collect(Collectors.toUnmodifiableMap(Commendation::getTarget, Commendation::getLastEditedAt, Math::max));
        return scores.stream().filter(entry -> latest.containsKey(entry.getKey()))
                .sorted(Comparator.<Map.Entry<UUID, Integer>>comparingLong(entry -> latest.get(entry.getKey()))
                        .reversed().thenComparing(entry -> entry.getKey().toString())).toList();
    }
}
