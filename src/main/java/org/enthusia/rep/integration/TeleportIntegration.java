package org.enthusia.rep.integration;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.rep.RepService;

import java.lang.reflect.Method;
import java.util.UUID;

public final class TeleportIntegration implements Listener {

    private final CommendPlugin plugin;
    private final RepService repService;

    private Object api;
    private Method setWarmupModifierMethod;
    private Method setCooldownModifierMethod;
    private boolean warnedMissing;

    public TeleportIntegration(CommendPlugin plugin, RepService repService) {
        this.plugin = plugin;
        this.repService = repService;
    }

    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void refresh() {
        clearAllWithCurrentHook();
        api = null;
        setWarmupModifierMethod = null;
        setCooldownModifierMethod = null;
        warnedMissing = false;
        tryHook();
        applyToAllOnlinePlayers();
    }

    public void updatePlayer(UUID playerId) {
        if (!ensureHooked()) {
            return;
        }
        int score = repService.getScore(playerId);
        invoke(setWarmupModifierMethod, playerId, computeWarmupModifier(score));
        invoke(setCooldownModifierMethod, playerId, computeCooldownModifier(score));
    }

    public void clearPlayer(UUID playerId) {
        if (!ensureHooked()) {
            return;
        }
        invoke(setWarmupModifierMethod, playerId, 1.0D);
        invoke(setCooldownModifierMethod, playerId, 1.0D);
    }

    public void shutdown() {
        clearAllWithCurrentHook();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        updatePlayer(event.getPlayer().getUniqueId());
    }

    private void applyToAllOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayer(player.getUniqueId());
        }
    }

    private void clearAllWithCurrentHook() {
        if (api == null || setWarmupModifierMethod == null || setCooldownModifierMethod == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            invoke(setWarmupModifierMethod, player.getUniqueId(), 1.0D);
            invoke(setCooldownModifierMethod, player.getUniqueId(), 1.0D);
        }
    }

    private boolean ensureHooked() {
        if (api != null) {
            return true;
        }
        tryHook();
        return api != null;
    }

    private void tryHook() {
        Class<?> apiClass;
        try {
            apiClass = Class.forName("org.enthusia.teleport.api.TeleportApi");
        } catch (ClassNotFoundException ignored) {
            warnOnce("EnthusiaTeleport API not found; teleport reputation modifiers disabled.");
            return;
        }

        RegisteredServiceProvider<?> provider = Bukkit.getServicesManager().getRegistration(apiClass);
        if (provider == null) {
            warnOnce("EnthusiaTeleport service is not registered; teleport reputation modifiers disabled.");
            return;
        }

        try {
            Object resolvedApi = provider.getProvider();
            Method warmup = apiClass.getMethod("setWarmupModifier", UUID.class, double.class);
            Method cooldown = apiClass.getMethod("setCooldownModifier", UUID.class, double.class);
            this.api = resolvedApi;
            this.setWarmupModifierMethod = warmup;
            this.setCooldownModifierMethod = cooldown;
            this.warnedMissing = false;
            plugin.getLogger().info("Linked EnthusiaCommend to EnthusiaTeleport.");
        } catch (Exception ex) {
            warnOnce("Failed to hook EnthusiaTeleport: " + ex.getMessage());
            this.api = null;
        }
    }

    private void invoke(Method method, UUID playerId, double value) {
        if (api == null || method == null) {
            return;
        }
        try {
            method.invoke(api, playerId, value);
        } catch (Exception ex) {
            warnOnce("Teleport modifier call failed: " + ex.getMessage());
            api = null;
            setWarmupModifierMethod = null;
            setCooldownModifierMethod = null;
        }
    }

    private void warnOnce(String message) {
        if (!warnedMissing) {
            warnedMissing = true;
            plugin.getLogger().warning(message);
        }
    }

    private double computeWarmupModifier(int score) {
        if (score >= 15) return 0.5D;
        if (score >= 5) return 0.8D;
        if (score <= -25) return 2.0D;
        if (score <= -15) return 1.6D;
        if (score <= -10) return 1.4D;
        return 1.0D;
    }

    private double computeCooldownModifier(int score) {
        if (score >= 20) return 0.5D;
        if (score >= 15) return 40.0D / 60.0D;
        if (score >= 10) return 45.0D / 60.0D;
        if (score >= 5) return 50.0D / 60.0D;
        return 1.0D;
    }
}
