package org.enthusia.rep.rep;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure reputation rules used by runtime code and regression tests. */
public final class RepRules {
    public static final long RECIPROCITY_WINDOW_MILLIS = 24L * 60L * 60L * 1000L;
    public static final long CLUSTER_WINDOW_MILLIS = 6L * 60L * 60L * 1000L;
    public static final int CLUSTER_MIN_GIVERS = 3;

    private RepRules() {
    }

    public static Map<RepCategory, Integer> categoryScores(Collection<Commendation> commendations) {
        Map<RepCategory, Integer> scores = new EnumMap<>(RepCategory.class);
        if (commendations == null) {
            return scores;
        }
        for (Commendation commendation : commendations) {
            if (commendation == null) {
                continue;
            }
            scores.merge(commendation.getCategory().migratedCategory(), commendation.getScoreValue(), Integer::sum);
        }
        scores.values().removeIf(value -> value == 0);
        return scores;
    }

    public static RepCategory acceptedCategory(RepCategory category, boolean positive) {
        RepCategory candidate = category == null
                ? (positive ? RepCategory.WAS_KIND : RepCategory.SCAMMED)
                : category;
        return candidate.isSelectable() && candidate.isPositive() == positive ? candidate : null;
    }

    public static boolean isCooldownActive(long removedAt, long nowMillis, long cooldownMillis) {
        return cooldownMillis > 0L
                && nowMillis >= removedAt
                && nowMillis - removedAt < cooldownMillis;
    }

    public static boolean isRecentReciprocal(Commendation reverse, long nowMillis) {
        return reverse != null
                && nowMillis >= reverse.getLastEditedAt()
                && nowMillis - reverse.getLastEditedAt() <= RECIPROCITY_WINDOW_MILLIS;
    }

    public static Set<UUID> recentNegativeGivers(Collection<Commendation> commendations, long nowMillis) {
        Set<UUID> givers = new LinkedHashSet<>();
        if (commendations == null) {
            return givers;
        }
        long cutoff = nowMillis - CLUSTER_WINDOW_MILLIS;
        for (Commendation commendation : commendations) {
            if (commendation != null
                    && !commendation.isPositive()
                    && commendation.getLastEditedAt() >= cutoff
                    && commendation.getLastEditedAt() <= nowMillis) {
                givers.add(commendation.getGiver());
            }
        }
        return givers;
    }
}
