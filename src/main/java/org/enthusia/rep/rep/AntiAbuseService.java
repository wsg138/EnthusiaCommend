package org.enthusia.rep.rep;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-abuse logic isolated from the main RepService.
 * Tracks rep activity, detects reciprocity (rep trading) and
 * cluster-downrep (mass brigading), and maintains alert state.
 */
public class AntiAbuseService {

    private static final long RECIPROCITY_WINDOW_MS = 24L * 60L * 60L * 1000L;
    private static final long CLUSTER_WINDOW_MS     = 6L  * 60L * 60L * 1000L;
    private static final int  CLUSTER_MIN_GIVERS     = 3;

    private final Map<String, Long> activityLog = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Long>> byTargetActivity = new ConcurrentHashMap<>();
    private final RepService repService;

    public AntiAbuseService(RepService repService) {
        this.repService = repService;
    }

    public void recordActivity(UUID giver, UUID target) {
        long now = System.currentTimeMillis();
        String key = giver.toString() + "->" + target.toString();
        activityLog.put(key, now);
        byTargetActivity.computeIfAbsent(target, k -> new ConcurrentHashMap<>()).put(giver, now);
    }

    public RepService.SuspiciousRepCase checkReciprocity(UUID giver, UUID target) {
        String reverseKey = target.toString() + "->" + giver.toString();
        Long reverseTime = activityLog.get(reverseKey);
        if (reverseTime == null) return null;
        long elapsed = System.currentTimeMillis() - reverseTime;
        if (elapsed > RECIPROCITY_WINDOW_MS) return null;
        RepService.SuspiciousRepCase c = new RepService.SuspiciousRepCase(target, AlertType.RECIPROCITY,
                List.of(giver, target));
        c.setDetail("Rep trading: " + repService.nameOf(giver) + " and "
                + repService.nameOf(target) + " exchanged rep within " + (elapsed / 3600000) + "h");
        return c;
    }

    public RepService.SuspiciousRepCase checkNegativeCluster(UUID target, List<RepService.SuspiciousRepCase> existingAlerts) {
        long now = System.currentTimeMillis();
        long cutoff = now - CLUSTER_WINDOW_MS;
        Map<UUID, Long> recentMap = byTargetActivity.get(target);
        if (recentMap == null) return null;

        Set<UUID> recentDownreppers = new HashSet<>();
        for (Map.Entry<UUID, Long> entry : recentMap.entrySet()) {
            if (entry.getValue() < cutoff) continue;
            Commendation c = repService.getCommendation(entry.getKey(), target);
            if (c != null && !c.isPositive()) {
                recentDownreppers.add(entry.getKey());
            }
        }
        if (recentDownreppers.size() < CLUSTER_MIN_GIVERS) return null;

        boolean alreadyReported = existingAlerts.stream()
                .anyMatch(sc -> sc.getTarget().equals(target)
                        && sc.getAlertType() == AlertType.CLUSTER_DOWNREP
                        && now - sc.getCreatedAt() < CLUSTER_WINDOW_MS);
        if (alreadyReported) return null;

        RepService.SuspiciousRepCase c = new RepService.SuspiciousRepCase(target, AlertType.CLUSTER_DOWNREP,
                new ArrayList<>(recentDownreppers));
        c.setDetail("Mass downrep: " + recentDownreppers.size() + " players downrepped "
                + repService.nameOf(target) + " within " + (CLUSTER_WINDOW_MS / 3600000) + "h");
        return c;
    }

    public void pruneStale() {
        long cutoff = System.currentTimeMillis() - Math.max(RECIPROCITY_WINDOW_MS, CLUSTER_WINDOW_MS);
        activityLog.entrySet().removeIf(e -> e.getValue() < cutoff);
        byTargetActivity.values().forEach(m -> m.values().removeIf(v -> v < cutoff));
        byTargetActivity.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
}
