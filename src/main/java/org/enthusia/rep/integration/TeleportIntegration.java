package org.enthusia.rep.integration;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.rep.RepService;

import java.lang.reflect.Method;
import java.util.UUID;

public class TeleportIntegration {

    private final CommendPlugin plugin;
    private final RepService repService;

    private Object teleportApi;
    private Method warmupMethod;
    private Method cooldownMethod;
    private boolean warnedMissing = false;

    public TeleportIntegration(CommendPlugin plugin, RepService repService) {
        this.plugin = plugin;
        this.repService = repService;
    }

    public void tick() {
        if (teleportApi == null) {
            tryHook();
            if (teleportApi == null) {
                return;
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            int rep = repService.getScore(id);

            invokeTeleport(warmupMethod, id, computeWarmupModifier(rep));
            invokeTeleport(cooldownMethod, id, computeCooldownModifier(rep));
        }
    }

    public void refresh() {
        warnedMissing = false;
        teleportApi = null;
        warmupMethod = null;
        cooldownMethod = null;
        tryHook();
    }

    public void updatePlayer(UUID id) {
        if (teleportApi == null) {
            tryHook();
        }
        if (teleportApi == null) return;
        int rep = repService.getScore(id);
        invokeTeleport(warmupMethod, id, computeWarmupModifier(rep));
        invokeTeleport(cooldownMethod, id, computeCooldownModifier(rep));
    }

    private void tryHook() {
        Class<?> apiClass;
        try {
            apiClass = Class.forName("org.enthusia.teleport.api.TeleportApi");
        } catch (ClassNotFoundException e) {
            if (!warnedMissing) {
                plugin.getLogger().warning("EnthusiaTeleport API not on classpath; rep teleport modifiers disabled.");
                warnedMissing = true;
            }
            return;
        }

        RegisteredServiceProvider<?> provider =
                Bukkit.getServicesManager().getRegistration(apiClass);

        if (provider == null) {
            if (!warnedMissing) {
                plugin.getLogger().warning("EnthusiaTeleport not found; rep teleport modifiers disabled.");
                warnedMissing = true;
            }
            teleportApi = null;
            return;
        }

        try {
            Object api = provider.getProvider();
            Method warmup = apiClass.getMethod("setWarmupModifier", UUID.class, double.class);
            Method cooldown = apiClass.getMethod("setCooldownModifier", UUID.class, double.class);

            teleportApi = api;
            warmupMethod = warmup;
            cooldownMethod = cooldown;
            plugin.getLogger().info("Linked to EnthusiaTeleport for rep-based cooldowns/warmups.");
            warnedMissing = false;
        } catch (NoSuchMethodException e) {
            plugin.getLogger().warning("EnthusiaTeleport API methods missing; rep teleport modifiers disabled.");
            teleportApi = null;
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to hook EnthusiaTeleport: " + ex.getMessage());
            teleportApi = null;
        }
    }

    private void invokeTeleport(Method method, UUID playerId, double modifier) {
        if (teleportApi == null || method == null) return;
        try {
            method.invoke(teleportApi, playerId, modifier);
        } catch (Exception ex) {
            if (!warnedMissing) {
                plugin.getLogger().warning("Failed to call EnthusiaTeleport API: " + ex.getMessage());
                warnedMissing = true;
            }
            teleportApi = null;
        }
    }

    private double computeWarmupModifier(int rep) {
        if (rep >= 15) return 0.5;   // 5s -> 2.5s
        if (rep >= 5) return 0.8;    // 5s -> 4s
        if (rep <= -25) return 2.0;  // 5s -> 10s
        if (rep <= -15) return 1.6;  // 5s -> 8s
        if (rep <= -10) return 1.4;  // 5s -> 7s
        return 1.0;
    }

    private double computeCooldownModifier(int rep) {
        if (rep >= 20) return 0.5;          // 60s -> 30s
        if (rep >= 15) return 40.0 / 60.0;  // 60s -> 40s
        if (rep >= 10) return 45.0 / 60.0;  // 60s -> 45s
        if (rep >= 5) return 50.0 / 60.0;   // 60s -> 50s
        return 1.0;
    }
}
