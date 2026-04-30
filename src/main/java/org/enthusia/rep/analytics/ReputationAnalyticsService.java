package org.enthusia.rep.analytics;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.enthusia.rep.config.RepConfig;
import org.enthusia.rep.rep.RepCategory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class ReputationAnalyticsService {

    private final List<ReputationChangeRecord> changes = new ArrayList<>();
    private final Supplier<RepConfig> configSupplier;
    private final Runnable dirtyMarker;
    private final AtomicLong sequence = new AtomicLong();

    public ReputationAnalyticsService(RepConfig repConfig, List<ReputationChangeRecord> storedChanges, Runnable dirtyMarker) {
        this(() -> repConfig, storedChanges, dirtyMarker);
    }

    public ReputationAnalyticsService(Supplier<RepConfig> configSupplier, List<ReputationChangeRecord> storedChanges, Runnable dirtyMarker) {
        this.configSupplier = configSupplier;
        this.dirtyMarker = dirtyMarker;
        load(storedChanges);
    }

    private synchronized void load(List<ReputationChangeRecord> storedChanges) {
        changes.clear();
        if (storedChanges != null) {
            storedChanges.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingLong(ReputationChangeRecord::timestamp))
                    .forEach(changes::add);
        }
        pruneExpired(false);
    }

    public synchronized List<ReputationChangeRecord> snapshot() {
        pruneExpired(false);
        return List.copyOf(changes);
    }

    public void recordPlayerChange(
            UUID targetId,
            UUID actorId,
            int amount,
            ReputationChangeAction action,
            RepCategory category,
            String reason,
            int oldTotal,
            int newTotal
    ) {
        record(targetId, actorId, null, amount, action, ReputationChangeSource.PLAYER_ACTION, ReputationChangeOutcome.SUCCEEDED, category, reason, oldTotal, newTotal);
    }

    public void recordStaffChange(
            UUID targetId,
            CommandSender actor,
            int amount,
            ReputationChangeAction action,
            ReputationChangeSource source,
            RepCategory category,
            String reason,
            int oldTotal,
            int newTotal
    ) {
        UUID actorId = actor instanceof Player player ? player.getUniqueId() : null;
        String actorName = actor != null ? actor.getName() : "Console";
        record(targetId, actorId, actorName, amount, action, source, ReputationChangeOutcome.SUCCEEDED, category, reason, oldTotal, newTotal);
    }

    public void recordSystemChange(
            UUID targetId,
            int amount,
            ReputationChangeAction action,
            RepCategory category,
            String reason,
            int oldTotal,
            int newTotal
    ) {
        record(targetId, null, "System", amount, action, ReputationChangeSource.SYSTEM, ReputationChangeOutcome.SUCCEEDED, category, reason, oldTotal, newTotal);
    }

    public void recordChange(
            UUID targetId,
            UUID actorId,
            String actorName,
            int amount,
            ReputationChangeAction action,
            ReputationChangeSource source,
            RepCategory category,
            String reason,
            int oldTotal,
            int newTotal
    ) {
        record(targetId, actorId, actorName, amount, action, source, ReputationChangeOutcome.SUCCEEDED, category, reason, oldTotal, newTotal);
    }

    private synchronized void record(
            UUID targetId,
            UUID actorId,
            String actorName,
            int amount,
            ReputationChangeAction action,
            ReputationChangeSource source,
            ReputationChangeOutcome outcome,
            RepCategory category,
            String reason,
            int oldTotal,
            int newTotal
    ) {
        if (targetId == null || amount == 0 || oldTotal == newTotal) {
            return;
        }
        ReputationChangeRecord record = new ReputationChangeRecord(
                nextId(),
                System.currentTimeMillis(),
                targetId,
                actorId,
                actorName,
                amount,
                action,
                source,
                outcome,
                normalizeReason(reason, category),
                category,
                oldTotal,
                newTotal
        );
        changes.add(record);
        pruneExpired(false);
        dirtyMarker.run();
    }

    public synchronized List<ReputationChangeRecord> recentChanges(int limit) {
        return changes.stream()
                .sorted(Comparator.comparingLong(ReputationChangeRecord::timestamp).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    public synchronized List<ReputationChangeRecord> playerHistory(UUID playerId, int limit) {
        return changes.stream()
                .filter(change -> change.targetId().equals(playerId))
                .sorted(Comparator.comparingLong(ReputationChangeRecord::timestamp).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    public synchronized WindowTotals windowTotals(long sinceMillis) {
        int added = 0;
        int removed = 0;
        int total = 0;
        Map<UUID, Boolean> players = new HashMap<>();
        for (ReputationChangeRecord change : changes) {
            if (change.timestamp() < sinceMillis) {
                continue;
            }
            total++;
            players.put(change.targetId(), true);
            if (change.amount() > 0) {
                added += change.amount();
            } else {
                removed += Math.abs(change.amount());
            }
        }
        return new WindowTotals(total, added, removed, added - removed, players.size());
    }

    public synchronized PlayerStats playerStats(UUID playerId, int currentScore, long sinceMillis) {
        int gained = 0;
        int lost = 0;
        int changesInWindow = 0;
        ReputationChangeRecord last = null;
        for (ReputationChangeRecord change : changes) {
            if (!change.targetId().equals(playerId)) {
                continue;
            }
            if (last == null || change.timestamp() > last.timestamp()) {
                last = change;
            }
            if (change.amount() > 0) {
                gained += change.amount();
            } else {
                lost += Math.abs(change.amount());
            }
            if (change.timestamp() >= sinceMillis) {
                changesInWindow += change.amount();
            }
        }
        return new PlayerStats(currentScore, gained, lost, gained - lost, changesInWindow, last);
    }

    public synchronized List<PlayerActivity> recentPlayerActivity(Map<UUID, Integer> currentScores, long sinceMillis, int limit) {
        Map<UUID, MutablePlayerActivity> activity = new LinkedHashMap<>();
        for (ReputationChangeRecord change : changes) {
            if (change.timestamp() < sinceMillis) {
                continue;
            }
            MutablePlayerActivity entry = activity.computeIfAbsent(change.targetId(), MutablePlayerActivity::new);
            if (change.amount() > 0) {
                entry.gained += change.amount();
            } else {
                entry.lost += Math.abs(change.amount());
            }
            entry.net += change.amount();
            entry.changeCount++;
            entry.lastChange = Math.max(entry.lastChange, change.timestamp());
        }
        return activity.values().stream()
                .map(entry -> new PlayerActivity(
                        entry.playerId,
                        currentScores.getOrDefault(entry.playerId, 0),
                        entry.gained,
                        entry.lost,
                        entry.net,
                        entry.changeCount,
                        entry.lastChange
                ))
                .sorted(Comparator.comparingLong(PlayerActivity::lastChange).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    public synchronized List<ReasonCount> topReasons(long sinceMillis, int limit) {
        Map<String, ReasonCount.Mutable> counts = new HashMap<>();
        for (ReputationChangeRecord change : changes) {
            if (change.timestamp() < sinceMillis) {
                continue;
            }
            String reason = normalizeReason(change.reason(), change.category());
            ReasonCount.Mutable mutable = counts.computeIfAbsent(reason, ReasonCount.Mutable::new);
            mutable.count++;
            mutable.net += change.amount();
        }
        return counts.values().stream()
                .map(value -> new ReasonCount(value.reason, value.count, value.net))
                .sorted(Comparator.comparingInt(ReasonCount::count).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    public synchronized List<ReputationChangeRecord> staffActions(int limit) {
        return changes.stream()
                .filter(change -> change.source() == ReputationChangeSource.STAFF_COMMAND
                        || change.source() == ReputationChangeSource.STAFF_GUI
                        || change.source() == ReputationChangeSource.ADMIN_CORRECTION)
                .sorted(Comparator.comparingLong(ReputationChangeRecord::timestamp).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    public synchronized int totalChanges() {
        return changes.size();
    }

    public synchronized int pruneExpired(boolean markDirty) {
        RepConfig config = configSupplier.get();
        int maxRecords = config != null ? config.getAnalyticsMaxRecords() : 5000;
        long retentionMillis = config != null ? config.getAnalyticsRetentionMillis() : 90L * 24L * 60L * 60L * 1000L;
        long cutoff = System.currentTimeMillis() - retentionMillis;
        int before = changes.size();
        changes.removeIf(change -> change.timestamp() < cutoff);
        if (changes.size() > maxRecords) {
            changes.sort(Comparator.comparingLong(ReputationChangeRecord::timestamp).reversed());
            changes.subList(maxRecords, changes.size()).clear();
            changes.sort(Comparator.comparingLong(ReputationChangeRecord::timestamp));
        }
        int removed = before - changes.size();
        if (removed > 0 && markDirty) {
            dirtyMarker.run();
        }
        return removed;
    }

    public String nameOf(UUID playerId) {
        if (playerId == null) {
            return "Console";
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        return player.getName() != null ? player.getName() : playerId.toString().substring(0, 8);
    }

    public String actorName(ReputationChangeRecord change) {
        if (change.actorName() != null && !change.actorName().isBlank()) {
            return change.actorName();
        }
        if (change.actorId() != null) {
            return nameOf(change.actorId());
        }
        return switch (change.source()) {
            case SYSTEM -> "System";
            case STAFF_COMMAND, STAFF_GUI, ADMIN_CORRECTION -> "Console";
            case PLAYER_ACTION -> "Unknown";
        };
    }

    public static long sinceHours(int hours) {
        return System.currentTimeMillis() - hours * 60L * 60L * 1000L;
    }

    public static long sinceDays(int days) {
        return System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
    }

    private String normalizeReason(String reason, RepCategory category) {
        String normalized = reason == null ? "" : reason.trim().replaceAll("\\s+", " ");
        if (!normalized.isBlank()) {
            return normalized;
        }
        if (category != null) {
            return category.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        return "unspecified";
    }

    private String nextId() {
        return Long.toString(Instant.now().toEpochMilli(), 36) + "-" + Long.toString(sequence.incrementAndGet(), 36);
    }

    private static final class MutablePlayerActivity {
        private final UUID playerId;
        private int gained;
        private int lost;
        private int net;
        private int changeCount;
        private long lastChange;

        private MutablePlayerActivity(UUID playerId) {
            this.playerId = playerId;
        }
    }

    public record WindowTotals(int changes, int added, int removed, int net, int playersChanged) {
    }

    public record PlayerStats(int current, int gained, int lost, int net, int windowNet, ReputationChangeRecord lastChange) {
    }

    public record PlayerActivity(UUID playerId, int current, int gained, int lost, int net, int changeCount, long lastChange) {
    }

    public record ReasonCount(String reason, int count, int net) {
        private static final class Mutable {
            private final String reason;
            private int count;
            private int net;

            private Mutable(String reason) {
                this.reason = reason;
            }
        }
    }
}
