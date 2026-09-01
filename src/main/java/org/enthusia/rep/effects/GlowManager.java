package org.enthusia.rep.effects;

import fr.skytasul.glowingentities.GlowingEntities;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Objects;

/**
 * Wrapper around GlowingEntities to avoid scoreboard/team conflicts (e.g., TAB).
 */
public class GlowManager {

    private static final String SET_GLOWING_METHOD = "setGlowing";
    private static final String UNSET_GLOWING_METHOD = "unsetGlowing";
    private static final int TEAM_NAME_LIMIT = 16;
    private static final int TEAM_UUID_PREFIX_LENGTH = 12;

    private final GlowingEntities glowing;
    private final JavaPlugin plugin;

    public GlowManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.glowing = new GlowingEntities(plugin);
        logAvailableMethods();
    }

    public void setGlow(Player target, ChatColor color, Collection<? extends Player> viewers) {
        if (target == null || viewers == null) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> applyGlow(target, color, viewers));
    }

    private void applyGlow(Player target, ChatColor color, Collection<? extends Player> viewers) {
        ChatColor bukkitColor = color != null ? color : ChatColor.WHITE;
        net.md_5.bungee.api.ChatColor bungeeColor = net.md_5.bungee.api.ChatColor.valueOf(bukkitColor.name());
        Player[] viewerArray = viewers.stream()
                .filter(Objects::nonNull)
                .toArray(Player[]::new);
        if (viewerArray.length == 0) return;

        unsetGlow(target, viewerArray);
        if (!applyWithAvailableApi(target, bukkitColor, bungeeColor, viewerArray)) {
            logDebug("Glow path: none matched (still white?)");
            return;
        }
        try {
            target.setGlowing(true);
        } catch (Exception ignored) {
        }
    }

    private boolean applyWithAvailableApi(Player target, ChatColor bukkitColor,
                                          net.md_5.bungee.api.ChatColor bungeeColor,
                                          Player[] viewers) {
        return invokeBulk(target, bukkitColor, viewers, "bulk-bukkit")
                || invokeBulk(target, bungeeColor, viewers, "bulk-bungee")
                || invokeTeam(target, bukkitColor, viewers)
                || invokeLegacy(target, bukkitColor, viewers)
                || invokeLegacyBungee(target, bungeeColor, viewers);
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
            Method unsetAll = glowing.getClass().getMethod(UNSET_GLOWING_METHOD, org.bukkit.entity.Entity.class);
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
                    SET_GLOWING_METHOD,
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
                    SET_GLOWING_METHOD,
                    org.bukkit.entity.Entity.class,
                    Player.class,
                    net.md_5.bungee.api.ChatColor.class
            );
            boolean success = false;
            for (Player viewer : viewers) {
                try {
                    legacy.invoke(glowing, target, viewer, color);
                    success = true;
                } catch (ReflectiveOperationException ignored) {
                }
            }
            if (success) logDebug("Glow path: legacy-bungee");
            return success;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private boolean invokeTeam(Player target, ChatColor color, Player[] viewers) {
        try {
            Method teamMethod = glowing.getClass().getMethod(
                    SET_GLOWING_METHOD,
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
        }
    }

    private String buildTeamName(Player target, ChatColor color) {
        // Max scoreboard team length is 16
        String hex = target.getUniqueId().toString().replace("-", "");
        String colorTag = color != null ? Character.toString(color.getChar()) : "w";
        String suffix = hex.length() >= TEAM_UUID_PREFIX_LENGTH
                ? hex.substring(0, TEAM_UUID_PREFIX_LENGTH)
                : hex;
        String name = "cmg" + suffix + colorTag;
        if (name.length() > TEAM_NAME_LIMIT) {
            name = name.substring(0, TEAM_NAME_LIMIT);
        }
        return name;
    }

    private void logAvailableMethods() {
        for (Method m : glowing.getClass().getMethods()) {
            String name = m.getName();
            if (SET_GLOWING_METHOD.equals(name) || UNSET_GLOWING_METHOD.equals(name)) {
                plugin.getLogger().info("[GlowDebug] GlowingEntities method: " + m);
            }
        }
    }

    private void logDebug(String message) {
        plugin.getLogger().info("[GlowDebug] " + message);
    }
}
