package org.enthusia.rep.rep;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.analytics.ReputationAnalyticsService;
import org.enthusia.rep.analytics.ReputationChangeAction;
import org.enthusia.rep.analytics.ReputationChangeSource;
import org.enthusia.rep.events.RepMilestoneReachedEvent;
import org.enthusia.rep.events.RepScoreChangedEvent;
import org.enthusia.rep.storage.PluginDataSnapshot;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class RepService {
    private static final long ALT_WINDOW_MILLIS = 48L * 60L * 60L * 1000L;

    private final Runnable dirtyMarker;
    private final Consumer<UUID> scoreChangeListener;
    private final ReputationAnalyticsService analyticsService;
    private final Consumer<AuditRecord> auditConsumer;
    private final RepAlertPreferences alertPreferences;

    private volatile org.enthusia.rep.config.RepConfig repConfig;
    private volatile Predicate<UUID> grantPolicy = ignored -> true;

    private final Map<UUID, Integer> scoreByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, String> knownNames = new ConcurrentHashMap<>();
    private final Map<UUID, List<Commendation>> commendationsByTarget = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Commendation>> commendationsByGiver = new ConcurrentHashMap<>();
    private final Map<RepPair, Long> removalCooldowns = new ConcurrentHashMap<>();
    private final Map<String, List<AltRepRecord>> altRecordsByHash = new ConcurrentHashMap<>();
    private final List<SuspiciousRepCase> suspiciousCases = new ArrayList<>();
    private final List<RemovedRep> removedEntries = new ArrayList<>();

    public RepService(
            CommendPlugin plugin,
            org.enthusia.rep.config.RepConfig repConfig,
            PluginDataSnapshot dataSnapshot,
            Runnable dirtyMarker,
            Consumer<UUID> scoreChangeListener,
            ReputationAnalyticsService analyticsService
    ) {
        this(plugin, repConfig, dataSnapshot, dirtyMarker, scoreChangeListener, analyticsService, ignored -> { });
    }

    public RepService(
            CommendPlugin plugin,
            org.enthusia.rep.config.RepConfig repConfig,
            PluginDataSnapshot dataSnapshot,
            Runnable dirtyMarker,
            Consumer<UUID> scoreChangeListener,
            ReputationAnalyticsService analyticsService,
            Consumer<AuditRecord> auditConsumer
    ) {
        Objects.requireNonNull(plugin, "plugin");
        this.repConfig = repConfig;
        this.dirtyMarker = dirtyMarker;
        this.scoreChangeListener = scoreChangeListener;
        this.analyticsService = analyticsService;
        this.auditConsumer = auditConsumer == null ? ignored -> { } : auditConsumer;
        PluginDataSnapshot resolvedSnapshot = dataSnapshot == null ? PluginDataSnapshot.EMPTY : dataSnapshot;
        this.alertPreferences = new RepAlertPreferences(
                repConfig.areRepTradingAlertsEnabledByDefault(),
                resolvedSnapshot.repTradingAlertPreferences());
        loadSnapshot(resolvedSnapshot);
    }

    public void reload(org.enthusia.rep.config.RepConfig repConfig) {
        this.repConfig = repConfig;
        this.alertPreferences.reloadDefault(repConfig.areRepTradingAlertsEnabledByDefault());
    }

    public void setGrantPolicy(Predicate<UUID> grantPolicy) {
        this.grantPolicy = Objects.requireNonNull(grantPolicy, "grantPolicy");
    }

    private void loadSnapshot(PluginDataSnapshot snapshot) {
        scoreByPlayer.clear();
        scoreByPlayer.putAll(snapshot.scores());

        commendationsByTarget.clear();
        commendationsByGiver.clear();
        for (Commendation commendation : snapshot.commendations()) {
            cacheCommendation(cloneCommendation(commendation), false);
        }

        synchronized (removedEntries) {
            removedEntries.clear();
            removedEntries.addAll(snapshot.removedEntries().stream().map(RemovedRep::copy).toList());
        }
        synchronized (suspiciousCases) {
            suspiciousCases.clear();
            suspiciousCases.addAll(snapshot.suspiciousCases().stream().map(SuspiciousRepCase::copy).toList());
        }
        removalCooldowns.clear();
        long now = System.currentTimeMillis();
        long cooldownMillis = repConfig.getEditCooldownMillis();
        for (PluginDataSnapshot.RemovalCooldownEntry entry : snapshot.removalCooldowns()) {
            if (RepRules.isCooldownActive(entry.removedAt(), now, cooldownMillis)) {
                removalCooldowns.put(new RepPair(entry.giverId(), entry.targetId()), entry.removedAt());
            }
        }
        rebuildAntiAbuseIndex();
    }

    private void rebuildAntiAbuseIndex() {
        altRecordsByHash.clear();
        for (List<Commendation> entries : commendationsByTarget.values()) {
            synchronized (entries) {
                for (Commendation commendation : entries) {
                    if (commendation.getIpHash() == null || commendation.getIpHash().isBlank()) {
                        continue;
                    }
                    altRecordsByHash.computeIfAbsent(commendation.getIpHash(), ignored -> new ArrayList<>())
                            .add(new AltRepRecord(
                                    commendation.getGiver(),
                                    commendation.getTarget(),
                                    commendation.isPositive(),
                                    commendation.getLastEditedAt(),
                                    commendation.getIpHash()));
                }
            }
        }
    }

    public PluginDataSnapshot snapshot(PluginDataSnapshot base) {
        Map<UUID, Integer> scores = new LinkedHashMap<>(scoreByPlayer);
        List<Commendation> commendations = new ArrayList<>();
        for (List<Commendation> entries : commendationsByTarget.values()) {
            synchronized (entries) {
                for (Commendation commendation : entries) {
                    commendations.add(cloneCommendation(commendation));
                }
            }
        }
        commendations.sort(Comparator.comparingLong(Commendation::getCreatedAt));

        List<RemovedRep> removed;
        synchronized (removedEntries) {
            removed = removedEntries.stream().map(RemovedRep::copy).toList();
        }
        List<SuspiciousRepCase> cases;
        synchronized (suspiciousCases) {
            cases = suspiciousCases.stream().map(SuspiciousRepCase::copy).toList();
        }
        long now = System.currentTimeMillis();
        long cooldownMillis = repConfig.getEditCooldownMillis();
        List<PluginDataSnapshot.RemovalCooldownEntry> cooldowns = removalCooldowns.entrySet().stream()
                .filter(entry -> RepRules.isCooldownActive(entry.getValue(), now, cooldownMillis))
                .map(entry -> new PluginDataSnapshot.RemovalCooldownEntry(
                        entry.getKey().giverId(), entry.getKey().targetId(), entry.getValue()))
                .toList();
        return new PluginDataSnapshot(
                scores,
                commendations,
                removed,
                base.stalkEntries(),
                base.reputationChanges(),
                cases,
                cooldowns,
                alertPreferences.snapshot()
        );
    }

    public int getScore(UUID playerId) {
        return scoreByPlayer.getOrDefault(playerId, 0);
    }

    public Map<UUID, Integer> getScoresSnapshot() {
        return Map.copyOf(scoreByPlayer);
    }

    public Map<RepCategory, Integer> getCategoryScores(UUID playerId) {
        return Map.copyOf(RepRules.categoryScores(getCommendationsAbout(playerId)));
    }

    public Map<RepCategory, Integer> getAllCategoryScores(UUID playerId) {
        return getCategoryScores(playerId);
    }

    public int getCategoryScore(UUID playerId, RepCategory category) {
        if (category == null) {
            return 0;
        }
        return getCategoryScores(playerId).getOrDefault(category.migratedCategory(), 0);
    }

    public int getWorstNegativeCategoryScore(UUID playerId) {
        return getCategoryScores(playerId).entrySet().stream()
                .filter(entry -> !entry.getKey().isPositive())
                .mapToInt(Map.Entry::getValue)
                .min()
                .orElse(0);
    }

    public void setScore(UUID playerId, int score) {
        applyScore(playerId, score, true);
    }

    public void setScoreByStaff(UUID playerId, int score, CommandSender actor) {
        int oldScore = getScore(playerId);
        applyScore(playerId, score, true);
        recordStaffChange(playerId, actor, score - oldScore, ReputationChangeAction.SET,
                ReputationChangeSource.ADMIN_CORRECTION, null, "Admin set", oldScore, score);
    }

    public void adjustScore(UUID playerId, int delta) {
        if (delta != 0) {
            applyScore(playerId, getScore(playerId) + delta, true);
        }
    }

    public void adjustScoreByStaff(UUID playerId, int delta, CommandSender actor) {
        if (delta == 0) {
            return;
        }
        int oldScore = getScore(playerId);
        int newScore = oldScore + delta;
        applyScore(playerId, newScore, true);
        recordStaffChange(playerId, actor, delta, ReputationChangeAction.ADJUST,
                ReputationChangeSource.ADMIN_CORRECTION, null, "Admin adjustment", oldScore, newScore);
    }

    private void applyScore(UUID playerId, int newScore, boolean emitEvent) {
        int oldScore = getScore(playerId);
        scoreByPlayer.put(playerId, newScore);
        if (oldScore == newScore) {
            return;
        }
        dirtyMarker.run();
        if (emitEvent) {
            Bukkit.getPluginManager().callEvent(new RepScoreChangedEvent(playerId, oldScore, newScore));
            if (repConfig.crossedEffectThreshold(oldScore, newScore)) {
                Bukkit.getPluginManager().callEvent(new RepMilestoneReachedEvent(playerId, oldScore, newScore));
            }
        }
        scoreChangeListener.accept(playerId);
    }

    public Commendation getCommendation(UUID giverId, UUID targetId) {
        Map<UUID, Commendation> given = commendationsByGiver.get(giverId);
        return given == null ? null : given.get(targetId);
    }

    public List<Commendation> getCommendationsAbout(UUID targetId) {
        List<Commendation> commendations = commendationsByTarget.get(targetId);
        if (commendations == null) {
            return List.of();
        }
        synchronized (commendations) {
            return List.copyOf(commendations);
        }
    }

    public List<Commendation> getReceivedCommendations(UUID targetId) {
        return getCommendationsAbout(targetId).stream()
                .sorted(Comparator.comparingLong(Commendation::getCreatedAt).reversed())
                .toList();
    }

    public Commendation findCommendation(UUID giverId, UUID targetId, RepCategory category) {
        Commendation commendation = getCommendation(giverId, targetId);
        if (commendation == null || category == null) {
            return null;
        }
        return commendation.getCategory() == category.migratedCategory() ? commendation : null;
    }

    public List<Commendation> recentCommendations(int limit) {
        List<Commendation> commendations = new ArrayList<>();
        for (List<Commendation> entries : commendationsByTarget.values()) {
            synchronized (entries) {
                commendations.addAll(entries);
            }
        }
        return commendations.stream()
                .sorted(Comparator.comparingLong(Commendation::getLastEditedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    /** Immutable copies safe for asynchronous integrations such as Plan. */
    public List<Commendation> getCommendationSnapshotsAbout(UUID targetId) {
        List<Commendation> entries = commendationsByTarget.get(targetId);
        if (entries == null) {
            return List.of();
        }
        synchronized (entries) {
            return entries.stream().map(this::cloneCommendation).toList();
        }
    }

    /** Immutable copies safe for asynchronous integrations such as Plan. */
    public List<Commendation> recentCommendationSnapshots(int limit) {
        List<Commendation> snapshots = new ArrayList<>();
        for (List<Commendation> entries : commendationsByTarget.values()) {
            synchronized (entries) {
                entries.stream().map(this::cloneCommendation).forEach(snapshots::add);
            }
        }
        return snapshots.stream()
                .sorted(Comparator.comparingLong(Commendation::getLastEditedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    public List<Map.Entry<UUID, Integer>> top(int limit, boolean lowest) {
        return leaderboard(null, lowest).stream().limit(Math.max(1, limit)).toList();
    }

    public List<Map.Entry<UUID, Integer>> leaderboard(RepCategory category, boolean lowest) {
        Map<UUID, Integer> values = new LinkedHashMap<>();
        if (category == null) {
            Set<UUID> players = new LinkedHashSet<>(scoreByPlayer.keySet());
            players.addAll(commendationsByTarget.keySet());
            for (UUID playerId : players) {
                values.put(playerId, getScore(playerId));
            }
        } else {
            values.putAll(RepLeaderboardPopulation.categoryTotals(
                    recentCommendations(Integer.MAX_VALUE), category));
        }
        return RepLeaderboardSorter.sort(values, lowest);
    }

    public boolean areTradingAlertsEnabled(UUID playerId) {
        return alertPreferences.isEnabled(playerId);
    }

    public boolean toggleTradingAlerts(UUID playerId) {
        boolean enabled = alertPreferences.toggle(playerId);
        dirtyMarker.run();
        return enabled;
    }

    public String nameOf(UUID playerId) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        rememberName(playerId, player.getName());
        return cachedNameOf(playerId);
    }

    public void rememberName(UUID playerId, String playerName) {
        if (playerId != null && playerName != null && !playerName.isBlank()) {
            knownNames.put(playerId, playerName);
        }
    }

    /** Does not access Bukkit and is safe for asynchronous integrations. */
    public String cachedNameOf(UUID playerId) {
        if (playerId == null) {
            return "unknown";
        }
        return knownNames.getOrDefault(playerId, playerId.toString().substring(0, 8));
    }

    public CommendationResult addOrUpdateCommendation(
            UUID giverId,
            UUID targetId,
            boolean positive,
            RepCategory category,
            String reasonText,
            String ipHash
    ) {
        Objects.requireNonNull(giverId, "giverId");
        if (!grantPolicy.test(giverId)) {
            return CommendationResult.blacklisted();
        }
        RepCategory normalizedCategory = RepRules.acceptedCategory(category, positive);
        if (normalizedCategory == null) {
            return CommendationResult.invalid();
        }

        long now = System.currentTimeMillis();
        Commendation existing = getCommendation(giverId, targetId);
        if (existing == null) {
            long remainingCooldown = getRemovalCooldownMillis(giverId, targetId);
            if (remainingCooldown > 0) {
                return CommendationResult.cooldown(remainingCooldown);
            }

            int value = normalizedCategory.defaultScoreValue();
            Commendation created = new Commendation(
                    giverId, targetId, positive, normalizedCategory, reasonText,
                    now, now, ipHash, value);
            cacheCommendation(created, true);
            int oldScore = getScore(targetId);
            applyScore(targetId, oldScore + value, true);
            recordPlayerChange(targetId, giverId, value, ReputationChangeAction.ADD,
                    normalizedCategory, reasonText, oldScore, oldScore + value);
            removalCooldowns.remove(key(giverId, targetId));
            runAntiAbuseChecks(created, now);
            dirtyMarker.run();

            Bukkit.getPluginManager().callEvent(new org.enthusia.rep.events.CommendationGivenEvent(giverId, targetId, positive));
            Bukkit.getPluginManager().callEvent(new org.enthusia.rep.events.CommendationReceivedEvent(targetId, giverId, positive, getScore(targetId)));
            auditConsumer.accept(new AuditRecord(AuditAction.CREATED, cloneCommendation(created), value, getScore(targetId), now));
            return CommendationResult.created(created);
        }

        long sinceLastEdit = now - existing.getLastEditedAt();
        if (sinceLastEdit < repConfig.getEditCooldownMillis()) {
            return CommendationResult.cooldown(repConfig.getEditCooldownMillis() - sinceLastEdit);
        }

        int delta = existing.applyUpdate(positive, normalizedCategory, reasonText, now, ipHash);
        int oldScore = getScore(targetId);

        if (delta != 0) {
            applyScore(targetId, oldScore + delta, true);
        }
        recordPlayerChange(targetId, giverId, delta, ReputationChangeAction.UPDATE,
                normalizedCategory, reasonText, oldScore, oldScore + delta);
        removalCooldowns.remove(key(giverId, targetId));
        rebuildAntiAbuseIndex();
        runAntiAbuseChecks(existing, now);
        dirtyMarker.run();

        Bukkit.getPluginManager().callEvent(new org.enthusia.rep.events.CommendationEditedEvent(giverId, targetId, positive));
        auditConsumer.accept(new AuditRecord(AuditAction.UPDATED, cloneCommendation(existing), delta, getScore(targetId), now));
        return CommendationResult.updated(existing, delta);
    }

    public void removeCommendation(UUID giverId, UUID targetId) {
        removeCommendationInternal(giverId, targetId, false, false, null);
    }

    public void removeCommendationWithCooldown(UUID giverId, UUID targetId) {
        removeCommendationInternal(giverId, targetId, true, false, null);
    }

    public RemovedRep removeCommendationLogged(UUID removerId, UUID giverId, UUID targetId, boolean applyCooldown) {
        return removeCommendationInternal(giverId, targetId, applyCooldown, true, removerId);
    }

    private RemovedRep removeCommendationInternal(UUID giverId, UUID targetId, boolean applyCooldown,
                                                   boolean logRemoval, UUID removerId) {
        Commendation existing = getCommendation(giverId, targetId);
        if (existing == null) {
            return null;
        }

        Map<UUID, Commendation> byGiver = commendationsByGiver.get(giverId);
        if (byGiver != null) {
            byGiver.remove(targetId);
            if (byGiver.isEmpty()) {
                commendationsByGiver.remove(giverId);
            }
        }

        List<Commendation> byTarget = commendationsByTarget.get(targetId);
        if (byTarget != null) {
            synchronized (byTarget) {
                byTarget.removeIf(commendation -> commendation.getGiver().equals(giverId));
                if (byTarget.isEmpty()) {
                    commendationsByTarget.remove(targetId);
                }
            }
        }

        int oldScore = getScore(targetId);
        int delta = -existing.getScoreValue();
        applyScore(targetId, oldScore + delta, true);
        if (applyCooldown) {
            removalCooldowns.put(key(giverId, targetId), System.currentTimeMillis());
        } else {
            removalCooldowns.remove(key(giverId, targetId));
        }

        RemovedRep removedRep = null;
        if (logRemoval) {
            removedRep = new RemovedRep(nextRemovalId(), cloneCommendation(existing), System.currentTimeMillis(), removerId);
            synchronized (removedEntries) {
                removedEntries.add(removedRep);
            }
        }

        ReputationChangeSource source = logRemoval ? ReputationChangeSource.STAFF_GUI : ReputationChangeSource.PLAYER_ACTION;
        UUID actorId = logRemoval ? removerId : giverId;
        recordChange(targetId, actorId, delta, ReputationChangeAction.REMOVE, source,
                existing.getCategory(), existing.getReasonText(), oldScore, oldScore + delta);
        rebuildAntiAbuseIndex();
        dirtyMarker.run();
        String actorName = actorId == null ? (logRemoval ? "Console" : "System") : nameOf(actorId);
        auditConsumer.accept(new AuditRecord(AuditAction.REMOVED, cloneCommendation(existing), delta,
                getScore(targetId), System.currentTimeMillis(), actorId, actorName));
        return removedRep;
    }

    public boolean canEdit(UUID giverId, UUID targetId) {
        Commendation existing = getCommendation(giverId, targetId);
        if (existing != null) {
            return System.currentTimeMillis() - existing.getLastEditedAt() >= repConfig.getEditCooldownMillis();
        }
        return getRemovalCooldownMillis(giverId, targetId) <= 0L;
    }

    public long getRemovalCooldownMillis(UUID giverId, UUID targetId) {
        Long removedAt = removalCooldowns.get(key(giverId, targetId));
        if (removedAt == null) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        long cooldownMillis = repConfig.getEditCooldownMillis();
        if (!RepRules.isCooldownActive(removedAt, now, cooldownMillis)) {
            removalCooldowns.remove(key(giverId, targetId));
            return 0L;
        }
        return cooldownMillis - (now - removedAt);
    }

    public void resetAll(UUID targetId) {
        List<Commendation> current = new ArrayList<>(getCommendationsAbout(targetId));
        for (Commendation commendation : current) {
            removeCommendationLogged(null, commendation.getGiver(), targetId, false);
        }
    }

    public void resetAllByStaff(UUID targetId, CommandSender actor) {
        UUID removerId = actor instanceof Player player ? player.getUniqueId() : null;
        List<Commendation> current = new ArrayList<>(getCommendationsAbout(targetId));
        for (Commendation commendation : current) {
            removeCommendationLogged(removerId, commendation.getGiver(), targetId, false);
        }
        int residualScore = getScore(targetId);
        if (residualScore != 0) {
            applyScore(targetId, 0, true);
            recordStaffChange(targetId, actor, -residualScore, ReputationChangeAction.RESET,
                    ReputationChangeSource.ADMIN_CORRECTION, null, "Admin reset residual", residualScore, 0);
        }
    }

    public String hashIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(ipAddress.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 8 && i < bytes.length; i++) {
                builder.append(String.format("%02x", bytes[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ignored) {
            return Integer.toHexString(ipAddress.hashCode());
        }
    }

    public List<SuspiciousRepCase> getSuspiciousCases() {
        synchronized (suspiciousCases) {
            return suspiciousCases.stream().map(SuspiciousRepCase::copy).toList();
        }
    }

    public List<SuspiciousRepCase> getCasesForTarget(UUID targetId, boolean includeResolved) {
        synchronized (suspiciousCases) {
            return suspiciousCases.stream()
                    .filter(entry -> entry.targetId.equals(targetId))
                    .filter(entry -> includeResolved || !entry.resolved)
                    .map(SuspiciousRepCase::copy)
                    .toList();
        }
    }

    public boolean resolveCase(UUID targetId, String key) {
        boolean changed = false;
        synchronized (suspiciousCases) {
            for (SuspiciousRepCase entry : suspiciousCases) {
                if (entry.targetId.equals(targetId) && entry.caseKey.equalsIgnoreCase(key) && !entry.resolved) {
                    entry.resolved = true;
                    changed = true;
                }
            }
        }
        if (changed) {
            dirtyMarker.run();
        }
        return changed;
    }

    public List<RemovedRep> getRemovedLog() {
        synchronized (removedEntries) {
            return removedEntries.stream().map(RemovedRep::copy).toList();
        }
    }

    public boolean restoreRemoved(String id) {
        return restoreRemoved(id, null);
    }

    public boolean restoreRemoved(String id, CommandSender actor) {
        if (id == null || id.isBlank()) {
            return false;
        }
        RemovedRep removed;
        synchronized (removedEntries) {
            removed = removedEntries.stream()
                    .filter(entry -> id.equalsIgnoreCase(entry.removalId))
                    .findFirst()
                    .orElse(null);
        }
        if (removed == null) {
            return false;
        }

        Commendation commendation = removed.removedCommendation;
        if (getCommendation(commendation.getGiver(), commendation.getTarget()) != null) {
            return false;
        }

        Commendation restored = cloneCommendation(commendation);
        cacheCommendation(restored, true);
        int oldScore = getScore(restored.getTarget());
        int delta = restored.getScoreValue();
        applyScore(restored.getTarget(), oldScore + delta, true);
        removalCooldowns.remove(key(restored.getGiver(), restored.getTarget()));
        synchronized (removedEntries) {
            removedEntries.remove(removed);
        }
        if (actor != null) {
            recordStaffChange(restored.getTarget(), actor, delta, ReputationChangeAction.RESTORE,
                    ReputationChangeSource.STAFF_COMMAND, restored.getCategory(), restored.getReasonText(), oldScore, oldScore + delta);
        } else {
            recordChange(restored.getTarget(), restored.getGiver(), delta, ReputationChangeAction.RESTORE,
                    ReputationChangeSource.SYSTEM, restored.getCategory(), restored.getReasonText(), oldScore, oldScore + delta);
        }
        rebuildAntiAbuseIndex();
        dirtyMarker.run();
        UUID auditActorId = actor instanceof Player player ? player.getUniqueId() : null;
        String auditActorName = actor == null ? "System" : actor.getName();
        auditConsumer.accept(new AuditRecord(AuditAction.RESTORED, cloneCommendation(restored), delta,
                getScore(restored.getTarget()), System.currentTimeMillis(), auditActorId, auditActorName));
        return true;
    }

    private void runAntiAbuseChecks(Commendation changed, long now) {
        logAltRecord(changed.getIpHash(), changed.getGiver(), changed.getTarget(), changed.isPositive(), now);
        checkReciprocity(changed, now);
        if (!changed.isPositive()) {
            checkNegativeCluster(changed.getTarget(), now);
        }
    }

    private void logAltRecord(String ipHash, UUID giverId, UUID targetId, boolean positive, long timestamp) {
        if (ipHash == null || ipHash.isBlank()) {
            return;
        }
        List<AltRepRecord> records = altRecordsByHash.computeIfAbsent(ipHash, ignored -> new ArrayList<>());
        synchronized (records) {
            records.removeIf(record -> record.giverId.equals(giverId) && record.targetId.equals(targetId));
            records.add(new AltRepRecord(giverId, targetId, positive, timestamp, ipHash));
        }
        if (positive) {
            return;
        }

        Set<UUID> givers = new LinkedHashSet<>();
        synchronized (records) {
            for (AltRepRecord record : records) {
                if (record.targetId.equals(targetId)
                        && !record.positive
                        && timestamp >= record.timestamp
                        && timestamp - record.timestamp <= ALT_WINDOW_MILLIS) {
                    givers.add(record.giverId);
                }
            }
        }
        if (givers.size() >= 2) {
            createCaseIfAbsent(targetId, "ALT_IP", ipHash, new ArrayList<>(givers), timestamp,
                    "Multiple accounts on the same IP hash down-repped this player within 48 hours.");
        }
    }

    private void checkReciprocity(Commendation changed, long now) {
        Commendation reverse = getCommendation(changed.getTarget(), changed.getGiver());
        if (!RepRules.isRecentReciprocal(reverse, now)) {
            return;
        }
        List<UUID> participants = List.of(changed.getGiver(), changed.getTarget());
        String first = changed.getGiver().compareTo(changed.getTarget()) <= 0
                ? changed.getGiver().toString() : changed.getTarget().toString();
        String second = changed.getGiver().compareTo(changed.getTarget()) <= 0
                ? changed.getTarget().toString() : changed.getGiver().toString();
        createCaseIfAbsent(changed.getTarget(), "RECIPROCITY", first + "-" + second,
                participants, now, "The two players exchanged reputation within 24 hours.");
    }

    private void checkNegativeCluster(UUID targetId, long now) {
        Set<UUID> givers = RepRules.recentNegativeGivers(getCommendationsAbout(targetId), now);
        if (givers.size() >= RepRules.CLUSTER_MIN_GIVERS) {
            createCaseIfAbsent(targetId, "CLUSTER_DOWNREP", targetId.toString(), new ArrayList<>(givers), now,
                    givers.size() + " distinct players down-repped this target within 6 hours.");
        }
    }

    private void createCaseIfAbsent(UUID targetId, String type, String caseKey, List<UUID> givers,
                                    long timestamp, String detail) {
        SuspiciousRepCase created = null;
        synchronized (suspiciousCases) {
            boolean duplicate = suspiciousCases.stream().anyMatch(entry ->
                    !entry.resolved
                            && entry.targetId.equals(targetId)
                            && entry.type.equals(type)
                            && entry.caseKey.equalsIgnoreCase(caseKey));
            if (!duplicate) {
                created = new SuspiciousRepCase(targetId, type, caseKey, givers, timestamp, false, detail);
                suspiciousCases.add(created);
            }
        }
        if (created != null) {
            dirtyMarker.run();
            notifyStaff(created);
        }
    }

    private void notifyStaff(SuspiciousRepCase caseData) {
        String targetArgument = resolveTargetArgument(caseData.targetId);
        String inspectCommand = "/rep admin inspect " + targetArgument + " " + caseData.caseKey;
        Component message = Component.text("REP ALERT: ", NamedTextColor.RED)
                .append(Component.text(caseData.type, NamedTextColor.YELLOW))
                .append(Component.text(" involving ", NamedTextColor.RED))
                .append(Component.text(nameOf(caseData.targetId), NamedTextColor.YELLOW))
                .append(Component.text(" [inspect]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand(inspectCommand))
                        .hoverEvent(HoverEvent.showText(Component.text(caseData.detail, NamedTextColor.GRAY))));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("enthusiacommend.rep.alert")
                    && alertPreferences.isEnabled(player.getUniqueId())) {
                player.sendMessage(message);
            }
        }
    }

    private void recordPlayerChange(UUID targetId, UUID actorId, int delta, ReputationChangeAction action,
                                    RepCategory category, String reason, int oldScore, int newScore) {
        if (analyticsService != null) {
            analyticsService.recordPlayerChange(targetId, actorId, delta, action, category, reason, oldScore, newScore);
        }
    }

    private void recordStaffChange(UUID targetId, CommandSender actor, int delta, ReputationChangeAction action,
                                   ReputationChangeSource source, RepCategory category, String reason,
                                   int oldScore, int newScore) {
        if (analyticsService != null) {
            analyticsService.recordStaffChange(targetId, actor, delta, action, source, category, reason, oldScore, newScore);
        }
    }

    private void recordChange(UUID targetId, UUID actorId, int delta, ReputationChangeAction action,
                              ReputationChangeSource source, RepCategory category, String reason,
                              int oldScore, int newScore) {
        if (analyticsService == null) {
            return;
        }
        if (source == ReputationChangeSource.PLAYER_ACTION) {
            analyticsService.recordPlayerChange(targetId, actorId, delta, action, category, reason, oldScore, newScore);
        } else {
            analyticsService.recordChange(targetId, actorId, null, delta, action, source, category, reason, oldScore, newScore);
        }
    }

    private void cacheCommendation(Commendation commendation, boolean replaceExisting) {
        List<Commendation> targetEntries = commendationsByTarget.computeIfAbsent(
                commendation.getTarget(), ignored -> java.util.Collections.synchronizedList(new ArrayList<>()));
        Map<UUID, Commendation> giverEntries = commendationsByGiver.computeIfAbsent(
                commendation.getGiver(), ignored -> new ConcurrentHashMap<>());
        if (replaceExisting) {
            Commendation previous = giverEntries.get(commendation.getTarget());
            if (previous != null) {
                synchronized (targetEntries) {
                    targetEntries.removeIf(entry -> entry.getGiver().equals(commendation.getGiver()));
                }
            }
        }
        synchronized (targetEntries) {
            targetEntries.add(commendation);
        }
        giverEntries.put(commendation.getTarget(), commendation);
    }

    private String resolveTargetArgument(UUID targetId) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(targetId);
        return player.getName() != null ? player.getName() : targetId.toString();
    }

    private RepPair key(UUID giverId, UUID targetId) {
        return new RepPair(giverId, targetId);
    }

    private Commendation cloneCommendation(Commendation commendation) {
        return commendation.snapshot();
    }

    private String nextRemovalId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record RepPair(UUID giverId, UUID targetId) {
    }

    private record AltRepRecord(UUID giverId, UUID targetId, boolean positive, long timestamp, String ipHash) {
    }

    public record AuditRecord(AuditAction action, Commendation commendation, int scoreDelta,
                              int newTotal, long timestamp, UUID actorId, String actorName) {
        public AuditRecord(AuditAction action, Commendation commendation, int scoreDelta,
                           int newTotal, long timestamp) {
            this(action, commendation, scoreDelta, newTotal, timestamp, null, null);
        }
    }

    public enum AuditAction {
        CREATED,
        UPDATED,
        REMOVED,
        RESTORED
    }

    public static final class SuspiciousRepCase {
        private final UUID targetId;
        private final String type;
        private final String caseKey;
        private final List<UUID> giverIds;
        private final long createdAt;
        private boolean resolved;
        private final String detail;

        public SuspiciousRepCase(UUID targetId, String type, String caseKey, List<UUID> giverIds,
                                 long createdAt, boolean resolved, String detail) {
            this.targetId = targetId;
            this.type = type == null ? "UNKNOWN" : type;
            this.caseKey = caseKey == null ? this.type : caseKey;
            this.giverIds = List.copyOf(giverIds == null ? List.of() : giverIds);
            this.createdAt = createdAt;
            this.resolved = resolved;
            this.detail = detail == null ? "" : detail;
        }

        /** Compatibility constructor for older in-memory callers. */
        public SuspiciousRepCase(UUID targetId, String key, List<UUID> giverIds, long createdAt, boolean resolved) {
            this(targetId, "ALT_IP", key, giverIds, createdAt, resolved, "");
        }

        public UUID getTarget() { return targetId; }
        public String ipHash() { return caseKey; }
        public String type() { return type; }
        public String key() { return caseKey; }
        public List<UUID> givers() { return giverIds; }
        public long getCreatedAt() { return createdAt; }
        public boolean isResolved() { return resolved; }
        public String detail() { return detail; }

        public Map<String, Object> serialize() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("target", targetId.toString());
            map.put("type", type);
            map.put("key", caseKey);
            map.put("givers", giverIds.stream().map(UUID::toString).toList());
            map.put("createdAt", createdAt);
            map.put("resolved", resolved);
            map.put("detail", detail);
            return map;
        }

        public static SuspiciousRepCase fromMap(Map<?, ?> raw) {
            try {
                UUID target = UUID.fromString(String.valueOf(raw.get("target")));
                String type = Objects.toString(raw.get("type"), "ALT_IP");
                String key = Objects.toString(raw.get("key"), Objects.toString(raw.get("ipHash"), type));
                List<UUID> givers = new ArrayList<>();
                Object rawGivers = raw.get("givers");
                if (rawGivers instanceof Collection<?> values) {
                    for (Object value : values) {
                        try {
                            givers.add(UUID.fromString(String.valueOf(value)));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                long createdAt = raw.get("createdAt") instanceof Number value ? value.longValue() : System.currentTimeMillis();
                boolean resolved = raw.get("resolved") instanceof Boolean value
                        ? value : Boolean.parseBoolean(String.valueOf(raw.get("resolved")));
                String detail = Objects.toString(raw.get("detail"), "");
                return new SuspiciousRepCase(target, type, key, givers, createdAt, resolved, detail);
            } catch (Exception ignored) {
                return null;
            }
        }

        public SuspiciousRepCase copy() {
            return new SuspiciousRepCase(targetId, type, caseKey, giverIds, createdAt, resolved, detail);
        }
    }

    public record CommendationResult(boolean success, boolean created, Commendation commendation,
                                      long cooldownRemainingMillis, int repDelta, Failure failure) {
        public enum Failure {
            NONE,
            COOLDOWN,
            INVALID_CATEGORY,
            REPUTATION_BLACKLISTED
        }

        public static CommendationResult created(Commendation commendation) {
            return new CommendationResult(true, true, commendation, 0L,
                    commendation.getScoreValue(), Failure.NONE);
        }

        public static CommendationResult updated(Commendation commendation, int delta) {
            return new CommendationResult(true, false, commendation, 0L, delta, Failure.NONE);
        }

        public static CommendationResult cooldown(long remainingMillis) {
            return new CommendationResult(false, false, null, remainingMillis, 0, Failure.COOLDOWN);
        }

        public static CommendationResult invalid() {
            return new CommendationResult(false, false, null, 0L, 0, Failure.INVALID_CATEGORY);
        }

        public static CommendationResult blacklisted() {
            return new CommendationResult(false, false, null, 0L, 0, Failure.REPUTATION_BLACKLISTED);
        }
    }

    public static final class RemovedRep {
        private final String removalId;
        private final Commendation removedCommendation;
        private final long removedAtMillis;
        private final UUID removerId;

        public RemovedRep(String id, Commendation commendation, long removedAt, UUID removedBy) {
            this.removalId = id;
            this.removedCommendation = commendation;
            this.removedAtMillis = removedAt;
            this.removerId = removedBy;
        }

        public String id() { return removalId; }
        public Commendation commendation() { return removedCommendation; }
        public long removedAt() { return removedAtMillis; }
        public UUID removedBy() { return removerId; }

        public Map<String, Object> serialize() {
            Map<String, Object> map = new LinkedHashMap<>(removedCommendation.serialize());
            map.put("id", removalId);
            map.put("removedAt", removedAtMillis);
            if (removerId != null) {
                map.put("removedBy", removerId.toString());
            }
            return map;
        }

        public static RemovedRep fromMap(Map<?, ?> raw) {
            try {
                String id = String.valueOf(raw.get("id"));
                UUID giver = UUID.fromString(String.valueOf(raw.get("giver")));
                UUID target = UUID.fromString(String.valueOf(raw.get("target")));
                boolean positive = raw.get("positive") instanceof Boolean flag
                        ? flag : Boolean.parseBoolean(String.valueOf(raw.get("positive")));
                RepCategory category = RepCategory.fromStored(Objects.toString(raw.get("category"), null), positive);
                String reason = Objects.toString(raw.get("reason"), "");
                long createdAt = raw.get("createdAt") instanceof Number value ? value.longValue() : Instant.now().toEpochMilli();
                long lastEditedAt = raw.get("lastEditedAt") instanceof Number value ? value.longValue() : createdAt;
                String ipHash = raw.get("ipHash") != null ? raw.get("ipHash").toString() : null;
                int scoreValue = raw.get("scoreValue") instanceof Number value
                        ? value.intValue() : positive ? 1 : -1;
                long removedAt = raw.get("removedAt") instanceof Number value ? value.longValue() : Instant.now().toEpochMilli();
                UUID removedBy = raw.get("removedBy") == null ? null : UUID.fromString(String.valueOf(raw.get("removedBy")));
                Commendation commendation = new Commendation(
                        giver, target, positive, category, reason, createdAt, lastEditedAt, ipHash, scoreValue);
                return new RemovedRep(id, commendation, removedAt, removedBy);
            } catch (Exception ignored) {
                return null;
            }
        }

        public RemovedRep copy() {
            Commendation copied = new Commendation(
                    removedCommendation.getGiver(),
                    removedCommendation.getTarget(),
                    removedCommendation.isPositive(),
                    removedCommendation.getCategory(),
                    removedCommendation.getReasonText(),
                    removedCommendation.getCreatedAt(),
                    removedCommendation.getLastEditedAt(),
                    removedCommendation.getIpHash(),
                    removedCommendation.getScoreValue());
            return new RemovedRep(removalId, copied, removedAtMillis, removerId);
        }
    }
}
