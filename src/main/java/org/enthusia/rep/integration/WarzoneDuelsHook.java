package org.enthusia.rep.integration;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.enthusia.rep.CommendPlugin;

import java.lang.reflect.Method;
import java.util.UUID;

public final class WarzoneDuelsHook {
    private final CommendPlugin plugin;

    private Plugin duelPlugin;
    private Method duelServiceMethod;
    private Method isParticipantRestrictedMethod;
    private boolean lookupAttempted;
    private boolean warnedLookupFailure;

    public WarzoneDuelsHook(CommendPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isDuelExempt(Player player) {
        if (player == null) {
            return false;
        }
        Object duelService = duelService();
        if (duelService == null || isParticipantRestrictedMethod == null) {
            return false;
        }
        try {
            Object result = isParticipantRestrictedMethod.invoke(duelService, player.getUniqueId());
            return result instanceof Boolean value && value;
        } catch (ReflectiveOperationException ex) {
            warnLookupFailure("Failed to query WarzoneDuels duel state", ex);
            clearCachedLookup();
            return false;
        }
    }

    public void refresh() {
        clearCachedLookup();
        duelService();
    }

    private Object duelService() {
        if (!lookupAttempted) {
            resolveLookup();
        }
        if (duelPlugin == null || duelServiceMethod == null) {
            return null;
        }
        try {
            return duelServiceMethod.invoke(duelPlugin);
        } catch (ReflectiveOperationException ex) {
            warnLookupFailure("Failed to access WarzoneDuels duel service", ex);
            clearCachedLookup();
            return null;
        }
    }

    private void resolveLookup() {
        lookupAttempted = true;
        duelPlugin = Bukkit.getPluginManager().getPlugin("WarzoneDuels");
        if (duelPlugin == null || !duelPlugin.isEnabled()) {
            return;
        }
        try {
            duelServiceMethod = duelPlugin.getClass().getMethod("duelService");
            Class<?> duelServiceClass = duelServiceMethod.getReturnType();
            isParticipantRestrictedMethod = duelServiceClass.getMethod("isParticipantRestricted", UUID.class);
        } catch (ReflectiveOperationException ex) {
            warnLookupFailure("Failed to wire WarzoneDuels hook", ex);
            clearCachedLookup();
            lookupAttempted = true;
        }
    }

    private void clearCachedLookup() {
        duelPlugin = null;
        duelServiceMethod = null;
        isParticipantRestrictedMethod = null;
        lookupAttempted = false;
    }

    private void warnLookupFailure(String message, Exception ex) {
        if (warnedLookupFailure) {
            return;
        }
        warnedLookupFailure = true;
        plugin.getLogger().warning(message + ": " + ex.getMessage());
    }
}
