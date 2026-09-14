package org.enthusia.rep.rep;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.config.RepConfig;
import org.enthusia.rep.storage.PluginDataSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class RepServiceRecentTimestampsTest {
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setup() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
    }

    @AfterEach
    void close() {
        bukkit.close();
    }

    @Test
    void aggregatesLatestMatchingTimestampPerTargetWithoutSortingHistory() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID giver = UUID.randomUUID();
        List<Commendation> entries = List.of(
                entry(giver, first, RepCategory.WAS_KIND, 10L),
                entry(UUID.randomUUID(), first, RepCategory.SCAMMED, 30L),
                entry(UUID.randomUUID(), second, RepCategory.WAS_KIND, 20L));
        PluginDataSnapshot snapshot = new PluginDataSnapshot(
                Map.of(), entries, List.of(), List.of(), List.of(), List.of());
        YamlConfiguration config = new YamlConfiguration();
        config.set("rep.ipProtection.enabled", false);
        RepService service = new RepService(mock(CommendPlugin.class), new RepConfig(config), snapshot,
                () -> { }, ignored -> { }, null);

        assertEquals(Map.of(first, 10L, second, 20L),
                service.latestCommendationTimestamps(Commendation::isPositive, 0L));
        assertEquals(Map.of(second, 20L),
                service.latestCommendationTimestamps(Commendation::isPositive, 15L));
    }

    private Commendation entry(UUID giver, UUID target, RepCategory category, long edited) {
        return new Commendation(giver, target, category.isPositive(), category, "reason", 1L, edited, null,
                category.defaultScoreValue());
    }
}
