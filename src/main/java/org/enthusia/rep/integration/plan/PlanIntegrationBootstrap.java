package org.enthusia.rep.integration.plan;

import org.bukkit.Bukkit;
import org.enthusia.rep.CommendPlugin;

public final class PlanIntegrationBootstrap {

    private final CommendPlugin plugin;
    private boolean registered;
    private boolean warned;
    private PlanHook hook;

    public PlanIntegrationBootstrap(CommendPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        if (!plugin.getRepConfig().isPlanIntegrationEnabled()) {
            shutdown();
            return;
        }
        if (registered) {
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("Plan") == null) {
            return;
        }
        try {
            hook = new PlanHook(plugin);
            hook.register();
            registered = true;
            plugin.getLogger().info("Plan reputation analytics integration registered.");
        } catch (NoClassDefFoundError | IllegalStateException ex) {
            if (!warned) {
                plugin.getLogger().warning("Plan integration is enabled but could not be registered: " + ex.getMessage());
                warned = true;
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Plan integration failed to register: " + ex.getMessage());
        }
    }

    public void shutdown() {
        if (hook != null) {
            hook.shutdown();
        }
        registered = false;
        hook = null;
    }
}
