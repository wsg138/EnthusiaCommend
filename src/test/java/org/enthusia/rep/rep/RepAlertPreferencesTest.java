package org.enthusia.rep.rep;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RepAlertPreferencesTest {
    @Test
    void newPlayerInheritsEnabledDefault() {
        assertTrue(new RepAlertPreferences(true, Map.of()).isEnabled(UUID.randomUUID()));
    }

    @Test
    void disabledChoiceSurvivesReloadAndDoesNotAffectAnotherPlayer() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        RepAlertPreferences preferences = new RepAlertPreferences(true, Map.of());
        assertFalse(preferences.toggle(first));
        assertTrue(preferences.isEnabled(second));

        RepAlertPreferences reloaded = new RepAlertPreferences(true, preferences.snapshot());
        assertFalse(reloaded.isEnabled(first));
        assertTrue(reloaded.isEnabled(second));
    }

    @Test
    void playerCanEnableAgain() {
        UUID player = UUID.randomUUID();
        RepAlertPreferences preferences = new RepAlertPreferences(true, Map.of());
        assertFalse(preferences.toggle(player));
        assertTrue(preferences.toggle(player));
        assertTrue(preferences.isEnabled(player));
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
