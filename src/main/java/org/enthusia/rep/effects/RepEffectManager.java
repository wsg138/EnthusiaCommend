package org.enthusia.rep.effects;

import org.bukkit.Bukkit;
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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.ChatColor;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.config.RepConfig;
import org.enthusia.rep.config.RepConfig.RepTierConfig;
import org.enthusia.rep.region.RegionManager;
import org.enthusia.rep.rep.RepService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RepEffectManager implements Listener {

    private static final String SPEED_MODIFIER_NAME = "enthusia-rep-speed";

    private final CommendPlugin plugin;
    private RepConfig config;
    private final RegionManager regions;
    private final RepService repService;

    private final Map<UUID, Long> lastPearlMessage = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastWindMessage  = new ConcurrentHashMap<>();

    private final Map<UUID, Double> baseMoveSpeed = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRocketUse = new ConcurrentHashMap<>();

    private final Map<UUID, RepAppliedEffects> currentEffects = new ConcurrentHashMap<>();

    private final Map<UUID, Boolean> lastGlowState = new ConcurrentHashMap<>();

    public RepEffectManager(CommendPlugin plugin,
                            RepConfig config,
                            RegionManager regions,
                            RepService repService) {
        this.plugin = plugin;
        this.config = config;
        this.regions = regions;
        this.repService = repService;

        PluginManager pm = plugin.getServer().getPluginManager();
        pm.registerEvents(this, plugin);
    }

    public void reload(RepConfig newConfig) {
        this.config = newConfig;
    }

    public void clearAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            clearPlayer(player.getUniqueId());
        }
        currentEffects.clear();
    }

    public void clearPlayer(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;

        resetMovementBase(uuid);
        removeSpeedModifier(player);
        setGlowState(player, false);
        lastGlowState.remove(uuid);
    }

    public void resetMovementBase(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;
        AttributeInstance attr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr == null) return;

        double def = 0.1; // vanilla default
        baseMoveSpeed.put(uuid, def);
        attr.setBaseValue(def);
        removeSpeedModifier(player);
    }

    public void tickEffects() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            int score = repService.getScore(id);
            RepAppliedEffects effects = computeEffects(score);

            // hard fallback: if they glow and are VERY low, but no color from config, force RED
            if (effects.glow && effects.glowColor == null && score <= -20) {
                effects.glowColor = ChatColor.RED;
            }

            currentEffects.put(id, effects);

            boolean inCombatArea = regions.isInSpawnOrWarzone(player.getLocation());
            applyMovementAndGlow(player, effects, inCombatArea);
        }
    }

    public RepAppliedEffects getCurrentEffects(UUID uuid) {
        return currentEffects.getOrDefault(uuid, new RepAppliedEffects());
    }

    public RepAppliedEffects computeEffects(int score) {
        RepAppliedEffects out = new RepAppliedEffects();

        if (score < 0) {
            for (Map.Entry<Integer, RepTierConfig> e : config.getNegativeTiers().entrySet()) {
                int threshold = e.getKey();
                if (score <= threshold) {
                    applyTier(out, e.getValue());
                }
            }
        } else if (score > 0) {
            for (Map.Entry<Integer, RepTierConfig> e : config.getPositiveTiers().entrySet()) {
                int threshold = e.getKey();
                if (score >= threshold) {
                    applyTier(out, e.getValue());
                }
            }
        }

        return out;
    }

    private void applyTier(RepAppliedEffects out, RepTierConfig tier) {
        if (tier.movementSpeedPercent != null) {
            out.movementSpeedPercent = tier.movementSpeedPercent;
        }
        if (tier.potionDurationPercent != null) {
            out.potionDurationPercent = tier.potionDurationPercent;
        }
        if (tier.fireworkDurationPercent != null) {
            out.fireworkDurationPercent = tier.fireworkDurationPercent;
        }
        if (tier.pearlCooldownSeconds != null) {
            out.pearlCooldownSeconds = tier.pearlCooldownSeconds;
        }
        if (tier.windCooldownSeconds != null) {
            out.windCooldownSeconds = tier.windCooldownSeconds;
        }
        if (tier.glow != null) {
            out.glow = tier.glow;
        }
        if (tier.glowColor != null) {
            out.glowColor = tier.glowColor;
        }
        if (tier.stalkable != null) {
            out.stalkable = tier.stalkable;
        }
        if (tier.cashbackPercent != null) {
            out.cashbackPercent = tier.cashbackPercent;
        }
    }

    /* ================= Movement / Glow ================= */

    private void applyMovementAndGlow(Player player, RepAppliedEffects effects, boolean inCombatArea) {
        applyMovement(player, effects, inCombatArea);
        applyGlow(player, inCombatArea);
    }

    private void applyMovement(Player player, RepAppliedEffects effects, boolean inCombatArea) {
        AttributeInstance attr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr == null) return;

        UUID id = player.getUniqueId();

        baseMoveSpeed.computeIfAbsent(id, key -> {
            double v = attr.getBaseValue();
            if (v < 0.05) v = 0.1;
            if (v > 1.0) v = 0.1;
            return v;
        });
        double original = baseMoveSpeed.get(id);

        if (inCombatArea && effects.movementSpeedPercent != 0) {
            double factor = 1.0 + (effects.movementSpeedPercent / 100.0);
            if (factor < 0.05) factor = 0.05;
            attr.setBaseValue(original * factor);
        } else {
            attr.setBaseValue(original);
        }

        removeSpeedModifier(player);
    }

    private void removeSpeedModifier(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr == null) return;
        for (AttributeModifier mod : new ArrayList<>(attr.getModifiers())) {
            if (SPEED_MODIFIER_NAME.equals(mod.getName())) {
                attr.removeModifier(mod);
            }
        }
    }

    private void applyGlow(Player player, boolean inCombatArea) {
        int score = repService.getScore(player.getUniqueId());
        boolean shouldGlow = inCombatArea && score <= -10;

        Boolean prev = lastGlowState.get(player.getUniqueId());
        if (Objects.equals(prev, shouldGlow)) return;

        setGlowState(player, shouldGlow);
        if (shouldGlow) {
            lastGlowState.put(player.getUniqueId(), true);
            plugin.getLogger().info("Glow enabled for " + player.getName() + " (score " + score + ")");
        } else {
            lastGlowState.remove(player.getUniqueId());
        }
    }

    private void setGlowState(Player player, boolean glowing) {
        try {
            player.setGlowing(glowing);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to set glow state for " + player.getName() + ": " + ex.getMessage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastGlowState.remove(event.getPlayer().getUniqueId());
    }

    /* ================= Cooldowns / Fireworks / Potions ================= */

    private UUID uuid(Player p) {
        return p.getUniqueId();
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!event.hasItem()) return;
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null) return;

        Material type = item.getType();
        RepAppliedEffects effects = currentEffects.getOrDefault(uuid(player), new RepAppliedEffects());

        if (type == Material.ENDER_PEARL && regions.isInSpawnOrWarzone(player.getLocation())) {
            int repCooldownSeconds = effects.pearlCooldownSeconds;

            if (repCooldownSeconds > 0) {
                int currentTicks = player.getCooldown(Material.ENDER_PEARL);

                if (currentTicks > 0) {
                    long now = System.currentTimeMillis();
                    long lastMsg = lastPearlMessage.getOrDefault(uuid(player), 0L);
                    if (now - lastMsg > 750L) {
                        int sec = (int) Math.ceil(currentTicks / 20.0);
                        player.sendMessage(ChatColor.RED + "You can't throw another ender pearl for " + sec + "s (rep penalty).");
                        lastPearlMessage.put(uuid(player), now);
                    }
                    event.setCancelled(true);
                    return;
                }

                Bukkit.getScheduler().runTask(plugin, () ->
                        player.setCooldown(Material.ENDER_PEARL, repCooldownSeconds * 20)
                );
            }
        }

        if (type == Material.WIND_CHARGE && regions.isInSpawnOrWarzone(player.getLocation())) {
            int repCooldownSeconds = effects.windCooldownSeconds;

            if (repCooldownSeconds > 0) {
                int currentTicks = player.getCooldown(Material.WIND_CHARGE);

                if (currentTicks > 0) {
                    long now = System.currentTimeMillis();
                    long lastMsg = lastWindMessage.getOrDefault(uuid(player), 0L);
                    if (now - lastMsg > 750L) {
                        int sec = (int) Math.ceil(currentTicks / 20.0);
                        player.sendMessage(ChatColor.RED + "You can't use another wind charge for " + sec + "s (rep penalty).");
                        lastWindMessage.put(uuid(player), now);
                    }
                    event.setCancelled(true);
                    return;
                }

                Bukkit.getScheduler().runTask(plugin, () ->
                        player.setCooldown(Material.WIND_CHARGE, repCooldownSeconds * 20)
                );
            }
        }

        if (type == Material.FIREWORK_ROCKET && player.isGliding()) {
            lastRocketUse.put(uuid(player), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof Firework firework)) return;

        long now = System.currentTimeMillis();

        Player best = null;
        double bestDist2 = 0.0;
        int fireworkPercent = 0;

        for (Player p : firework.getWorld().getPlayers()) {
            UUID id = p.getUniqueId();
            Long last = lastRocketUse.get(id);
            if (last == null) continue;
            if (now - last > 500L) continue;
            if (!p.isGliding()) continue;

            double dist2 = p.getLocation().distanceSquared(firework.getLocation());
            RepAppliedEffects eff = currentEffects.getOrDefault(id, new RepAppliedEffects());
            if (eff.fireworkDurationPercent >= 0) continue;

            if (best == null || dist2 < bestDist2) {
                best = p;
                bestDist2 = dist2;
                fireworkPercent = eff.fireworkDurationPercent;
            }
        }

        if (best == null || fireworkPercent >= 0) return;

        double factor = 1.0 + (fireworkPercent / 100.0);
        if (factor <= 0.1) factor = 0.1;

        FireworkMeta meta = firework.getFireworkMeta();
        int power = meta.getPower();
        int baseTicks = 20 * (power + 1);
        long shortenedTicks = Math.max(1, Math.round(baseTicks * factor));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!firework.isDead()) {
                firework.detonate();
            }
        }, shortenedTicks);
    }

    @EventHandler
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!regions.isInSpawnOrWarzone(player.getLocation())) return;

        ItemStack item = event.getItem();
        if (item == null) return;
        Material type = item.getType();

        if (type != Material.POTION && type != Material.SPLASH_POTION && type != Material.LINGERING_POTION) {
            return;
        }

        RepAppliedEffects effects = currentEffects.getOrDefault(uuid(player), new RepAppliedEffects());
        if (effects.potionDurationPercent == 0) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            applyPotionDurationModifier(player, effects);
        }, 5L);
    }

    @EventHandler
    public void onPotionSplash(PotionSplashEvent event) {
        for (Entity entity : event.getAffectedEntities()) {
            if (!(entity instanceof Player player)) continue;

            if (!regions.isInSpawnOrWarzone(player.getLocation())) continue;

            RepAppliedEffects effects = currentEffects.getOrDefault(uuid(player), new RepAppliedEffects());
            if (effects.potionDurationPercent == 0) continue;

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                applyPotionDurationModifier(player, effects);
            }, 5L);
        }
    }

    private void applyPotionDurationModifier(Player player, RepAppliedEffects effects) {
        if (effects.potionDurationPercent == 0) return;

        List<PotionEffect> snapshot = new ArrayList<>(player.getActivePotionEffects());
        for (PotionEffect eff : snapshot) {
            if (!isBeneficial(eff.getType())) continue;

            int original = eff.getDuration();
            double factor = 1.0 + (effects.potionDurationPercent / 100.0);
            int newDuration = (int) Math.max(1, original * factor);

            PotionEffectType type = eff.getType();
            player.removePotionEffect(type);

            PotionEffect replaced = new PotionEffect(
                    type,
                    newDuration,
                    eff.getAmplifier(),
                    eff.isAmbient(),
                    eff.hasParticles(),
                    eff.hasIcon()
            );
            player.addPotionEffect(replaced, true);
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
}
