// src/main/java/org/enthusia/commend/rep/RepService.java
package org.enthusia.rep.rep;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.config.RepConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.enthusia.rep.discord.DiscordWebhook;

public class RepService {

    private final CommendPlugin plugin;
    private final File repFile;
    private final File commendationFile;
    private final File removedFile;
    private final Map<UUID, Integer> repMap = new ConcurrentHashMap<>();
    private final Map<UUID, Map<RepCategory, Integer>> categoryScores = new ConcurrentHashMap<>();

    private final Map<UUID, List<Commendation>> byTarget = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Commendation>> byGiver = new ConcurrentHashMap<>();
    private final Map<String, Long> removalCooldown = new ConcurrentHashMap<>();

    private final Map<String, List<AltRepRecord>> altRecords = new ConcurrentHashMap<>();
    private final List<SuspiciousRepCase> suspiciousCases = Collections.synchronizedList(new ArrayList<>());
    private final List<RemovedRep> removedLog = Collections.synchronizedList(new ArrayList<>());

    // Anti-abuse: track rep activity for reciprocity and cluster detection
    private final Map<String, Long> repActivityLog = new ConcurrentHashMap<>(); // "giverUUID->targetUUID" -> timestamp
    private static final long RECIPROCITY_WINDOW_MS = 24L * 60L * 60L * 1000L; // 24h
    private static final long CLUSTER_WINDOW_MS = 6L * 60L * 60L * 1000L;      // 6h
    private static final int CLUSTER_MIN_GIVERS = 3;                             // 3+ different givers = cluster

    private RepConfig repConfig;
    private DiscordWebhook discordWebhook; // set by plugin after construction

    public RepService(CommendPlugin plugin) {
        this.plugin = plugin;
        this.repConfig = plugin.getRepConfig();
        this.repFile = new File(plugin.getDataFolder(), "reputation.yml");
        this.commendationFile = new File(plugin.getDataFolder(), "commendations.yml");
        this.removedFile = new File(plugin.getDataFolder(), "removed-rep.yml");
        loadRepScores();
        loadCommendations();
        loadRemoved();
    }

