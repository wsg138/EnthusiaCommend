package org.enthusia.rep.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PluginDataSnapshotCompatibilityTest {
    @Test
    void legacyConstructorCreatesNoExplicitAlertPreferences() {
        PluginDataSnapshot snapshot = new PluginDataSnapshot(Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertTrue(snapshot.repTradingAlertPreferences().isEmpty());
    }

    @Test
    void explicitPreferencesAreRetainedWithoutChangingExistingRecords() {
        UUID player = UUID.randomUUID();
        PluginDataSnapshot snapshot = new PluginDataSnapshot(Map.of(player, 7), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), Map.of(player, false));
        assertEquals(7, snapshot.scores().get(player));
        assertFalse(snapshot.repTradingAlertPreferences().get(player));
    }
}
