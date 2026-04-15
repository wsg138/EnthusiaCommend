package org.enthusia.rep.effects;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.config.RepConfig;
import org.enthusia.rep.region.RegionManager;
import org.enthusia.rep.rep.RepService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class RepEffectManager implements Listener {

    private static final UUID MOVEMENT_MODIFIER_ID = UUID.fromString("5ae272fd-5fd0-48a7-b94d-38fdb332e313");
    private static final String MOVEMENT_MODIFIER_NAME = "enthusia-rep-movement";

    private final CommendPlugin plugin;
    private final RegionManager regionManager;
    private final RepService repService;
    private final ProtocolGlowService protocolGlowService;

    private RepConfig config;

    private final Map<UUID, RepAppliedEffects> currentEffects = new HashMap<>();
    private final Map<UUID, Integer> appliedMovementPercents = new HashMap<>();
    private final Map<UUID, GlowState> glowStates = new HashMap<>();
    private final Map<UUID, Long> lastPearlMessageAt = new HashMap<>();
    private final Map<UUID, Long> lastWindMessageAt = new HashMap<>();
    private final Map<UUID, Long> lastRocketUseAt = new HashMap<>();

    public RepEffectManager(CommendPlugin plugin, RepConfig config, RegionManager regionManager, RepService repService) {
        this.plugin = plugin;
        this.config = config;
        this.regionManager = regionManager;
        this.repService = repService;
        this.protocolGlowService = isProtocolAvailable() ? new ProtocolGlowService(plugin) : null;
    }

    public void register(PluginManager pluginManager) {
        pluginManager.registerEvents(this, plugin);
    }

    public void reload(RepConfig config) {
        this.config = config;
        refreshAll();
    }

    public void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyEffects(player, true);
        }
    }

    public void tickEffects() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyEffects(player, false);
        }
    }

    public RepAppliedEffects getCurrentEffects(UUID playerId) {
        return currentEffects.getOrDefault(playerId, config.resolveEffects(repService.getScore(playerId)));
    }

    public void clearAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            clearPlayer(player);
        }
        currentEffects.clear();
        appliedMovementPercents.clear();
        glowStates.clear();
        lastPearlMessageAt.clear();
        lastWindMessageAt.clear();
        lastRocketUseAt.clear();
        if (protocolGlowService != null) {
            protocolGlowService.clearAll();
        }
    }

    public void handleScoreChanged(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            applyEffects(player, true);
        }
    }

    private void applyEffects(Player player, boolean force) {
        UUID playerId = player.getUniqueId();
        RepAppliedEffects desired = config.resolveEffects(repService.getScore(playerId));
        currentEffects.put(playerId, desired);

        boolean inEffectZone = regionManager.isInSpawnOrWarzone(player.getLocation());
        applyMovement(player, inEffectZone ? desired.movementSpeedPercent() : 0, force);
        applyGlow(player, inEffectZone && desired.glow(), desired.glowColor(), force);
    }

    private void applyMovement(Player player, int desiredPercent, boolean force) {
        UUID playerId = player.getUniqueId();
        Integer currentPercent = appliedMovementPercents.get(playerId);
        if (!force && Objects.equals(currentPercent, desiredPercent)) {
            return;
        }

        AttributeInstance attribute = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attribute == null) {
            return;
        }

        removeMovementModifier(attribute);
        if (desiredPercent != 0) {
            AttributeModifier modifier = new AttributeModifier(
                    MOVEMENT_MODIFIER_ID,
                    MOVEMENT_MODIFIER_NAME,
                    desiredPercent / 100.0D,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1
            );
            attribute.addModifier(modifier);
            appliedMovementPercents.put(playerId, desiredPercent);
        } else {
            appliedMovementPercents.remove(playerId);
        }
    }

    private void removeMovementModifier(AttributeInstance attribute) {
        for (AttributeModifier modifier : attribute.getModifiers()) {
            if (MOVEMENT_MODIFIER_ID.equals(modifier.getUniqueId()) || MOVEMENT_MODIFIER_NAME.equals(modifier.getName())) {
                attribute.removeModifier(modifier);
            }
        }
    }

    private void applyGlow(Player target, boolean shouldGlow, ChatColor glowColor, boolean force) {
        GlowState desired = shouldGlow ? new GlowState(true, glowColor) : GlowState.OFF;
        GlowState current = glowStates.getOrDefault(target.getUniqueId(), GlowState.OFF);
        if (!force && current.equals(desired)) {
            return;
        }

        if (!shouldGlow) {
            clearGlow(target);
            glowStates.remove(target.getUniqueId());
            return;
        }

        if (glowColor == ChatColor.RED && protocolGlowService != null) {
            protocolGlowService.setGlow(target, ChatColor.RED, target.getWorld().getPlayers());
            target.setGlowing(true);
        } else {
            if (protocolGlowService != null) {
                protocolGlowService.clearGlow(target, Bukkit.getOnlinePlayers());
            }
            target.setGlowing(true);
        }
        glowStates.put(target.getUniqueId(), desired);
    }

    private void clearGlow(Player player) {
        if (protocolGlowService != null) {
            protocolGlowService.clearGlow(player, Bukkit.getOnlinePlayers());
        }
        player.setGlowing(false);
    }

    private boolean isProtocolAvailable() {
        return Bukkit.getPluginManager().getPlugin("ProtocolLib") != null;
    }

    private void clearPlayer(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attribute != null) {
            removeMovementModifier(attribute);
        }
        clearGlow(player);
        appliedMovementPercents.remove(player.getUniqueId());
        glowStates.remove(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        clearPlayer(player);
        lastPearlMessageAt.remove(player.getUniqueId());
        lastWindMessageAt.remove(player.getUniqueId());
        lastRocketUseAt.remove(player.getUniqueId());
        currentEffects.remove(player.getUniqueId());
        if (protocolGlowService != null) {
            protocolGlowService.clearViewer(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            applyEffects(event.getPlayer(), true);
            refreshRedGlowForViewer(event.getPlayer());
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> applyEffects(event.getPlayer(), true));
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        if (protocolGlowService != null) {
            protocolGlowService.clearViewer(event.getPlayer());
        }
        applyEffects(event.getPlayer(), true);
        refreshRedGlowForViewer(event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (protocolGlowService != null) {
                    protocolGlowService.clearViewer(event.getPlayer());
                }
                applyEffects(event.getPlayer(), true);
                refreshRedGlowForViewer(event.getPlayer());
            });
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        if (event.getFrom().getWorld() == event.getTo().getWorld()
                && event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        boolean wasInZone = regionManager.isInSpawnOrWarzone(event.getFrom());
        boolean isInZone = regionManager.isInSpawnOrWarzone(event.getTo());
        if (wasInZone != isInZone) {
            applyEffects(event.getPlayer(), true);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!event.hasItem()) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }

        RepAppliedEffects effects = getCurrentEffects(player.getUniqueId());
        if (regionManager.isInSpawnOrWarzone(player.getLocation())) {
            if (item.getType() == Material.ENDER_PEARL) {
                handleCooldownItem(event, player, Material.ENDER_PEARL, effects.pearlCooldownSeconds(), lastPearlMessageAt, "You can't throw another ender pearl for ");
            } else if (item.getType() == Material.WIND_CHARGE) {
                handleCooldownItem(event, player, Material.WIND_CHARGE, effects.windCooldownSeconds(), lastWindMessageAt, "You can't use another wind charge for ");
            }
        }

        if (item.getType() == Material.FIREWORK_ROCKET && player.isGliding()) {
            lastRocketUseAt.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    private void handleCooldownItem(
            PlayerInteractEvent event,
            Player player,
            Material material,
            int cooldownSeconds,
            Map<UUID, Long> messageCache,
            String messagePrefix
    ) {
        if (cooldownSeconds <= 0) {
            return;
        }

        int currentCooldownTicks = player.getCooldown(material);
        if (currentCooldownTicks > 0) {
            long now = System.currentTimeMillis();
            long lastMessageAt = messageCache.getOrDefault(player.getUniqueId(), 0L);
            if (now - lastMessageAt > 750L) {
                int remainingSeconds = (int) Math.ceil(currentCooldownTicks / 20.0D);
                player.sendMessage(ChatColor.RED + messagePrefix + remainingSeconds + "s (rep penalty).");
                messageCache.put(player.getUniqueId(), now);
            }
            event.setCancelled(true);
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> player.setCooldown(material, cooldownSeconds * 20));
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof Firework firework)) {
            return;
        }

        long now = System.currentTimeMillis();
        Player closestPlayer = null;
        double closestDistanceSquared = 0.0D;
        int durationPercent = 0;

        for (Player player : firework.getWorld().getPlayers()) {
            Long lastUse = lastRocketUseAt.get(player.getUniqueId());
            if (lastUse == null || now - lastUse > 500L || !player.isGliding()) {
                continue;
            }

            RepAppliedEffects effects = getCurrentEffects(player.getUniqueId());
            if (effects.fireworkDurationPercent() >= 0) {
                continue;
            }

            double distanceSquared = player.getLocation().distanceSquared(firework.getLocation());
            if (closestPlayer == null || distanceSquared < closestDistanceSquared) {
                closestPlayer = player;
                closestDistanceSquared = distanceSquared;
                durationPercent = effects.fireworkDurationPercent();
            }
        }

        if (closestPlayer == null) {
            return;
        }

        FireworkMeta meta = firework.getFireworkMeta();
        int baseTicks = 20 * (meta.getPower() + 1);
        double factor = Math.max(0.1D, 1.0D + (durationPercent / 100.0D));
        long lifetimeTicks = Math.max(1L, Math.round(baseTicks * factor));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!firework.isDead()) {
                firework.detonate();
            }
        }, lifetimeTicks);
    }

    @EventHandler
    public void onPotionConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!regionManager.isInSpawnOrWarzone(player.getLocation())) {
            return;
        }
        Material material = event.getItem().getType();
        if (material != Material.POTION && material != Material.SPLASH_POTION && material != Material.LINGERING_POTION) {
            return;
        }
        RepAppliedEffects effects = getCurrentEffects(player.getUniqueId());
        if (effects.potionDurationPercent() == 0) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> applyPotionDurationModifier(player, effects), 5L);
    }

    @EventHandler
    public void onPotionSplash(PotionSplashEvent event) {
        for (Entity entity : event.getAffectedEntities()) {
            if (entity instanceof Player player && regionManager.isInSpawnOrWarzone(player.getLocation())) {
                RepAppliedEffects effects = getCurrentEffects(player.getUniqueId());
                if (effects.potionDurationPercent() != 0) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> applyPotionDurationModifier(player, effects), 5L);
                }
            }
        }
    }

    private void applyPotionDurationModifier(Player player, RepAppliedEffects effects) {
        List<PotionEffect> activeEffects = new ArrayList<>(player.getActivePotionEffects());
        for (PotionEffect effect : activeEffects) {
            if (!isBeneficial(effect.getType())) {
                continue;
            }
            int adjustedDuration = (int) Math.max(1, Math.round(effect.getDuration() * (1.0D + effects.potionDurationPercent() / 100.0D)));
            player.removePotionEffect(effect.getType());
            player.addPotionEffect(new PotionEffect(
                    effect.getType(),
                    adjustedDuration,
                    effect.getAmplifier(),
                    effect.isAmbient(),
                    effect.hasParticles(),
                    effect.hasIcon()
            ), true);
        }
    }

    private boolean isBeneficial(PotionEffectType type) {
        return type == PotionEffectType.SPEED
                || type == PotionEffectType.JUMP_BOOST
                || type == PotionEffectType.HASTE
                || type == PotionEffectType.STRENGTH
                || type == PotionEffectType.RESISTANCE
                || type == PotionEffectType.REGENERATION
                || type == PotionEffectType.FIRE_RESISTANCE
                || type == PotionEffectType.HEALTH_BOOST
                || type == PotionEffectType.ABSORPTION
                || type == PotionEffectType.NIGHT_VISION
                || type == PotionEffectType.SATURATION
                || type == PotionEffectType.LUCK
                || type == PotionEffectType.CONDUIT_POWER
                || type == PotionEffectType.DOLPHINS_GRACE
                || type == PotionEffectType.HERO_OF_THE_VILLAGE;
    }

    private void refreshRedGlowForViewer(Player viewer) {
        if (protocolGlowService == null) {
            return;
        }
        for (Player target : viewer.getWorld().getPlayers()) {
            if (target.equals(viewer)) {
                continue;
            }
            GlowState state = glowStates.get(target.getUniqueId());
            if (state != null && state.enabled() && state.color() == ChatColor.RED) {
                protocolGlowService.setGlow(target, ChatColor.RED, List.of(viewer));
            }
        }
    }

    private record GlowState(boolean enabled, ChatColor color) {
        private static final GlowState OFF = new GlowState(false, null);
    }
}
