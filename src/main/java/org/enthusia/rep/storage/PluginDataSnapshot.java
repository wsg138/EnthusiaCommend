package org.enthusia.rep.storage;

import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.analytics.ReputationChangeRecord;
import org.enthusia.rep.rep.RepService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PluginDataSnapshot(
        Map<UUID, Integer> scores,
        List<Commendation> commendations,
        List<RepService.RemovedRep> removedEntries,
        List<StalkEntry> stalkEntries,
        List<ReputationChangeRecord> reputationChanges
) {
    public static final PluginDataSnapshot EMPTY = new PluginDataSnapshot(
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
    );

    public record StalkEntry(UUID stalkerId, UUID targetId, long expiresAt) {
    }
}