    private void loadRepScores() {
        if (!repFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(repFile);
        for (String key : cfg.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                int score = cfg.getInt(key + ".score", 0);
                repMap.put(uuid, score);
                // Load category scores
                ConfigurationSection catSec = cfg.getConfigurationSection(key + ".categories");
                if (catSec != null) {
                    Map<RepCategory, Integer> cats = new ConcurrentHashMap<>();
                    for (String catKey : catSec.getKeys(false)) {
                        try {
                            RepCategory cat = RepCategory.valueOf(catKey);
                            cats.put(cat, catSec.getInt(catKey));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    if (!cats.isEmpty()) {
                        categoryScores.put(uuid, cats);
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void loadCommendations() {
        if (!commendationFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(commendationFile);
        ConfigurationSection root = cfg.getConfigurationSection("commendations");
        if (root == null) return;

        for (String idKey : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(idKey);
            Commendation c = Commendation.fromSection(sec);
            if (c != null) {
                cacheCommendation(c, false);
            }
        }
    }

    private void loadRemoved() {
        if (!removedFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(removedFile);
        List<Map<?, ?>> list = cfg.getMapList("removed");
        for (Map<?, ?> map : list) {
            RemovedRep rep = RemovedRep.fromMap(map);
            if (rep != null) removedLog.add(rep);
        }
    }

    public void saveAll() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> entry : repMap.entrySet()) {
            String key = entry.getKey().toString();
            cfg.set(key + ".score", entry.getValue());
            // Save category scores
            Map<RepCategory, Integer> cats = categoryScores.get(entry.getKey());
            if (cats != null && !cats.isEmpty()) {
                for (Map.Entry<RepCategory, Integer> catEntry : cats.entrySet()) {
                    cfg.set(key + ".categories." + catEntry.getKey().name(), catEntry.getValue());
                }
            }
        }
        try {
            cfg.save(repFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save reputation.yml: " + e.getMessage());
        }

        YamlConfiguration commendCfg = new YamlConfiguration();
        int counter = 0;
        for (List<Commendation> list : byTarget.values()) {
            for (Commendation c : list) {
                String path = "commendations." + (counter++);
                commendCfg.createSection(path, c.serialize());
            }
        }
        try {
            commendCfg.save(commendationFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save commendations.yml: " + e.getMessage());
        }

        YamlConfiguration removedCfg = new YamlConfiguration();
        List<Map<String, Object>> removed = new ArrayList<>();
        for (RemovedRep rep : removedLog) {
            removed.add(rep.serialize());
        }
        removedCfg.set("removed", removed);
        try {
            removedCfg.save(removedFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save removed-rep.yml: " + e.getMessage());
        }
    }

    public int getScore(UUID uuid) {
        return repMap.getOrDefault(uuid, 0);
    }

    public void setScore(UUID uuid, int score) {
        repMap.put(uuid, score);
    }

    public void adjustScore(UUID uuid, int delta) {
        int oldScore = getScore(uuid);
        int newScore = oldScore + delta;
        repMap.put(uuid, newScore);
        if (oldScore != newScore) {
            Bukkit.getPluginManager().callEvent(new org.enthusia.rep.events.RepMilestoneReachedEvent(uuid, oldScore, newScore));
        }
    }

    public void adjustCategoryScore(UUID uuid, RepCategory category, int delta) {
        categoryScores.compute(uuid, (id, map) -> {
            if (map == null) map = new ConcurrentHashMap<>();
            map.merge(category, delta, Integer::sum);
            if (map.get(category) == 0) map.remove(category);
            return map.isEmpty() ? null : map;
        });
    }

    public int getCategoryScore(UUID uuid, RepCategory category) {
        Map<RepCategory, Integer> map = categoryScores.get(uuid);
        return map != null ? map.getOrDefault(category, 0) : 0;
    }

    public Map<RepCategory, Integer> getAllCategoryScores(UUID uuid) {
        Map<RepCategory, Integer> map = categoryScores.get(uuid);
        return map != null ? new HashMap<>(map) : Collections.emptyMap();
    }

    /**
     * Returns the worst (most negative) score across all negative categories.
     */
    public int getWorstNegativeCategoryScore(UUID uuid) {
        Map<RepCategory, Integer> map = categoryScores.get(uuid);
        if (map == null) return 0;
        return map.entrySet().stream()
                .filter(e -> !e.getKey().isPositive())
                .mapToInt(Map.Entry::getValue)
                .min().orElse(0);
    }

    public Commendation getCommendation(UUID giver, UUID target) {
        Map<UUID, Commendation> map = byGiver.get(giver);
        if (map == null) return null;
        return map.get(target);
    }

    public List<Commendation> getCommendationsAbout(UUID target) {
        return byTarget.getOrDefault(target, Collections.emptyList());
    }

    /**
     * Returns all commendations RECEIVED by a player, sorted newest first.
     */
    public List<Commendation> getReceivedCommendations(UUID target) {
        List<Commendation> list = byTarget.get(target);
        if (list == null) return Collections.emptyList();
        List<Commendation> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingLong(Commendation::getCreatedAt).reversed());
        return sorted;
    }

    /**
     * Finds a specific commendation by giver, target, and category.
     */
    public Commendation findCommendation(UUID giver, UUID target, RepCategory category) {
        Commendation existing = getCommendation(giver, target);
        if (existing != null && existing.getCategory() == category) {
            return existing;
        }
        return null;
    }

    public List<Map.Entry<UUID, Integer>> top(int limit, boolean lowest) {
        Comparator<Map.Entry<UUID, Integer>> cmp = Map.Entry.comparingByValue();
        if (!lowest) cmp = cmp.reversed();
        return repMap.entrySet().stream()
                .sorted(cmp)
                .limit(limit)
                .toList();
    }

    public String nameOf(UUID uuid) {
        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        return off.getName() != null ? off.getName() : uuid.toString().substring(0, 8);
    }

    public CommendationResult addOrUpdateCommendation(UUID giver,
                                                      UUID target,
                                                      boolean positive,
                                                      RepCategory category,
                                                      String reasonText,
                                                      String ipHash) {
        long now = System.currentTimeMillis();
        Commendation existing = getCommendation(giver, target);
        if (existing == null) {
            long remainingCd = getRemovalCooldownMillis(giver, target);
            if (remainingCd > 0) {
                return CommendationResult.cooldown(remainingCd);
            }
            Commendation c = new Commendation(giver, target, positive, category, reasonText, now, now, ipHash);
            cacheCommendation(c, true);
            adjustScore(target, positive ? 1 : -2);
            adjustCategoryScore(target, category, positive ? 1 : -2);
            logAltRecord(ipHash, giver, target, positive, now);
            checkReciprocity(giver, target);
            if (!positive) checkNegativeCluster(target);
            if (discordWebhook != null) discordWebhook.logCommendation(c, getScore(target));
            removalCooldown.remove(key(giver, target));
            notifyTeleport(target);
            Bukkit.getPluginManager().callEvent(new org.enthusia.rep.events.CommendationGivenEvent(giver, target, positive));
            Bukkit.getPluginManager().callEvent(new org.enthusia.rep.events.CommendationReceivedEvent(target, giver, positive, getScore(target)));
            return CommendationResult.created(c);
        }

        long sinceEdit = now - existing.getLastEditedAt();
        if (sinceEdit < repConfig.getEditCooldownMillis()) {
            return CommendationResult.cooldown(repConfig.getEditCooldownMillis() - sinceEdit);
        }

        int delta = 0;
        if (existing.isPositive() != positive) {
            delta = positive ? 3 : -3;
        }
        // Remove old category contribution and add new
        adjustCategoryScore(target, existing.getCategory(), existing.isPositive() ? -1 : 2);
        existing.setPositive(positive);
        existing.setCategory(category);
        existing.setReasonText(reasonText);
        existing.setLastEditedAt(now);
        existing.setIpHash(ipHash);
        adjustScore(target, delta);
        adjustCategoryScore(target, category, positive ? 1 : -2);
        logAltRecord(ipHash, giver, target, positive, now);
        checkReciprocity(giver, target);
        if (!positive) checkNegativeCluster(target);
        if (discordWebhook != null && (delta != 0 || !existing.getCategory().equals(category))) {
            discordWebhook.logCommendation(existing, getScore(target));
        }
        if (delta != 0) {
            notifyTeleport(target);
        }
        removalCooldown.remove(key(giver, target));
        Bukkit.getPluginManager().callEvent(new org.enthusia.rep.events.CommendationEditedEvent(giver, target, positive));
        return CommendationResult.updated(existing, delta);
    }

    private void notifyTeleport(UUID target) {
        if (plugin.getTeleportIntegration() != null) {
            plugin.getTeleportIntegration().updatePlayer(target);
        }
    }

    private void cacheCommendation(Commendation c, boolean replace) {
        byTarget.computeIfAbsent(c.getTarget(), k -> new ArrayList<>());
        byGiver.computeIfAbsent(c.getGiver(), k -> new HashMap<>());

        // if replacing, remove old from target list
        if (replace) {
            Commendation old = byGiver.get(c.getGiver()).get(c.getTarget());
            if (old != null) {
                byTarget.get(c.getTarget()).removeIf(cm -> cm.getGiver().equals(c.getGiver()));
            }
        }

        byTarget.get(c.getTarget()).add(c);
        byGiver.get(c.getGiver()).put(c.getTarget(), c);
    }

    public void removeCommendation(UUID giver, UUID target) {
        removeCommendationInternal(giver, target);
    }

    public void removeCommendationWithCooldown(UUID giver, UUID target) {
        Commendation removed = removeCommendationInternal(giver, target);
        if (removed != null) {
            removalCooldown.put(key(giver, target), System.currentTimeMillis());
        }
    }

    public boolean canEdit(UUID giver, UUID target) {
        Commendation existing = getCommendation(giver, target);
        long now = System.currentTimeMillis();
        if (existing != null) {
            long since = now - existing.getLastEditedAt();
            return since >= repConfig.getEditCooldownMillis();
        }
        return getRemovalCooldownMillis(giver, target) <= 0;
    }

    public long getRemovalCooldownMillis(UUID giver, UUID target) {
        Long ts = removalCooldown.get(key(giver, target));
        if (ts == null) return 0;
        long remaining = repConfig.getEditCooldownMillis() - (System.currentTimeMillis() - ts);
        if (remaining <= 0) {
            removalCooldown.remove(key(giver, target));
            return 0;
        }
        return remaining;
    }

    private String key(UUID giver, UUID target) {
        return giver.toString() + "->" + target.toString();
    }

    public void resetAll(UUID target) {
        List<Commendation> list = byTarget.remove(target);
        if (list != null) {
            for (Commendation c : list) {
                removeCommendationLogged(null, c.getGiver(), target, false);
            }
        }
        notifyTeleport(target);
    }

    private void logAltRecord(String ipHash, UUID giver, UUID target, boolean positive, long time) {
        if (ipHash == null || ipHash.isEmpty()) return;
        AltRepRecord record = new AltRepRecord(giver, target, positive, time, ipHash);
        altRecords.computeIfAbsent(ipHash, k -> new ArrayList<>()).add(record);

        // crude detection: 2+ accounts on same hash downrepping same target within 48h
        if (!positive) {
            List<AltRepRecord> list = altRecords.get(ipHash);
            if (list == null) return;
            long window = 48L * 60L * 60L * 1000L;
            Set<UUID> givers = new HashSet<>();
            for (AltRepRecord r : list) {
                if (!r.target().equals(target)) continue;
                if (!r.positive() && time - r.timestamp() <= window) {
                    givers.add(r.giver());
                }
            }
            if (givers.size() >= 2) {
                SuspiciousRepCase c = new SuspiciousRepCase(target, ipHash, new ArrayList<>(givers), time);
                suspiciousCases.add(c);
                notifyStaff(c);
            }
        }
    }

    /**
     * Check for rep trading: A reps B, then B reps A within the reciprocity window.
     */
    private void checkReciprocity(UUID giver, UUID target) {
        String reverseKey = target.toString() + "->" + giver.toString();
        Long reverseTime = repActivityLog.get(reverseKey);
        if (reverseTime != null) {
            long elapsed = System.currentTimeMillis() - reverseTime;
            if (elapsed <= RECIPROCITY_WINDOW_MS) {
                SuspiciousRepCase c = new SuspiciousRepCase(target, "RECIPROCITY",
                        List.of(giver, target), System.currentTimeMillis());
                c.setDetail("Rep trading: " + nameOf(giver) + " and " + nameOf(target)
                        + " exchanged rep within " + (elapsed / 3600000) + "h");
                suspiciousCases.add(c);
                notifyStaff(c);
            }
        }
        // Record this activity
        String forwardKey = giver.toString() + "->" + target.toString();
        repActivityLog.put(forwardKey, System.currentTimeMillis());
    }

    /**
     * Check for mass downrepping: 3+ different givers downrep the same target within CLUSTER_WINDOW.
     */
    private void checkNegativeCluster(UUID target) {
        long now = System.currentTimeMillis();
        long cutoff = now - CLUSTER_WINDOW_MS;
        Set<UUID> recentDownreppers = new HashSet<>();

        for (Map.Entry<String, Long> entry : repActivityLog.entrySet()) {
            if (entry.getValue() < cutoff) continue;
            String[] parts = entry.getKey().split("->");
            if (parts.length != 2 || !parts[1].equals(target.toString())) continue;
            // Check that this was a negative rep (we only track after successful commendation)
            Commendation c = getCommendation(UUID.fromString(parts[0]), target);
            if (c != null && !c.isPositive()) {
                recentDownreppers.add(UUID.fromString(parts[0]));
            }
        }

        if (recentDownreppers.size() >= CLUSTER_MIN_GIVERS) {
            // Check if we already reported this cluster recently (dedup)
            boolean alreadyReported = suspiciousCases.stream()
                    .anyMatch(sc -> sc.getTarget().equals(target)
                            && "CLUSTER_DOWNREP".equals(sc.ipHash())
                            && now - sc.getCreatedAt() < CLUSTER_WINDOW_MS);
            if (!alreadyReported) {
                SuspiciousRepCase c = new SuspiciousRepCase(target, "CLUSTER_DOWNREP",
                        new ArrayList<>(recentDownreppers), now);
                c.setDetail("Mass downrep: " + recentDownreppers.size() + " players downrepped "
                        + nameOf(target) + " within " + (CLUSTER_WINDOW_MS / 3600000) + "h");
                suspiciousCases.add(c);
                notifyStaff(c);
            }
        }
    }

    private void notifyStaff(SuspiciousRepCase c) {
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("enthusiacommend.rep.alert"))
                .forEach(p -> {
                    String targetArg = resolveTargetArg(c.getTarget());
                    String inspectCmd = "/rep admin inspect " + targetArg + " " + c.ipHash();
                    Component msg = Component.text("ALT REP ALERT: IP group ", NamedTextColor.RED)
                            .append(Component.text(c.ipHash(), NamedTextColor.YELLOW)
                                    .clickEvent(ClickEvent.runCommand(inspectCmd))
                                    .hoverEvent(HoverEvent.showText(
                                            Component.text("Click to inspect report", NamedTextColor.GRAY))))
                            .append(Component.text(" accounts ", NamedTextColor.RED))
                            .append(Component.text(formatNames(c.givers()), NamedTextColor.WHITE))
                            .append(Component.text(" down-repped ", NamedTextColor.RED))
                            .append(Component.text(nameOf(c.getTarget()), NamedTextColor.YELLOW))
                            .append(Component.text(".", NamedTextColor.RED));
                    p.sendMessage(msg);
                });
    }

    private String formatNames(Collection<UUID> uuids) {
        List<String> names = new ArrayList<>();
        for (UUID u : uuids) {
            names.add(nameOf(u));
        }
        return String.join(", ", names);
    }

    private String resolveTargetArg(UUID target) {
        OfflinePlayer off = Bukkit.getOfflinePlayer(target);
        String name = off != null ? off.getName() : null;
        return name != null ? name : target.toString();
    }

    public String hashIp(String ip) {
        if (ip == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(ip.getBytes());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8 && i < bytes.length; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(ip.hashCode());
        }
    }

    public List<SuspiciousRepCase> getSuspiciousCases() {
        return suspiciousCases;
    }

    public void setRepConfig(RepConfig repConfig) {
        this.repConfig = repConfig;
    }

    public void setDiscordWebhook(DiscordWebhook discordWebhook) {
        this.discordWebhook = discordWebhook;
    }

    public RemovedRep removeCommendationLogged(UUID remover, UUID giver, UUID target, boolean applyCooldown) {
        Commendation removed = removeCommendationInternal(giver, target);
        if (removed == null) return null;
        if (applyCooldown) {
            removalCooldown.put(key(giver, target), System.currentTimeMillis());
        }
        RemovedRep record = new RemovedRep(nextRemovalId(), removed, System.currentTimeMillis(), remover);
        removedLog.add(record);
        return record;
    }

    public boolean restoreRemoved(String id) {
        if (id == null) return false;
        RemovedRep record = null;
        for (RemovedRep r : removedLog) {
            if (id.equalsIgnoreCase(r.id())) {
                record = r;
                break;
            }
        }
        if (record == null) return false;
        Commendation c = record.commendation();
        if (getCommendation(c.getGiver(), c.getTarget()) != null) {
            return false; // already exists, refuse to overwrite
        }
        cacheCommendation(cloneCommendation(c), true);
        adjustScore(c.getTarget(), c.isPositive() ? 1 : -2);
        adjustCategoryScore(c.getTarget(), c.getCategory(), c.isPositive() ? 1 : -2);
        removalCooldown.remove(key(c.getGiver(), c.getTarget()));
        removedLog.remove(record);
        notifyTeleport(c.getTarget());
        return true;
    }

    public List<RemovedRep> getRemovedLog() {
        return List.copyOf(removedLog);
    }

    private Commendation cloneCommendation(Commendation c) {
        return new Commendation(c.getGiver(), c.getTarget(), c.isPositive(), c.getCategory(),
                c.getReasonText(), c.getCreatedAt(), c.getLastEditedAt(), c.getIpHash());
    }

    private Commendation removeCommendationInternal(UUID giver, UUID target) {
        Commendation existing = getCommendation(giver, target);
        if (existing == null) return null;
        byGiver.computeIfPresent(giver, (g, map) -> {
            map.remove(target);
            return map.isEmpty() ? null : map;
        });
        byTarget.computeIfPresent(target, (t, list) -> {
            list.removeIf(cm -> cm.getGiver().equals(giver));
            return list.isEmpty() ? null : list;
        });
        adjustScore(target, existing.isPositive() ? -1 : 2);
        adjustCategoryScore(target, existing.getCategory(), existing.isPositive() ? -1 : 2);
        notifyTeleport(target);
        return existing;
    }

    private String nextRemovalId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public List<SuspiciousRepCase> getCasesForTarget(UUID target, boolean includeResolved) {
        List<SuspiciousRepCase> result = new ArrayList<>();
        for (SuspiciousRepCase c : suspiciousCases) {
            if (!Objects.equals(c.getTarget(), target)) continue;
            if (!includeResolved && c.isResolved()) continue;
            result.add(c);
        }
        return result;
    }

    public boolean resolveCase(UUID target, String ipHash) {
        boolean changed = false;
        for (SuspiciousRepCase c : suspiciousCases) {
            if (Objects.equals(c.getTarget(), target) && c.ipHash().equalsIgnoreCase(ipHash)) {
                c.setResolved(true);
                changed = true;
            }
        }
        return changed;
    }

    public record AltRepRecord(UUID giver, UUID target, boolean positive, long timestamp, String ipHash) {}

    public static final class SuspiciousRepCase {
        private final UUID target;
        private final String ipHash;
        private final List<UUID> givers;
        private final long createdAt;
        private boolean resolved;
        private String detail;

        public SuspiciousRepCase(UUID target, String ipHash, List<UUID> givers, long createdAt) {
            this.target = target;
            this.ipHash = ipHash;
            this.givers = givers;
            this.createdAt = createdAt;
            this.resolved = false;
            this.detail = null;
        }

        public UUID getTarget() {
            return target;
        }

        public String ipHash() {
            return ipHash;
        }

        public List<UUID> givers() {
            return givers;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public boolean isResolved() {
            return resolved;
        }

        public void setResolved(boolean resolved) {
            this.resolved = resolved;
        }

        public String getDetail() {
            return detail;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }
    }

    public record CommendationResult(boolean success,
                                     boolean created,
                                     Commendation commendation,
                                     long cooldownRemainingMillis,
                                     int repDelta) {
        public static CommendationResult created(Commendation c) {
            return new CommendationResult(true, true, c, 0, c.isPositive() ? 1 : -2);
        }

        public static CommendationResult updated(Commendation c, int delta) {
            return new CommendationResult(true, false, c, 0, delta);
        }

        public static CommendationResult cooldown(long millis) {
            return new CommendationResult(false, false, null, millis, 0);
        }
    }

    public record RemovedRep(String id, Commendation commendation, long removedAt, UUID removedBy) {
        public Map<String, Object> serialize() {
            Map<String, Object> map = new HashMap<>(commendation.serialize());
            map.put("id", id);
            map.put("removedAt", removedAt);
            if (removedBy != null) {
                map.put("removedBy", removedBy.toString());
            }
            return map;
        }

        public static RemovedRep fromMap(Map<?, ?> raw) {
            try {
                Object idObj = raw.get("id");
                if (idObj == null) return null;
                String id = idObj.toString();
                UUID giver = UUID.fromString(String.valueOf(raw.get("giver")));
                UUID target = UUID.fromString(String.valueOf(raw.get("target")));
                boolean positive = raw.get("positive") instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw.get("positive")));
                String catRaw = String.valueOf(raw.get("category"));
                RepCategory category;
                try {
                    category = RepCategory.valueOf(catRaw);
                } catch (IllegalArgumentException e) {
                    category = "OTHER_NEGATIVE".equals(catRaw) ? RepCategory.SCAMMED : RepCategory.WAS_KIND;
                }
                String reason = Objects.toString(raw.get("reason"), "");
                long createdAt = raw.get("createdAt") instanceof Number n ? n.longValue() : System.currentTimeMillis();
                long lastEditedAt = raw.get("lastEditedAt") instanceof Number n2 ? n2.longValue() : createdAt;
                String ipHash = raw.get("ipHash") != null ? raw.get("ipHash").toString() : null;
                Commendation c = new Commendation(giver, target, positive, category, reason, createdAt, lastEditedAt, ipHash);
                long removedAt = raw.get("removedAt") instanceof Number n3 ? n3.longValue() : System.currentTimeMillis();
                UUID removedBy = null;
                Object rb = raw.get("removedBy");
                if (rb != null) {
                    try {
                        removedBy = UUID.fromString(rb.toString());
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                return new RemovedRep(id, c, removedAt, removedBy);
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
