package org.enthusia.rep.rep;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds category leaderboard membership from actual category records. */
public final class RepLeaderboardPopulation {
    private RepLeaderboardPopulation() { }

    public static Map<UUID, Integer> categoryTotals(Collection<Commendation> commendations,
                                                    RepCategory category) {
        if (category == null) {
            return Map.of();
        }
        List<CategoryEntry> entries = commendations == null ? List.of() : commendations.stream()
                .filter(java.util.Objects::nonNull)
                .map(entry -> new CategoryEntry(entry.getTarget(), entry.getCategory(), entry.getScoreValue()))
                .toList();
        return categoryTotalsFromEntries(entries, category);
    }

    static Map<UUID, Integer> categoryTotalsFromEntries(Collection<CategoryEntry> entries,
                                                        RepCategory category) {
        if (category == null) {
            return Map.of();
        }
        RepCategory canonical = category.migratedCategory();
        Map<UUID, Integer> totals = new LinkedHashMap<>();
        if (entries != null) {
            for (CategoryEntry entry : entries) {
                if (entry != null && entry.playerId() != null && entry.category() != null
                        && entry.category().migratedCategory() == canonical) {
                    totals.merge(entry.playerId(), entry.value(), Integer::sum);
                }
            }
        }
        return Map.copyOf(totals);
    }

    record CategoryEntry(UUID playerId, RepCategory category, int value) { }
}
