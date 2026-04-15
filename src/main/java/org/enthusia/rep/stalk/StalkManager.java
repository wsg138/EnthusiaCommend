package org.enthusia.rep.stalk;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.enthusia.rep.config.RepConfig;
import org.enthusia.rep.region.RegionManager;
import org.enthusia.rep.rep.RepService;
import org.enthusia.rep.storage.PluginDataSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class StalkManager implements Listener {

    private final RegionManager regionManager;
    private final RepService repService;
    private final Runnable dirtyMarker;

    private RepConfig config;

    private final Map<UUID, Map<UUID, Long>> subscriptionsByTarget = new HashMap<>();
    private final Map<UUID, RegionManager.ZoneType> lastKnownZones = new HashMap<>();

    public StalkManager(RegionManager regionManager, RepService repService, RepConfig config, Runnable dirtyMarker) {
        this.regionManager = regionManager;
        this.repService = repService;
        this.config = config;
        this.dirtyMarker = dirtyMarker;
    }

    public void load(PluginDataSnapshot snapshot) {
        subscriptionsByTarget.clear();
        long now = System.currentTimeMillis();
        for (PluginDataSnapshot.StalkEntry entry : snapshot.stalkEntries()) {
            if (entry.expiresAt() <= now) {
                continue;
            }
            subscriptionsByTarget
                    .computeIfAbsent(entry.targetId(), ignored -> new HashMap<>())
                    .put(entry.stalkerId(), entry.expiresAt());
        }
    }

    public void reload(RepConfig config) {
        this.config = config;
    }

    public List<PluginDataSnapshot.StalkEntry> snapshotEntries() {
        long now = System.currentTimeMillis();
        List<PluginDataSnapshot.StalkEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, Map<UUID, Long>> targetEntry : subscriptionsByTarget.entrySet()) {
            for (Map.Entry<UUID, Long> stalkerEntry : targetEntry.getValue().entrySet()) {
                if (stalkerEntry.getValue() > now) {
                    entries.add(new PluginDataSnapshot.StalkEntry(stalkerEntry.getKey(), targetEntry.getKey(), stalkerEntry.getValue()));
                }
            }
        }
        return entries;
    }

    public void addSubscription(UUID stalkerId, UUID targetId, long durationMillis) {
        long expiresAt = System.currentTimeMillis() + durationMillis;
        subscriptionsByTarget.computeIfAbsent(targetId, ignored -> new HashMap<>()).put(stalkerId, expiresAt);
        dirtyMarker.run();
    }

    public void cancelSubscription(UUID stalkerId, UUID targetId) {
        Map<UUID, Long> entries = subscriptionsByTarget.get(targetId);
        if (entries == null) {
            return;
        }
        entries.remove(stalkerId);
        if (entries.isEmpty()) {
            subscriptionsByTarget.remove(targetId);
        }
        dirtyMarker.run();
    }

    public boolean isStalkable(UUID targetId) {
        return repService.getScore(targetId) <= config.getEffectThresholds().stalkableAt;
    }

    public List<StalkSubscription> getSubscriptionsByStalker(UUID stalkerId) {
        long now = System.currentTimeMillis();
        List<StalkSubscription> result = new ArrayList<>();
        for (Map.Entry<UUID, Map<UUID, Long>> entry : subscriptionsByTarget.entrySet()) {
            Long expiresAt = entry.getValue().get(stalkerId);
            if (expiresAt != null && expiresAt > now) {
                result.add(new StalkSubscription(entry.getKey(), expiresAt));
            }
        }
        return result;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getWorld() == to.getWorld()
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Player target = event.getPlayer();
        UUID targetId = target.getUniqueId();
        RegionManager.ZoneType previousZone = lastKnownZones.getOrDefault(targetId, regionManager.resolveZone(from));
        RegionManager.ZoneType newZone = regionManager.resolveZone(to);

        if (previousZone == newZone) {
            return;
        }
        lastKnownZones.put(targetId, newZone);

        if (newZone == RegionManager.ZoneType.WARZONE && previousZone != RegionManager.ZoneType.WARZONE) {
            notifyStalkers(targetId, ChatColor.GOLD + "[Stalk] " + ChatColor.YELLOW + target.getName()
                    + ChatColor.GOLD + " entered Warzone at "
                    + ChatColor.YELLOW + to.getBlockX() + " " + to.getBlockY() + " " + to.getBlockZ());
            return;
        }

        if (previousZone == RegionManager.ZoneType.WARZONE && newZone != RegionManager.ZoneType.WARZONE) {
            notifyStalkers(targetId, ChatColor.GOLD + "[Stalk] " + ChatColor.YELLOW + target.getName()
                    + ChatColor.GOLD + " left Warzone.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastKnownZones.remove(event.getPlayer().getUniqueId());
    }

    private void notifyStalkers(UUID targetId, String message) {
        Map<UUID, Long> entries = subscriptionsByTarget.get(targetId);
        if (entries == null || entries.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : entries.entrySet()) {
            if (entry.getValue() <= now) {
                expired.add(entry.getKey());
                continue;
            }
            Player stalker = Bukkit.getPlayer(entry.getKey());
            if (stalker != null && stalker.isOnline()) {
                stalker.sendMessage(message);
            }
        }

        if (!expired.isEmpty()) {
            for (UUID stalkerId : expired) {
                entries.remove(stalkerId);
            }
            if (entries.isEmpty()) {
                subscriptionsByTarget.remove(targetId);
            }
            dirtyMarker.run();
        }
    }
}
