package org.enthusia.rep.analytics;

import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.config.RepConfig;
import org.enthusia.rep.rep.RepCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReputationAnalyticsServiceTest {
    @Test
    void recordsMetadataOnlyCommendationEditsInHistory() {
        RepConfig config = new RepConfig(new YamlConfiguration());
        AtomicBoolean dirty = new AtomicBoolean(false);
        ReputationAnalyticsService service = new ReputationAnalyticsService(
                () -> config,
                List.of(),
                () -> dirty.set(true)
        );

        UUID target = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        service.recordPlayerChange(
                target,
                actor,
                0,
                ReputationChangeAction.UPDATE,
                RepCategory.WAS_KIND,
                "Updated explanation",
                5,
                5
        );

        List<ReputationChangeRecord> history = service.playerHistory(target, 10);
        assertEquals(1, history.size());
        assertEquals(0, history.getFirst().amount());
        assertEquals(ReputationChangeAction.UPDATE, history.getFirst().action());
        assertTrue(dirty.get());
    }
}
