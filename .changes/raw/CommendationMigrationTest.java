package org.enthusia.rep.rep;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CommendationMigrationTest {
    @Test
    void legacyNegativeKeepsHistoricalWeightAndMigratesOtherCategory() {
        UUID giver = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("entry.giver", giver.toString());
        yaml.set("entry.target", target.toString());
        yaml.set("entry.positive", false);
        yaml.set("entry.category", "OTHER_NEGATIVE");
        yaml.set("entry.reason", "legacy");
        Commendation loaded = Commendation.fromSection(yaml.getConfigurationSection("entry"));
        assertNotNull(loaded);
        assertEquals(RepCategory.SCAMMED, loaded.getCategory());
        assertEquals(-1, loaded.getScoreValue());
    }

    @Test
    void storedScoreValueRoundTrips() {
        Commendation original = new Commendation(
                UUID.randomUUID(), UUID.randomUUID(), false, RepCategory.GRIEFED,
                "reason", 10L, 20L, "hash", -2);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.createSection("entry", original.serialize());
        Commendation loaded = Commendation.fromSection(yaml.getConfigurationSection("entry"));
        assertNotNull(loaded);
        assertEquals(-2, loaded.getScoreValue());
        assertEquals(RepCategory.GRIEFED, loaded.getCategory());
    }

    @Test
    void categoryTotalsUseActualPersistedWeights() {
        UUID target = UUID.randomUUID();
        List<Commendation> entries = List.of(
                new Commendation(UUID.randomUUID(), target, false, RepCategory.SCAMMED, "old", 1, 1, null, -1),
                new Commendation(UUID.randomUUID(), target, false, RepCategory.SCAMMED, "new", 2, 2, null, -2),
                new Commendation(UUID.randomUUID(), target, true, RepCategory.HELPED_ME, "good", 3, 3, null, 1)
        );
        Map<RepCategory, Integer> totals = RepRules.categoryScores(entries);
        assertEquals(-3, totals.get(RepCategory.SCAMMED));
        assertEquals(1, totals.get(RepCategory.HELPED_ME));
    }
}
