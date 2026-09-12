package org.enthusia.rep.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.rep.RepCategory;
import org.enthusia.rep.rep.Commendation;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RepCustomizationTest {
    @Test
    void hexNamedAndLegacyColorsRoundTripWithInvalidFallback() {
        assertEquals("#ff4a00", RepColor.miniMessageTag(RepColor.code("#FF4A00", "GOLD")));
        assertEquals("#ff4a00", RepColor.miniMessageTag(RepColor.code("&#FF4A00", "GOLD")));
        assertEquals("green", RepColor.miniMessageTag(RepColor.code("&a", "GOLD")));
        assertEquals("gold", RepColor.miniMessageTag(RepColor.code("invalid", "GOLD")));
        var yaml = new YamlConfiguration();
        yaml.set("rep.tarnished.color", "#FF4A00");
        yaml.set("rep.colors.positive", "#123456");
        var config = new RepConfig(yaml);
        assertEquals("#ff4a00", RepColor.miniMessageTag(config.getTarnishedColorCode()));
        assertEquals("#123456", RepColor.miniMessageTag(config.colorCodeForScore(10)));
    }

    @Test
    void descriptionsUseCustomTextAndCanonicalCategory() {
        var yaml = new YamlConfiguration();
        yaml.set("rep.categories.SPAWN_KILLED.description", "Repeated kills near spawn.");
        yaml.set("rep.categories.WAS_KIND.description", "Kind or helpful.");
        var config = new RepConfig(yaml);
        assertEquals("Repeated kills near spawn.", config.getCategoryDescription(RepCategory.SPAWN_KILLED));
        assertEquals("Kind or helpful.", config.getCategoryDescription(RepCategory.HELPED_ME));
        assertEquals(RepCategory.GRIEFED.description(), config.getCategoryDescription(RepCategory.GRIEFED));
    }

    @Test
    void helpedVotesKeepReasonIdentityTimestampsAndWeightOnMigration() {
        var yaml = new YamlConfiguration();
        var original = new Commendation(UUID.randomUUID(), UUID.randomUUID(), true, RepCategory.WAS_KIND, "helped", 12, 34, "hash", 5);
        yaml.createSection("entry", original.serialize());
        yaml.set("entry.category", "HELPED_ME");
        var migrated = Commendation.fromSection(yaml.getConfigurationSection("entry"));
        assertNotNull(migrated);
        assertEquals(original.serialize(), migrated.serialize());
    }

    @Test
    void effectMigrationCombinesCustomRulesOnce() {
        var yaml = new YamlConfiguration();
        yaml.set("rep.effectRules.categories.WAS_KIND", List.of(Map.of("effect", "teleport", "threshold", 2, "value", .8)));
        yaml.set("rep.effectRules.categories.HELPED_ME", List.of(Map.of("effect", "teleport", "threshold", 4, "value", .6)));
        assertTrue(KindCategoryMigration.migrate(yaml));
        assertFalse(KindCategoryMigration.migrate(yaml));
        assertFalse(yaml.contains("rep.effectRules.categories.HELPED_ME"));
        assertEquals(2, yaml.getMapList("rep.effectRules.categories.WAS_KIND").size());
    }
}
