package org.enthusia.rep.storage;

import org.enthusia.rep.analytics.ReputationChangeRecord;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PluginDataSnapshot(
        Map<UUID, Integer> scores,
        List<Commendation> commendations,
        List<RepService.RemovedRep> removedEntries,
        List<StalkEntry> stalkEntries,
        List<ReputationChangeRecord> reputationChanges,
        List<RepService.SuspiciousRepCase> suspiciousCases,
        List<RemovalCooldownEntry> removalCooldowns,
        Map<UUID, Boolean> repTradingAlertPreferences
) {
    public PluginDataSnapshot(
            Map<UUID, Integer> scores,
            List<Commendation> commendations,
            List<RepService.RemovedRep> removedEntries,
            List<StalkEntry> stalkEntries,
            List<ReputationChangeRecord> reputationChanges,
            List<RepService.SuspiciousRepCase> suspiciousCases
    ) {
        this(scores, commendations, removedEntries, stalkEntries, reputationChanges,
                suspiciousCases, List.of(), Map.of());
    }

    public PluginDataSnapshot(
            Map<UUID, Integer> scores,
            List<Commendation> commendations,
            List<RepService.RemovedRep> removedEntries,
            List<StalkEntry> stalkEntries,
            List<ReputationChangeRecord> reputationChanges,
            List<RepService.SuspiciousRepCase> suspiciousCases,
            List<RemovalCooldownEntry> removalCooldowns
    ) {
        this(scores, commendations, removedEntries, stalkEntries, reputationChanges,
                suspiciousCases, removalCooldowns, Map.of());
    }

    public PluginDataSnapshot {
        scores = scores == null ? Map.of() : Map.copyOf(scores);
        commendations = commendations == null ? List.of() : List.copyOf(commendations);
        removedEntries = removedEntries == null ? List.of() : List.copyOf(removedEntries);
        stalkEntries = stalkEntries == null ? List.of() : List.copyOf(stalkEntries);
        reputationChanges = reputationChanges == null ? List.of() : List.copyOf(reputationChanges);
        suspiciousCases = suspiciousCases == null ? List.of() : List.copyOf(suspiciousCases);
        removalCooldowns = removalCooldowns == null ? List.of() : List.copyOf(removalCooldowns);
        repTradingAlertPreferences = repTradingAlertPreferences == null
                ? Map.of() : Map.copyOf(repTradingAlertPreferences);
    }

    public static final PluginDataSnapshot EMPTY = new PluginDataSnapshot(
            Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());

    public record StalkEntry(UUID stalkerId, UUID targetId, long expiresAt) { }
    public record RemovalCooldownEntry(UUID giverId, UUID targetId, long removedAt) { }
}
