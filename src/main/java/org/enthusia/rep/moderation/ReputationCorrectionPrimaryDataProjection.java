package org.enthusia.rep.moderation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepIdentityState;
import org.enthusia.rep.rep.RepService;
import org.enthusia.rep.storage.PluginDataSnapshot;

/**
 * Builds an isolated, read-only primary-data projection of a prepared correction.
 *
 * <p>NOT a committable transaction: projected data intentionally does not include
 * an executed-removal audit/history entry, case-revision authorization, durable
 * operation receipt, commit generation, or effects/outbox. No caller may save
 * this projection as `data.yml`, publish it to live indexes, or mark a Staff
 * remedy complete. Actual commits require provider-wide locking, a fresh CAS
 * and atomic state-plus-receipt storage.</p>
 */
@SuppressWarnings("PMD.UseConcurrentHashMap") // All maps are method-local candidate assembly.
public final class ReputationCorrectionPrimaryDataProjection {
    private ReputationCorrectionPrimaryDataProjection() {
    }

    public static Projection project(
            ReputationCorrectionIntentJournal.Prepared prepared,
            PluginDataSnapshot providerData
    ) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(providerData, "providerData");
        // PluginDataSnapshot owns shallow lists of mutable Commendation values.
        // Freeze copies first so no projected list aliases the caller's votes.
        PluginDataSnapshot before = copyOf(providerData);
        var preview = ReputationCorrectionProviderPreview.plan(prepared, before);

        UUID subject = prepared.subjectId();
        Set<UUID> selectedGivers = prepared.exactEntries().stream()
                .map(entry -> entry.giverId())
                .collect(Collectors.toUnmodifiableSet());
        List<Commendation> remaining = before.commendations().stream()
                .filter(entry -> !entry.getTarget().equals(subject)
                        || !selectedGivers.contains(entry.getGiver()))
                .map(Commendation::snapshot)
                .toList();

        Map<UUID, Integer> scores = new HashMap<>(before.scores());
        scores.put(subject, preview.projectedAfter().totalScore());

        Map<UUID, RepIdentityState> identities = new HashMap<>(before.identities());
        if (!preview.projectedTargetIdentity().equals(preview.previousTargetIdentity())) {
            identities.put(subject, preview.projectedTargetIdentity());
        }

        // Historical removals, reputation-change events, cooldown effects and
        // provider receipts are deliberately NOT manufactured by a preview.
        PluginDataSnapshot projected = new PluginDataSnapshot(
                scores, remaining,
                before.removedEntries().stream().map(RepService.RemovedRep::copy).toList(),
                before.stalkEntries(),
                before.reputationChanges(),
                before.suspiciousCases().stream().map(RepService.SuspiciousRepCase::copy).toList(),
                before.removalCooldowns(),
                before.repTradingAlertPreferences(),
                identities
        );
        return new Projection(preview, before, projected);
    }

    private static PluginDataSnapshot copyOf(PluginDataSnapshot data) {
        return new PluginDataSnapshot(
                data.scores(),
                data.commendations().stream().map(Commendation::snapshot).toList(),
                data.removedEntries().stream().map(RepService.RemovedRep::copy).toList(),
                data.stalkEntries(),
                data.reputationChanges(),
                data.suspiciousCases().stream().map(RepService.SuspiciousRepCase::copy).toList(),
                data.removalCooldowns(),
                data.repTradingAlertPreferences(),
                data.identities()
        );
    }

    /**
     * Pre-commit comparison material only. `projectedData` must never be saved
     * or treated as proof of a completed reputation correction.
     */
    public record Projection(
            ReputationCorrectionProviderPreview.Preview preview,
            PluginDataSnapshot beforeData,
            PluginDataSnapshot projectedData
    ) {
        public Projection {
            Objects.requireNonNull(preview, "preview");
            Objects.requireNonNull(beforeData, "beforeData");
            Objects.requireNonNull(projectedData, "projectedData");
        }
    }
}
