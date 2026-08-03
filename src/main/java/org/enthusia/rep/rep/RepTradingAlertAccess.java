package org.enthusia.rep.rep;

import org.bukkit.permissions.Permissible;

/** Shared permission policy for the administrative rep-trading alert feature. */
public final class RepTradingAlertAccess {
    public static final String PERMISSION = "enthusiacommend.rep.alert";

    private RepTradingAlertAccess() { }

    public static boolean isAuthorized(Permissible permissible) {
        return permissible != null && permissible.hasPermission(PERMISSION);
    }

    public static boolean shouldDeliver(boolean authorized, boolean enabledPreference) {
        return authorized && enabledPreference;
    }
}
