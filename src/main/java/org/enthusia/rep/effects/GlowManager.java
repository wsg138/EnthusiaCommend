package org.enthusia.rep.effects;

import fr.skytasul.glowingentities.GlowingEntities;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Wrapper around GlowingEntities to avoid scoreboard/team conflicts (e.g., TAB).
 */
public class GlowManager {

    private final GlowingEntities glowing;
    private final JavaPlugin plugin;

    public GlowManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.glowing = new GlowingEntities(plugin);
        logAvailableMethods();
    }

    public void setGlow(Player target, ChatColor color, Collection<? extends Player> viewers) {
        if (target == null || viewers == null) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            // Prefer bungee ChatColor (most libs) but fall back to Bukkit ChatColor if only that API exists
            net.md_5.bungee.api.ChatColor bungeeColor = color != null
                    ? net.md_5.bungee.api.ChatColor.valueOf(color.name())
                    : net.md_5.bungee.api.ChatColor.WHITE;
            ChatColor bukkitColor = color != null ? color : ChatColor.WHITE;

            Player[] viewerArray = viewers.stream()
                    .filter(Objects::nonNull)
                    .toArray(Player[]::new);
            if (viewerArray.length == 0) return;

            // Clear existing glow per viewer before reapplying so color updates reliably
            unsetGlow(target, viewerArray);

            boolean applied = false;

            // Try bulk color overloads first (most reliable for setting team color)
            applied |= invokeBulk(target, bukkitColor, viewerArray, "bulk-bukkit");
            applied |= !applied && invokeBulk(target, bungeeColor, viewerArray, "bulk-bungee");

            // Team-based overload available in legacy library: setGlowing(int entityId, String team, Player viewer, ChatColor color)
            applied |= !applied && invokeTeam(target, bukkitColor, viewerArray);

            // Legacy per-viewer fallbacks
            applied |= !applied && invokeLegacy(target, bukkitColor, viewerArray);
            applied |= !applied && invokeLegacyBungee(target, bungeeColor, viewerArray);

            if (applied) {
                try {
                    target.setGlowing(true);
                } catch (Exception ignored) {
                }
            } else {
                logDebug("Glow path: none matched (still white?)");
            }
        });
    }

    public void clearGlow(Player target) {
        if (target == null) return;
        // Prefer a global unset if the library provides it
        if (unsetAll(target)) return;
        for (Player viewer : target.getWorld().getPlayers()) {
            try {
                glowing.unsetGlowing(target, viewer);
            } catch (ReflectiveOperationException ex) {
                plugin.getLogger().warning("Failed to clear glow for " + target.getName() + ": " + ex.getMessage());
            }
        }
        try {
            target.setGlowing(false);
        } catch (Exception ignored) {
        }
    }

    public void disable() {
        glowing.disable();
    }

    private void unsetGlow(Player target, Player[] viewers) {
        for (Player viewer : viewers) {
            try {
                glowing.unsetGlowing(target, viewer);
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    private boolean unsetAll(Player target) {
        try {
            Method unsetAll = glowing.getClass().getMethod("unsetGlowing", org.bukkit.entity.Entity.class);
            unsetAll.invoke(glowing, target);
            logDebug("Glow unset: global");
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Failed to clear glow for " + target.getName() + ": " + ex.getMessage());
            return false; // allow per-viewer fallback to run
        }
    }

    private boolean invokeBulk(Player target, Object color, Player[] viewers, String pathName) {
        try {
            Method m = glowing.getClass().getMethod(
                    "setGlowing",
                    org.bukkit.entity.Entity.class,
                    color instanceof ChatColor ? ChatColor.class : net.md_5.bungee.api.ChatColor.class,
                    Player[].class
            );
            m.invoke(glowing, target, color, (Object) viewers);
            logDebug("Glow path: " + pathName);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Failed to apply glow to " + target.getName() + ": " + ex.getMessage());
            return false; // keep trying other fallbacks
        }
    }

    private boolean invokeLegacy(Player target, ChatColor color, Player[] viewers) {
        boolean success = false;
        for (Player viewer : viewers) {
            try {
                glowing.setGlowing(target, viewer, color);
                success = true;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        if (success) logDebug("Glow path: legacy-bukkit");
        return success;
    }

    private boolean invokeLegacyBungee(Player target, net.md_5.bungee.api.ChatColor color, Player[] viewers) {
        try {
            Method legacy = glowing.getClass().getMethod(
                    "setGlowing",
                    org.bukkit.entity.Entity.class,
                    Player.class,
                    net.md_5.bungee.api.ChatColor.class
            );
            for (Player viewer : viewers) {
                try {
                    legacy.invoke(glowing, target, viewer, color);
                } catch (ReflectiveOperationException ignored) {
                }
            }
            logDebug("Glow path: legacy-bungee");
            return true;
        } catch (NoSuchMethodException ignored) {
            // nothing left to try
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Failed to apply glow to " + target.getName() + ": " + ex.getMessage());
        }
        return false;
    }

    private boolean invokeTeam(Player target, ChatColor color, Player[] viewers) {
        try {
            Method teamMethod = glowing.getClass().getMethod(
                    "setGlowing",
                    int.class,
                    String.class,
                    Player.class,
                    ChatColor.class
            );
            String team = buildTeamName(target, color);
            boolean success = false;
            for (Player viewer : viewers) {
                try {
                    teamMethod.invoke(glowing, target.getEntityId(), team, viewer, color);
                    success = true;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            if (success) logDebug("Glow path: team-bukkit (" + team + ")");
            return success;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Failed to apply team-based glow to " + target.getName() + ": " + ex.getMessage());
            return false;
        }
    }

    private String buildTeamName(Player target, ChatColor color) {
        // Max scoreboard team length is 16
        String hex = target.getUniqueId().toString().replace("-", "");
        String colorTag = color != null ? String.valueOf(color.getChar()) : "w";
        String suffix = hex.length() >= 12 ? hex.substring(0, 12) : hex;
        String name = "cmg" + suffix + colorTag;
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }
        return name;
    }

    private void logAvailableMethods() {
        for (Method m : glowing.getClass().getMethods()) {
            String name = m.getName();
            if ("setGlowing".equals(name) || "unsetGlowing".equals(name)) {
                plugin.getLogger().info("[GlowDebug] GlowingEntities method: " + m);
            }
        }
    }

    private void logDebug(String message) {
        plugin.getLogger().info("[GlowDebug] " + message);
    }
}
