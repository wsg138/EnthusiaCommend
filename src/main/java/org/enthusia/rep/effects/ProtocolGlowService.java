package org.enthusia.rep.effects;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers.ChatFormatting;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Packet-based glowing that avoids scoreboard conflicts (e.g., TAB) by sending per-viewer teams.
 */
public class ProtocolGlowService {

    private final JavaPlugin plugin;
    private final ProtocolManager protocol;
    private volatile boolean teamPacketsEnabled = true;
    // viewer -> (target -> teamName)
    private final Map<UUID, Map<UUID, String>> teamsByViewer = new ConcurrentHashMap<>();

    public ProtocolGlowService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.protocol = ProtocolLibrary.getProtocolManager();
    }

    public void setGlow(Player target, ChatColor color, Collection<? extends Player> viewers) {
        if (target == null || viewers == null || color == null || !teamPacketsEnabled) return;
        String entry = target.getName();
        for (Player viewer : viewers) {
            if (viewer == null) continue;
            try {
                applyToViewer(viewer, target, entry, color);
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to apply glow via ProtocolLib to " + target.getName() + " for " + viewer.getName() + ": " + ex.getMessage());
            }
        }
    }

    public void clearGlow(Player target, Collection<? extends Player> viewers) {
        if (target == null || viewers == null) return;
        for (Player viewer : viewers) {
            if (viewer == null) continue;
            removeForViewer(viewer, target.getUniqueId());
        }
    }

    public void clearViewer(Player viewer) {
        if (viewer == null) return;
        Map<UUID, String> entries = teamsByViewer.remove(viewer.getUniqueId());
        if (entries == null) return;
        for (String team : entries.values()) {
            sendRemoveTeam(viewer, team);
        }
    }

    public void clearAll() {
        for (UUID viewerId : teamsByViewer.keySet()) {
            Player viewer = plugin.getServer().getPlayer(viewerId);
            if (viewer != null) {
                clearViewer(viewer);
            }
        }
        teamsByViewer.clear();
    }

    private void applyToViewer(Player viewer, Player target, String entry, ChatColor color) {
        Map<UUID, String> viewerTeams = teamsByViewer.computeIfAbsent(viewer.getUniqueId(), k -> new ConcurrentHashMap<>());
        UUID targetId = target.getUniqueId();
        String desiredTeam = buildTeamName(viewer.getUniqueId(), targetId, color);
        String previous = viewerTeams.get(targetId);
        if (previous != null && !previous.equals(desiredTeam)) {
            sendRemoveTeam(viewer, previous);
        }
        if (!sendCreateTeam(viewer, desiredTeam, color, entry)) {
            return;
        }
        viewerTeams.put(targetId, desiredTeam);
    }

    private void removeForViewer(Player viewer, UUID targetId) {
        Map<UUID, String> viewerTeams = teamsByViewer.get(viewer.getUniqueId());
        if (viewerTeams == null) return;
        String team = viewerTeams.remove(targetId);
        if (team != null) {
            sendRemoveTeam(viewer, team);
        }
    }

    private boolean sendCreateTeam(Player viewer, String teamName, ChatColor color, String entry) {
        if (!teamPacketsEnabled) {
            return false;
        }
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.SCOREBOARD_TEAM);

        var strings = packet.getStrings();
        if (strings.size() > 0) strings.writeSafely(0, teamName); // team name

        // Parameters object (1.21+): Component display, byte options, String nametagVis, String collision, ChatFormatting color, Component prefix, Component suffix
        try {
            Class<?> nmsColorClass = Class.forName("net.minecraft.ChatFormatting");
            Object nmsColor = Enum.valueOf((Class<Enum>) nmsColorClass, toFormatting(color).name());
            Object emptyComp = WrappedChatComponent.fromText("").getHandle();

            Class<?> compClass = emptyComp.getClass();
            Class<?> paramsClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket$Parameters");
            var paramsCtor = paramsClass.getDeclaredConstructor(
                    compClass, byte.class, String.class, String.class, nmsColorClass, compClass, compClass
            );
            Object params = paramsCtor.newInstance(
                    emptyComp,
                    (byte) 0,
                    "always",
                    "never",
                    nmsColor,
                    emptyComp,
                    emptyComp
            );

            // Optional<Parameters>
            var optionals = packet.getSpecificModifier(Optional.class);
            if (optionals.size() > 0) {
                optionals.writeSafely(0, Optional.of(params));
            }
        } catch (Exception ex) {
            disableTeamPackets("Failed to build team parameters", ex);
            return false;
        }

        var collections = packet.getSpecificModifier(Collection.class);
        if (collections.size() > 0) collections.writeSafely(0, Collections.singletonList(entry));

        try {
            protocol.sendServerPacket(viewer, packet);
            return true;
        } catch (Exception ex) {
            disableTeamPackets("Failed to send scoreboard team packet", ex);
            return false;
        }
    }

    private void sendRemoveTeam(Player viewer, String teamName) {
        try {
            PacketContainer packet = protocol.createPacket(PacketType.Play.Server.SCOREBOARD_TEAM);
            packet.getStrings().write(0, teamName);
            packet.getIntegers().write(0, 1); // mode: remove
            protocol.sendServerPacket(viewer, packet);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to remove glow team " + teamName + " for " + viewer.getName() + ": " + ex.getMessage());
        }
    }

    private String buildTeamName(UUID viewerId, UUID targetId, ChatColor color) {
        String v = viewerId.toString().replace("-", "");
        String t = targetId.toString().replace("-", "");
        String colorTag = color != null ? String.valueOf(color.getChar()) : "w";
        String name = "c" + colorTag + v.substring(0, 6) + t.substring(0, 6);
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }
        return name;
    }

    private ChatFormatting toFormatting(ChatColor color) {
        try {
            return ChatFormatting.valueOf(color.name());
        } catch (IllegalArgumentException ex) {
            return ChatFormatting.WHITE;
        }
    }

    private void disableTeamPackets(String context, Exception ex) {
        if (!teamPacketsEnabled) {
            return;
        }
        teamPacketsEnabled = false;
        plugin.getLogger().severe(context + "; disabling ProtocolLib team coloring fallback. Players will keep normal glow without red team packets. Cause: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        teamsByViewer.clear();
    }
}
