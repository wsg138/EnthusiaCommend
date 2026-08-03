package org.enthusia.rep.stalk;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.config.RepConfig;
import org.enthusia.rep.region.RegionManager;
import org.enthusia.rep.rep.RepService;
import org.enthusia.rep.storage.PluginDataSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StalkManager implements Listener {
    private final CommendPlugin plugin;
    private final RegionManager regionManager;
    private final RepService repService;
    private final Runnable dirtyMarker;
    private volatile RepConfig config;

    private final Map<UUID, Map<UUID, Long>> subscriptionsByTarget = new ConcurrentHashMap<>();
    private final Map<UUID, RegionManager.LogicalZone> lastKnownZones = new ConcurrentHashMap<>();

    public StalkManager(CommendPlugin plugin, RegionManager regionManager, RepService repService,
                        RepConfig config, Runnable dirtyMarker) {
        this.plugin = plugin;
        this.regionManager = regionManager;
        this.repService = repService;
        this.config = config;
        this.dirtyMarker = dirtyMarker;
    }

    public void load(PluginDataSnapshot snapshot) {
        subscriptionsByTarget.clear();
        long now = System.currentTimeMillis();
        for (PluginDataSnapshot.StalkEntry entry : snapshot.stalkEntries()) {
            if (entry.expiresAt() > now) {
                subscriptionsByTarget.computeIfAbsent(entry.targetId(), ignored -> new ConcurrentHashMap<>())
                        .put(entry.stalkerId(), entry.expiresAt());
            }
        }
        initializeOnlinePlayers();
    }

    public void reload(RepConfig config) {
        this.config = config;
        initializeOnlinePlayers();
    }

    private void initializeOnlinePlayers() {
        lastKnownZones.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            initialize(player);
        }
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
        subscriptionsByTarget.computeIfAbsent(targetId, ignored -> new ConcurrentHashMap<>())
                .put(stalkerId, System.currentTimeMillis() + durationMillis);
        dirtyMarker.run();
    }

    public void cancelSubscription(UUID stalkerId, UUID targetId) {
        Map<UUID, Long> entries = subscriptionsByTarget.get(targetId);
        if (entries == null) return;
        entries.remove(stalkerId);
        if (entries.isEmpty()) subscriptionsByTarget.remove(targetId);
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
            if (expiresAt != null && expiresAt > now) result.add(new StalkSubscription(entry.getKey(), expiresAt));
        }
        return result;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        repService.rememberName(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        initialize(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        StalkMovementRouting.Mode mode = StalkMovementRouting.forMove(
                event instanceof PlayerTeleportEvent,
                to == null || sameBlock(event.getFrom(), to));
        if (mode != StalkMovementRouting.Mode.IGNORE) {
            observe(event.getPlayer(), to, mode);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        boolean sameWorld = event.getFrom().getWorld() == to.getWorld();
        observe(event.getPlayer(), to, StalkMovementRouting.forTeleport(sameWorld));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        observe(event.getPlayer(), event.getPlayer().getLocation(), StalkMovementRouting.forWorldChange());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        lastKnownZones.remove(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        lastKnownZones.remove(playerId);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) initialize(player);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastKnownZones.remove(event.getPlayer().getUniqueId());
    }

    private void initialize(Player player) {
        observe(player, player.getLocation(), StalkMovementRouting.Mode.BASELINE);
    }

    private void observe(Player target, Location destination, StalkMovementRouting.Mode mode) {
        UUID targetId = target.getUniqueId();
        RegionManager.LogicalZone next = regionManager.resolveStalkingZone(destination);
        RegionManager.LogicalZone previous = lastKnownZones.get(targetId);
        StalkMovementRouting.Observation observation = StalkMovementRouting.observe(mode, previous, next);
        lastKnownZones.put(targetId, observation.rememberedZone());
        if (!observation.alert()) return;
        notifyStalkers(targetId, ChatColor.GOLD + "[Stalk] " + ChatColor.YELLOW + target.getName()
                + ChatColor.GOLD + " entered Warzone at " + ChatColor.YELLOW
                + destination.getBlockX() + " " + destination.getBlockY() + " " + destination.getBlockZ());
    }

    private boolean sameBlock(Location from, Location to) {
        return from.getWorld() == to.getWorld() && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ();
    }

    private void notifyStalkers(UUID targetId, String message) {
        Map<UUID, Long> entries = subscriptionsByTarget.get(targetId);
        if (entries == null || entries.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : entries.entrySet()) {
            if (entry.getValue() <= now) {
                expired.add(entry.getKey());
                continue;
            }
            Player stalker = Bukkit.getPlayer(entry.getKey());
            if (stalker != null && stalker.isOnline()) stalker.sendMessage(message);
        }
        if (!expired.isEmpty()) {
            expired.forEach(entries::remove);
            if (entries.isEmpty()) subscriptionsByTarget.remove(targetId);
            dirtyMarker.run();
        }
    }
}
