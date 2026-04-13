package org.enthusia.rep.stalk;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.region.RegionManager;
import org.enthusia.rep.rep.RepService;
import org.enthusia.rep.stalk.StalkSubscription;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class StalkManager implements Listener {

    private final CommendPlugin plugin;
    private final RegionManager regions;
    private final RepService repService;
    private final File dataFile;

    private static class Subscription {
        final UUID stalker;
        final long expiresAt;

        Subscription(UUID stalker, long expiresAt) {
            this.stalker = stalker;
            this.expiresAt = expiresAt;
        }
    }

    // target -> list of subscriptions
    private final Map<UUID, List<Subscription>> byTarget = new ConcurrentHashMap<>();

    // track last known warzone state
    private final Map<UUID, Boolean> lastInWarzone = new ConcurrentHashMap<>();

    public StalkManager(CommendPlugin plugin, RegionManager regions) {
        this.plugin = plugin;
        this.regions = regions;
        this.repService = plugin.getRepService();
        this.dataFile = new File(plugin.getDataFolder(), "stalks.yml");
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void addSubscription(UUID stalker, UUID target, long durationMillis) {
        long expires = System.currentTimeMillis() + durationMillis;
        byTarget.compute(target, (id, list) -> {
            if (list == null) list = new ArrayList<>();
            boolean updated = false;
            for (int i = 0; i < list.size(); i++) {
                Subscription s = list.get(i);
                if (s.stalker.equals(stalker)) {
                    list.set(i, new Subscription(stalker, expires));
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                list.add(new Subscription(stalker, expires));
            }
            return list;
        });
        save();
    }

    public List<Subscription> getSubscriptionsOf(UUID target) {
        return byTarget.getOrDefault(target, Collections.emptyList());
    }

    public void cancelSubscription(UUID stalker, UUID target) {
        List<Subscription> list = byTarget.get(target);
        if (list == null) return;
        list.removeIf(s -> s.stalker.equals(stalker));
        if (list.isEmpty()) {
            byTarget.remove(target);
        } else {
            byTarget.put(target, list);
        }
        save();
    }

    public boolean isStalkable(UUID target) {
        return repService.getScore(target) <= -12;
    }

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        if (!cfg.isConfigurationSection("subs")) return;
        long now = System.currentTimeMillis();
        for (String key : cfg.getConfigurationSection("subs").getKeys(false)) {
            UUID target = UUID.fromString(cfg.getString("subs." + key + ".target"));
            UUID stalker = UUID.fromString(cfg.getString("subs." + key + ".stalker"));
            long expires = cfg.getLong("subs." + key + ".expiresAt");
            if (expires > now) {
                addSubscription(stalker, target, expires - now);
            }
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        int idx = 0;
        for (Map.Entry<UUID, List<Subscription>> e : byTarget.entrySet()) {
            for (Subscription s : e.getValue()) {
                String path = "subs." + (idx++);
                cfg.set(path + ".target", e.getKey().toString());
                cfg.set(path + ".stalker", s.stalker.toString());
                cfg.set(path + ".expiresAt", s.expiresAt);
            }
        }
        try {
            cfg.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save stalks.yml: " + ex.getMessage());
        }
    }

    private List<UUID> getActiveStalkers(UUID target) {
        List<Subscription> list = byTarget.get(target);
        if (list == null || list.isEmpty()) return Collections.emptyList();

        long now = System.currentTimeMillis();
        List<Subscription> still = new ArrayList<>();
        List<UUID> result = new ArrayList<>();

        for (Subscription s : list) {
            if (s.expiresAt > now) {
                still.add(s);
                result.add(s.stalker);
            }
        }

        if (still.isEmpty()) {
            byTarget.remove(target);
        } else {
            byTarget.put(target, still);
        }
        return result;
    }

    public List<StalkSubscription> getSubscriptionsByStalker(UUID stalker) {
        List<StalkSubscription> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, List<Subscription>> e : byTarget.entrySet()) {
            for (Subscription s : e.getValue()) {
                if (s.stalker.equals(stalker) && s.expiresAt > now) {
                    result.add(new StalkSubscription(e.getKey(), s.expiresAt));
                }
            }
        }
        return result;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        boolean wasIn = lastInWarzone.getOrDefault(id, false);
        boolean nowIn = regions.isInWarzone(event.getTo());

        if (wasIn == nowIn) {
            return; // no change
        }

        lastInWarzone.put(id, nowIn);

        List<UUID> stalkers = getActiveStalkers(id);
        if (stalkers.isEmpty()) return;

        String msg;
        if (nowIn) {
            msg = ChatColor.GOLD + "[Stalk] " + ChatColor.YELLOW + player.getName()
                    + ChatColor.GOLD + " entered Warzone at "
                    + ChatColor.YELLOW + player.getLocation().getBlockX() + " "
                    + player.getLocation().getBlockY() + " "
                    + player.getLocation().getBlockZ();
        } else {
            msg = ChatColor.GOLD + "[Stalk] " + ChatColor.YELLOW + player.getName()
                    + ChatColor.GOLD + " left Warzone.";
        }

        for (UUID stalkerId : stalkers) {
            Player stalker = Bukkit.getPlayer(stalkerId);
            if (stalker != null && stalker.isOnline()) {
                stalker.sendMessage(msg);
            }
        }
    }
}
