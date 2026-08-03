package org.enthusia.rep.rep;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepAlertPreferencesTest {
    @Test
    void newAuthorizedAdministratorInheritsEnabledDefault() {
        UUID administrator = UUID.randomUUID();
        RepAlertPreferences preferences = new RepAlertPreferences(true, Map.of());

        assertTrue(RepTradingAlertAccess.shouldDeliver(true, preferences.isEnabled(administrator)));
    }

    @Test
    void storedTrueDoesNotBypassMissingPermission() {
        UUID player = UUID.randomUUID();
        RepAlertPreferences preferences = new RepAlertPreferences(false, Map.of(player, true));

        assertFalse(RepTradingAlertAccess.shouldDeliver(false, preferences.isEnabled(player)));
    }

    @Test
    void disabledChoiceSurvivesRestartAndDoesNotAffectAnotherAdministrator() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        RepAlertPreferences preferences = new RepAlertPreferences(true, Map.of());
        assertFalse(preferences.toggle(first));
        assertTrue(preferences.isEnabled(second));

        RepAlertPreferences restarted = new RepAlertPreferences(true, preferences.snapshot());
        assertFalse(RepTradingAlertAccess.shouldDeliver(true, restarted.isEnabled(first)));
        assertTrue(RepTradingAlertAccess.shouldDeliver(true, restarted.isEnabled(second)));
    }

    @Test
    void administratorCanEnableAgain() {
        UUID player = UUID.randomUUID();
        RepAlertPreferences preferences = new RepAlertPreferences(true, Map.of());
        assertFalse(preferences.toggle(player));
        assertTrue(preferences.toggle(player));
        assertTrue(RepTradingAlertAccess.shouldDeliver(true, preferences.isEnabled(player)));
    }

    @Test
    void permissionLossImmediatelyPreventsDeliveryAndRestorationUsesSavedTrue() {
        UUID player = UUID.randomUUID();
        RepAlertPreferences preferences = new RepAlertPreferences(true, Map.of(player, true));

        assertTrue(RepTradingAlertAccess.shouldDeliver(true, preferences.isEnabled(player)));
        assertFalse(RepTradingAlertAccess.shouldDeliver(false, preferences.isEnabled(player)));
        assertTrue(RepTradingAlertAccess.shouldDeliver(true, preferences.isEnabled(player)));
    }

    @Test
    void permissionRestorationKeepsSavedFalseDisabled() {
        UUID player = UUID.randomUUID();
        RepAlertPreferences preferences = new RepAlertPreferences(true, Map.of(player, false));

        assertFalse(RepTradingAlertAccess.shouldDeliver(false, preferences.isEnabled(player)));
        assertFalse(RepTradingAlertAccess.shouldDeliver(true, preferences.isEnabled(player)));
    }

    @Test
    void configReloadDoesNotOverwriteExplicitPreference() {
        UUID explicit = UUID.randomUUID();
        UUID inherited = UUID.randomUUID();
        RepAlertPreferences preferences = new RepAlertPreferences(true, Map.of(explicit, false));
        preferences.reloadDefault(false);
        assertFalse(preferences.isEnabled(explicit));
        assertFalse(preferences.isEnabled(inherited));
        preferences.reloadDefault(true);
        assertFalse(preferences.isEnabled(explicit));
        assertTrue(preferences.isEnabled(inherited));
    }
}
