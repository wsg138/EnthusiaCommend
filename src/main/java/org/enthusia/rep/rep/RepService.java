package org.enthusia.rep.rep;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.config.RepConfig;
import org.enthusia.rep.storage.PluginDataSnapshot;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class RepService {

    private final CommendPlugin plugin;
    private final Runnable dirtyMarker;
    private final Consumer<UUID> scoreChangeListener;

    private RepConfig repConfig;

    private final Map<UUID, Integer> scoreByPlayer = new HashMap<>();
    private final Map<UUID, List<Commendation>> commendationsByTarget = new HashMap<>();
    private final Map<UUID, Map<UUID, Commendation>> commendationsByGiver = new HashMap<>();
    private final Map<String, Long> removalCooldowns = new HashMap<>();
    private final Map<String, List<AltRepRecord>> altRecordsByHash = new HashMap<>();
    private final List<SuspiciousRepCase> suspiciousCases = new ArrayList<>();
    private final List<RemovedRep> removedEntries = new ArrayList<>();

    public RepService(
            CommendPlugin plugin,
            RepConfig repConfig,
            PluginDataSnapshot dataSnapshot,
            Runnable dirtyMarker,
            Consumer<UUID> scoreChangeListener
    ) {
        this.plugin = plugin;
        this.repConfig = repConfig;
        this.dirtyMarker = dirtyMarker;
        this.scoreChangeListener = scoreChangeListener;
        loadSnapshot(dataSnapshot);
    }

    public void reload(RepConfig repConfig) {
        this.repConfig = repConfig;
    }

    private void loadSnapshot(PluginDataSnapshot snapshot) {
        scoreByPlayer.clear();
        scoreByPlayer.putAll(snapshot.scores());

        commendationsByTarget.clear();
        commendationsByGiver.clear();
        for (Commendation commendation : snapshot.commendations()) {
            cacheCommendation(cloneCommendation(commendation), false);
        }

        removedEntries.clear();
        removedEntries.addAll(snapshot.removedEntries().stream().map(RemovedRep::copy).toList());
        removalCooldowns.clear();
        altRecordsByHash.clear();
        suspiciousCases.clear();
    }

    public PluginDataSnapshot snapshot(PluginDataSnapshot base) {
        Map<UUID, Integer> scores = new LinkedHashMap<>(scoreByPlayer);
        List<Commendation> commendations = new ArrayList<>();
        for (List<Commendation> entries : commendationsByTarget.values()) {
            for (Commendation commendation : entries) {
                commendations.add(cloneCommendation(commendation));
            }
        }
        commendations.sort(Comparator.comparingLong(Commendation::getCreatedAt));

        List<RemovedRep> removed = removedEntries.stream().map(RemovedRep::copy).toList();
        return new PluginDataSnapshot(scores, commendations, removed, base.stalkEntries());
    }

    public int getScore(UUID playerId) {
        return scoreByPlayer.getOrDefault(playerId, 0);
    }

    public void setScore(UUID playerId, int score) {
        applyScore(playerId, score, true);
    }

    public void adjustScore(UUID playerId, int delta) {
        if (delta == 0) {
            return;
        }
        applyScore(playerId, getScore(playerId) + delta, true);
    }

    private void applyScore(UUID playerId, int newScore, boolean emitEvent) {
        int oldScore = getScore(playerId);
        scoreByPlayer.put(playerId, newScore);
        if (oldScore != newScore) {
            dirtyMarker.run();
            if (emitEvent) {
                Bukkit.getPluginManager().callEvent(new org.enthusia.rep.events.RepMilestoneReachedEvent(playerId, oldScore, newScore));
            }
            scoreChangeListener.accept(playerId);
        }
    }

    public Commendation getCommendation(UUID giverId, UUID targetId) {
        Map<UUID, Commendation> given = commendationsByGiver.get(giverId);
        return given == null ? null : given.get(targetId);
    }

    public List<Commendation> getCommendationsAbout(UUID targetId) {
        List<Commendation> commendations = commendationsByTarget.get(targetId);
        return commendations == null ? List.of() : List.copyOf(commendations);
    }

    public List<Map.Entry<UUID, Integer>> top(int limit, boolean lowest) {
        Comparator<Map.Entry<UUID, Integer>> comparator = Map.Entry.comparingByValue();
        if (!lowest) {
            comparator = comparator.reversed();
        }
        return scoreByPlayer.entrySet().stream()
                .sorted(comparator)
                .limit(Math.max(1, limit))
                .toList();
    }

    public String nameOf(UUID playerId) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        return player.getName() != null ? player.getName() : playerId.toString().substring(0, 8);
    }

    public CommendationResult addOrUpdateCommendation(
            UUID giverId,
            UUID targetId,
            boolean positive,
            RepCategory category,
            String reasonText,
            String ipHash
    ) {
        long now = System.currentTimeMillis();
        Commendation existing = getCommendation(giverId, targetId);

        if (existing == null) {
            long remainingCooldown = getRemovalCooldownMillis(giverId, targetId);
            if (remainingCooldown > 0) {
                return CommendationResult.cooldown(remainingCooldown);
            }

            Commendation created = new Commendation(giverId, targetId, positive, category, reasonText, now, now, ipHash);
            cacheCommendation(created, true);
            applyScore(targetId, getScore(targetId) + (positive ? 1 : -1), true);
            logAltRecord(ipHash, giverId, targetId, positive, now);
            removalCooldowns.remove(key(giverId, targetId));
            dirtyMarker.run();

            Bukkit.getPluginManager().callEvent(new org.enthusia.rep.events.CommendationGivenEvent(giverId, targetId, positive));
            Bukkit.getPluginManager().callEvent(new org.enthusia.rep.events.CommendationReceivedEvent(targetId, giverId, positive, getScore(targetId)));
            return CommendationResult.created(created);
        }

        long sinceLastEdit = now - existing.getLastEditedAt();
        if (sinceLastEdit < repConfig.getEditCooldownMillis()) {
            return CommendationResult.cooldown(repConfig.getEditCooldownMillis() - sinceLastEdit);
        }

        int delta = 0;
        if (existing.isPositive() != positive) {
            delta = positive ? 2 : -2;
        }
        existing.setPositive(positive);
        existing.setCategory(category);
        existing.setReasonText(reasonText);
        existing.setLastEditedAt(now);
        existing.setIpHash(ipHash);
        if (delta != 0) {
            applyScore(targetId, getScore(targetId) + delta, true);
        }
        logAltRecord(ipHash, giverId, targetId, positive, now);
        removalCooldowns.remove(key(giverId, targetId));
        dirtyMarker.run();

        Bukkit.getPluginManager().callEvent(new org.enthusia.rep.events.CommendationEditedEvent(giverId, targetId, positive));
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

    private RemovedRep removeCommendationInternal(UUID giverId, UUID targetId, boolean applyCooldown, boolean logRemoval, UUID removerId) {
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
            byTarget.removeIf(commendation -> commendation.getGiver().equals(giverId));
            if (byTarget.isEmpty()) {
                commendationsByTarget.remove(targetId);
            }
        }

        applyScore(targetId, getScore(targetId) + (existing.isPositive() ? -1 : 1), true);
        if (applyCooldown) {
            removalCooldowns.put(key(giverId, targetId), System.currentTimeMillis());
        } else {
            removalCooldowns.remove(key(giverId, targetId));
        }

        RemovedRep removedRep = null;
        if (logRemoval) {
            removedRep = new RemovedRep(nextRemovalId(), cloneCommendation(existing), System.currentTimeMillis(), removerId);
            removedEntries.add(removedRep);
        }

        dirtyMarker.run();
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
        long remaining = repConfig.getEditCooldownMillis() - (System.currentTimeMillis() - removedAt);
        if (remaining <= 0L) {
            removalCooldowns.remove(key(giverId, targetId));
            return 0L;
        }
        return remaining;
    }

    public void resetAll(UUID targetId) {
        List<Commendation> current = new ArrayList<>(commendationsByTarget.getOrDefault(targetId, List.of()));
        for (Commendation commendation : current) {
            removeCommendationLogged(null, commendation.getGiver(), targetId, false);
        }
    }

    public String hashIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(ipAddress.getBytes());
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
        return List.copyOf(suspiciousCases);
    }

    public List<SuspiciousRepCase> getCasesForTarget(UUID targetId, boolean includeResolved) {
        return suspiciousCases.stream()
                .filter(entry -> entry.targetId.equals(targetId))
                .filter(entry -> includeResolved || !entry.resolved)
                .toList();
    }

    public boolean resolveCase(UUID targetId, String ipHash) {
        boolean changed = false;
        for (SuspiciousRepCase entry : suspiciousCases) {
            if (entry.targetId.equals(targetId) && entry.ipHash.equalsIgnoreCase(ipHash) && !entry.resolved) {
                entry.resolved = true;
                changed = true;
            }
        }
        return changed;
    }

    public List<RemovedRep> getRemovedLog() {
        return removedEntries.stream().map(RemovedRep::copy).toList();
    }

    public boolean restoreRemoved(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        RemovedRep removed = removedEntries.stream()
                .filter(entry -> id.equalsIgnoreCase(entry.id))
                .findFirst()
                .orElse(null);
        if (removed == null) {
            return false;
        }

        Commendation commendation = removed.commendation;
        if (getCommendation(commendation.getGiver(), commendation.getTarget()) != null) {
            return false;
        }

        cacheCommendation(cloneCommendation(commendation), true);
        applyScore(commendation.getTarget(), getScore(commendation.getTarget()) + (commendation.isPositive() ? 1 : -1), true);
        removalCooldowns.remove(key(commendation.getGiver(), commendation.getTarget()));
        removedEntries.remove(removed);
        dirtyMarker.run();
        return true;
    }

    private void cacheCommendation(Commendation commendation, boolean replaceExisting) {
        commendationsByTarget.computeIfAbsent(commendation.getTarget(), ignored -> new ArrayList<>());
        commendationsByGiver.computeIfAbsent(commendation.getGiver(), ignored -> new HashMap<>());

        if (replaceExisting) {
            Commendation previous = commendationsByGiver.get(commendation.getGiver()).get(commendation.getTarget());
            if (previous != null) {
                List<Commendation> existing = commendationsByTarget.get(commendation.getTarget());
                existing.removeIf(entry -> entry.getGiver().equals(commendation.getGiver()));
            }
        }

        commendationsByTarget.get(commendation.getTarget()).add(commendation);
        commendationsByGiver.get(commendation.getGiver()).put(commendation.getTarget(), commendation);
    }

    private void logAltRecord(String ipHash, UUID giverId, UUID targetId, boolean positive, long timestamp) {
        if (ipHash == null || ipHash.isBlank()) {
            return;
        }

        altRecordsByHash.computeIfAbsent(ipHash, ignored -> new ArrayList<>())
                .add(new AltRepRecord(giverId, targetId, positive, timestamp, ipHash));

        if (positive) {
            return;
        }

        List<AltRepRecord> records = altRecordsByHash.getOrDefault(ipHash, List.of());
        long windowMillis = 48L * 60L * 60L * 1000L;
        Set<UUID> givers = new HashSet<>();
        for (AltRepRecord record : records) {
            if (!record.targetId.equals(targetId)) {
                continue;
            }
            if (!record.positive && timestamp - record.timestamp <= windowMillis) {
                givers.add(record.giverId);
            }
        }

        if (givers.size() >= 2) {
            boolean duplicate = suspiciousCases.stream()
                    .anyMatch(entry -> entry.targetId.equals(targetId) && entry.ipHash.equalsIgnoreCase(ipHash) && !entry.resolved);
            if (!duplicate) {
                SuspiciousRepCase created = new SuspiciousRepCase(targetId, ipHash, new ArrayList<>(givers), timestamp, false);
                suspiciousCases.add(created);
                notifyStaff(created);
            }
        }
    }

    private void notifyStaff(SuspiciousRepCase caseData) {
        String targetArg = resolveTargetArgument(caseData.targetId);
        String inspectCommand = "/rep admin inspect " + targetArg + " " + caseData.ipHash;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("enthusiacommend.rep.alert")) {
                continue;
            }
            ComponentBuilder builder = new ComponentBuilder("ALT REP ALERT: IP group ")
                    .color(net.md_5.bungee.api.ChatColor.RED)
                    .append(caseData.ipHash)
                    .color(net.md_5.bungee.api.ChatColor.YELLOW)
                    .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, inspectCommand))
                    .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            new ComponentBuilder("Click to inspect report").color(net.md_5.bungee.api.ChatColor.GRAY).create()))
                    .append(" accounts ")
                    .color(net.md_5.bungee.api.ChatColor.RED)
                    .event((ClickEvent) null)
                    .event((HoverEvent) null)
                    .append(formatNames(caseData.giverIds))
                    .color(net.md_5.bungee.api.ChatColor.WHITE)
                    .append(" down-repped ")
                    .color(net.md_5.bungee.api.ChatColor.RED)
                    .append(nameOf(caseData.targetId))
                    .color(net.md_5.bungee.api.ChatColor.YELLOW)
                    .append(".")
                    .color(net.md_5.bungee.api.ChatColor.RED);
            player.spigot().sendMessage(builder.create());
        }
    }

    private String formatNames(Collection<UUID> playerIds) {
        List<String> names = new ArrayList<>();
        for (UUID playerId : playerIds) {
            names.add(nameOf(playerId));
        }
        return String.join(", ", names);
    }

    private String resolveTargetArgument(UUID targetId) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(targetId);
        return player.getName() != null ? player.getName() : targetId.toString();
    }

    private String key(UUID giverId, UUID targetId) {
        return giverId + "->" + targetId;
    }

    private Commendation cloneCommendation(Commendation commendation) {
        return new Commendation(
                commendation.getGiver(),
                commendation.getTarget(),
                commendation.isPositive(),
                commendation.getCategory(),
                commendation.getReasonText(),
                commendation.getCreatedAt(),
                commendation.getLastEditedAt(),
                commendation.getIpHash()
        );
    }

    private String nextRemovalId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record AltRepRecord(UUID giverId, UUID targetId, boolean positive, long timestamp, String ipHash) {
    }

    public static final class SuspiciousRepCase {
        private final UUID targetId;
        private final String ipHash;
        private final List<UUID> giverIds;
        private final long createdAt;
        private boolean resolved;

        public SuspiciousRepCase(UUID targetId, String ipHash, List<UUID> giverIds, long createdAt, boolean resolved) {
            this.targetId = targetId;
            this.ipHash = ipHash;
            this.giverIds = List.copyOf(giverIds);
            this.createdAt = createdAt;
            this.resolved = resolved;
        }

        public UUID getTarget() {
            return targetId;
        }

        public String ipHash() {
            return ipHash;
        }

        public List<UUID> givers() {
            return giverIds;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public boolean isResolved() {
            return resolved;
        }
    }

    public record CommendationResult(boolean success, boolean created, Commendation commendation, long cooldownRemainingMillis, int repDelta) {
        public static CommendationResult created(Commendation commendation) {
            return new CommendationResult(true, true, commendation, 0L, commendation.isPositive() ? 1 : -1);
        }

        public static CommendationResult updated(Commendation commendation, int delta) {
            return new CommendationResult(true, false, commendation, 0L, delta);
        }

        public static CommendationResult cooldown(long remainingMillis) {
            return new CommendationResult(false, false, null, remainingMillis, 0);
        }
    }

    public static final class RemovedRep {
        private final String id;
        private final Commendation commendation;
        private final long removedAt;
        private final UUID removedBy;

        public RemovedRep(String id, Commendation commendation, long removedAt, UUID removedBy) {
            this.id = id;
            this.commendation = commendation;
            this.removedAt = removedAt;
            this.removedBy = removedBy;
        }

        public String id() {
            return id;
        }

        public Commendation commendation() {
            return commendation;
        }

        public long removedAt() {
            return removedAt;
        }

        public UUID removedBy() {
            return removedBy;
        }

        public Map<String, Object> serialize() {
            Map<String, Object> map = new LinkedHashMap<>(commendation.serialize());
            map.put("id", id);
            map.put("removedAt", removedAt);
            if (removedBy != null) {
                map.put("removedBy", removedBy.toString());
            }
            return map;
        }

        public static RemovedRep fromMap(Map<?, ?> raw) {
            try {
                String id = String.valueOf(raw.get("id"));
                UUID giver = UUID.fromString(String.valueOf(raw.get("giver")));
                UUID target = UUID.fromString(String.valueOf(raw.get("target")));
                boolean positive = raw.get("positive") instanceof Boolean flag
                        ? flag
                        : Boolean.parseBoolean(String.valueOf(raw.get("positive")));
                RepCategory category = RepCategory.valueOf(String.valueOf(raw.get("category")));
                String reason = Objects.toString(raw.get("reason"), "");
                long createdAt = raw.get("createdAt") instanceof Number value ? value.longValue() : Instant.now().toEpochMilli();
                long lastEditedAt = raw.get("lastEditedAt") instanceof Number value ? value.longValue() : createdAt;
                String ipHash = raw.get("ipHash") != null ? raw.get("ipHash").toString() : null;
                long removedAt = raw.get("removedAt") instanceof Number value ? value.longValue() : Instant.now().toEpochMilli();
                UUID removedBy = null;
                if (raw.get("removedBy") != null) {
                    removedBy = UUID.fromString(String.valueOf(raw.get("removedBy")));
                }
                Commendation commendation = new Commendation(giver, target, positive, category, reason, createdAt, lastEditedAt, ipHash);
                return new RemovedRep(id, commendation, removedAt, removedBy);
            } catch (Exception ignored) {
                return null;
            }
        }

        public RemovedRep copy() {
            return new RemovedRep(id, new Commendation(
                    commendation.getGiver(),
                    commendation.getTarget(),
                    commendation.isPositive(),
                    commendation.getCategory(),
                    commendation.getReasonText(),
                    commendation.getCreatedAt(),
                    commendation.getLastEditedAt(),
                    commendation.getIpHash()
            ), removedAt, removedBy);
        }
    }
}
