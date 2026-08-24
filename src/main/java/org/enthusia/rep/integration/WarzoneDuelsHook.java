package org.enthusia.rep.integration;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.enthusia.rep.CommendPlugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

public final class WarzoneDuelsHook {
    private final CommendPlugin plugin;

    private Optional<DuelLookup> lookup = Optional.empty();
    private boolean lookupAttempted;
    private boolean warnedLookupFailure;

    public WarzoneDuelsHook(CommendPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isDuelExempt(Player player) {
        if (player == null) {
            return false;
        }
        DuelAccess access = duelAccess();
        if (access == null) {
            return false;
        }
        try {
            Object result = access.isParticipantRestrictedMethod().invoke(access.duelService(), player.getUniqueId());
            return result instanceof Boolean value && value;
        } catch (ReflectiveOperationException ex) {
            warnLookupFailure("Failed to query WarzoneDuels duel state", ex);
            clearCachedLookup();
            return false;
        }
    }

    public void refresh() {
        clearCachedLookup();
        resolveLookupIfNeeded();
    }

    private DuelAccess duelAccess() {
        resolveLookupIfNeeded();
        if (lookup.isEmpty()) {
            return null;
        }
        DuelLookup current = lookup.get();
        try {
            Object duelService = current.duelServiceMethod().invoke(current.duelPlugin());
            return new DuelAccess(duelService, current.isParticipantRestrictedMethod());
        } catch (ReflectiveOperationException ex) {
            warnLookupFailure("Failed to access WarzoneDuels duel service", ex);
            clearCachedLookup();
            return null;
        }
    }

    private void resolveLookupIfNeeded() {
        if (!lookupAttempted) {
            resolveLookup();
        }
    }

    private void resolveLookup() {
        lookupAttempted = true;
        Plugin duelPlugin = Bukkit.getPluginManager().getPlugin("WarzoneDuels");
        if (duelPlugin == null || !duelPlugin.isEnabled()) {
            return;
        }
        try {
            Method duelServiceMethod = duelPlugin.getClass().getMethod("duelService");
            Class<?> duelServiceClass = duelServiceMethod.getReturnType();
            Method isParticipantRestrictedMethod =
                    duelServiceClass.getMethod("isParticipantRestricted", UUID.class);
            lookup = Optional.of(new DuelLookup(
                    duelPlugin,
                    duelServiceMethod,
                    isParticipantRestrictedMethod));
        } catch (ReflectiveOperationException ex) {
            warnLookupFailure("Failed to wire WarzoneDuels hook", ex);
            clearCachedLookup();
            lookupAttempted = true;
        }
    }

    private void clearCachedLookup() {
        lookup = Optional.empty();
        lookupAttempted = false;
    }

    private void warnLookupFailure(String message, Exception ex) {
        if (warnedLookupFailure) {
            return;
        }
        warnedLookupFailure = true;
        plugin.getLogger().warning(message + ": " + ex.getMessage());
    }

    private record DuelLookup(
            Plugin duelPlugin,
            Method duelServiceMethod,
            Method isParticipantRestrictedMethod
    ) { }

    private record DuelAccess(Object duelService, Method isParticipantRestrictedMethod) { }
}
