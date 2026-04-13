// src/main/java/org/enthusia/commend/rep/RepService.java
package org.enthusia.rep.rep;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.config.RepConfig;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RepService {

    private final CommendPlugin plugin;
    private final File repFile;
    private final File commendationFile;
    private final File removedFile;
    private final Map<UUID, Integer> repMap = new ConcurrentHashMap<>();

    private final Map<UUID, List<Commendation>> byTarget = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Commendation>> byGiver = new ConcurrentHashMap<>();
    private final Map<String, Long> removalCooldown = new ConcurrentHashMap<>();

    private final Map<String, List<AltRepRecord>> altRecords = new ConcurrentHashMap<>();
    private final List<SuspiciousRepCase> suspiciousCases = Collections.synchronizedList(new ArrayList<>());
    private final List<RemovedRep> removedLog = Collections.synchronizedList(new ArrayList<>());

    private RepConfig repConfig;

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
        repMap.put(uuid, getScore(uuid) + delta);
    }

    public Commendation getCommendation(UUID giver, UUID target) {
        Map<UUID, Commendation> map = byGiver.get(giver);
        if (map == null) return null;
        return map.get(target);
    }

    public List<Commendation> getCommendationsAbout(UUID target) {
        return byTarget.getOrDefault(target, Collections.emptyList());
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
            adjustScore(target, positive ? 1 : -1);
            logAltRecord(ipHash, giver, target, positive, now);
            removalCooldown.remove(key(giver, target));
            notifyTeleport(target);
            return CommendationResult.created(c);
        }

        long sinceEdit = now - existing.getLastEditedAt();
        if (sinceEdit < repConfig.getEditCooldownMillis()) {
            return CommendationResult.cooldown(repConfig.getEditCooldownMillis() - sinceEdit);
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
        adjustScore(target, delta);
        logAltRecord(ipHash, giver, target, positive, now);
        if (delta != 0) {
            notifyTeleport(target);
        }
        removalCooldown.remove(key(giver, target));
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

    private void notifyStaff(SuspiciousRepCase c) {
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("enthusiacommend.rep.alert"))
                .forEach(p -> {
                    String targetArg = resolveTargetArg(c.getTarget());
                    String inspectCmd = "/rep admin inspect " + targetArg + " " + c.ipHash();
                    ComponentBuilder builder = new ComponentBuilder("ALT REP ALERT: IP group ")
                            .color(net.md_5.bungee.api.ChatColor.RED)
                            .append(c.ipHash())
                            .color(net.md_5.bungee.api.ChatColor.YELLOW)
                            .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, inspectCmd))
                            .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    new ComponentBuilder("Click to inspect report")
                                            .color(net.md_5.bungee.api.ChatColor.GRAY)
                                            .create()))
                            .append(" accounts ")
                            .color(net.md_5.bungee.api.ChatColor.RED)
                            .event((ClickEvent) null)
                            .event((HoverEvent) null)
                            .append(formatNames(c.givers()))
                            .color(net.md_5.bungee.api.ChatColor.WHITE)
                            .append(" down-repped ")
                            .color(net.md_5.bungee.api.ChatColor.RED)
                            .append(nameOf(c.getTarget()))
                            .color(net.md_5.bungee.api.ChatColor.YELLOW)
                            .append(".")
                            .color(net.md_5.bungee.api.ChatColor.RED);
                    p.spigot().sendMessage(builder.create());
                }); // brief alert
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
        adjustScore(c.getTarget(), c.isPositive() ? 1 : -1);
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
        adjustScore(target, existing.isPositive() ? -1 : 1);
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

        public SuspiciousRepCase(UUID target, String ipHash, List<UUID> givers, long createdAt) {
            this.target = target;
            this.ipHash = ipHash;
            this.givers = givers;
            this.createdAt = createdAt;
            this.resolved = false;
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
    }

    public record CommendationResult(boolean success,
                                     boolean created,
                                     Commendation commendation,
                                     long cooldownRemainingMillis,
                                     int repDelta) {
        public static CommendationResult created(Commendation c) {
            return new CommendationResult(true, true, c, 0, c.isPositive() ? 1 : -1);
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
                RepCategory category = RepCategory.valueOf(catRaw);
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
