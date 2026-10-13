package org.enthusia.rep.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.analytics.*;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AdvancementEvidenceWriterTest {
    private static final String DATA_FILE = "data.yml";
    private static final String POSITIVE_RECEIVED = "positiveReceived";
    private static final String MAX_OVERALL = "maxOverall";
    private static final String RECOVERED_FROM_SEVERE = "recoveredFromSevere";

    @TempDir Path directory;
    final UUID player = UUID.randomUUID();
    final String prefix = "advancementEvidence." + player + ".";

    PluginDataSnapshot snapshot(int score, List<Commendation> commendations, List<ReputationChangeRecord> changes) {
        return new PluginDataSnapshot(Map.of(player, score), commendations, List.of(), List.of(), changes, List.of());
    }

    Commendation positive() {
        return new Commendation(UUID.randomUUID(), player, true, RepCategory.WAS_KIND, "", 1, 1, null);
    }

    @Test void luluPositive23ExportsExistingEarnedMilestonesAndSurvivesRestart() {
        var store = new YamlPluginDataStore(directory.toFile(), Logger.getAnonymousLogger());
        assertTrue(store.save(snapshot(23, List.of(positive()), List.of())));
        var saved = YamlConfiguration.loadConfiguration(directory.resolve(DATA_FILE).toFile());
        assertTrue(saved.getBoolean(prefix + POSITIVE_RECEIVED));
        assertEquals(23, saved.getInt(prefix + MAX_OVERALL));
        assertEquals(1, saved.getInt(prefix + "categoryMax.WAS_KIND"));
        assertFalse(saved.getBoolean(prefix + RECOVERED_FROM_SEVERE));
        var restarted = new YamlPluginDataStore(directory.toFile(), Logger.getAnonymousLogger());
        restarted.load();
        assertTrue(restarted.save(snapshot(0, List.of(), List.of())));
        saved = YamlConfiguration.loadConfiguration(directory.resolve(DATA_FILE).toFile());
        assertTrue(saved.getBoolean(prefix + POSITIVE_RECEIVED));
        assertEquals(23, saved.getInt(prefix + MAX_OVERALL));
        assertEquals(1, saved.getInt(prefix + "categoryMax.WAS_KIND"));
    }

    @Test void adminPositiveBalanceDoesNotInventACommendationOrCategory() {
        var saved = new YamlConfiguration();
        AdvancementEvidenceWriter.write(saved, null, snapshot(23, List.of(), List.of()));
        assertEquals(23, saved.getInt(prefix + MAX_OVERALL));
        assertFalse(saved.getBoolean(prefix + POSITIVE_RECEIVED));
        assertFalse(saved.contains(prefix + "categoryMax"));
    }

    ReputationChangeRecord change(long time, int oldScore, int newScore, ReputationChangeOutcome outcome) {
        return new ReputationChangeRecord("change" + time, time, player, null, "System", newScore - oldScore,
                ReputationChangeAction.ADJUST, ReputationChangeSource.SYSTEM, outcome, "", null, oldScore, newScore);
    }

    @Test void replayDoesNotMistakePositiveBeforeNegativeForRedemption() {
        var first = new YamlConfiguration();
        var data = snapshot(-12, List.of(), List.of(change(1, 0, 23, ReputationChangeOutcome.SUCCEEDED),
                change(2, 23, -12, ReputationChangeOutcome.SUCCEEDED)));
        AdvancementEvidenceWriter.write(first, null, data);
        var second = new YamlConfiguration();
        AdvancementEvidenceWriter.write(second, first.getConfigurationSection("advancementEvidence"), data);
        assertFalse(second.getBoolean(prefix + RECOVERED_FROM_SEVERE));
        assertEquals(-12, second.getInt(prefix + "minOverall"));
        var recovered = new YamlConfiguration();
        AdvancementEvidenceWriter.write(recovered, second.getConfigurationSection("advancementEvidence"), snapshot(0, List.of(), List.of()));
        assertTrue(recovered.getBoolean(prefix + RECOVERED_FROM_SEVERE));
    }

    @Test void versionNineLoadBackfillsEvidenceWithoutChangingRep() throws Exception {
        var legacy = new YamlConfiguration();
        legacy.set("dataVersion", 9);
        legacy.set("players." + player + ".score", 23);
        legacy.createSection("commendations.0", positive().serialize());
        legacy.save(directory.resolve(DATA_FILE).toFile());
        var store = new YamlPluginDataStore(directory.toFile(), Logger.getAnonymousLogger());
        assertEquals(23, store.load().scores().get(player));
        var saved = YamlConfiguration.loadConfiguration(directory.resolve(DATA_FILE).toFile());
        assertTrue(saved.getBoolean(prefix + POSITIVE_RECEIVED));
        assertEquals(23, saved.getInt(prefix + MAX_OVERALL));
    }

    @Test void repeatedSnapshotsDoNotDoubleCountCategoryProgress() {
        var positives = java.util.stream.IntStream.range(0, 5).mapToObj(ignored -> positive()).toList();
        var first = new YamlConfiguration();
        AdvancementEvidenceWriter.write(first, null, snapshot(5, positives, List.of()));
        var second = new YamlConfiguration();
        AdvancementEvidenceWriter.write(second, first.getConfigurationSection("advancementEvidence"), snapshot(5, positives, List.of()));
        assertEquals(5, second.getInt(prefix + "categoryMax.WAS_KIND"));
    }

    @Test void failedTransitionsCannotEarnHistoricalMilestones() {
        var saved = new YamlConfiguration();
        AdvancementEvidenceWriter.write(saved, null, snapshot(0, List.of(),
                List.of(change(1, -25, 23, ReputationChangeOutcome.FAILED))));
        assertEquals(0, saved.getInt(prefix + MAX_OVERALL));
        assertEquals(0, saved.getInt(prefix + "minOverall"));
        assertFalse(saved.getBoolean(prefix + RECOVERED_FROM_SEVERE));
    }

    @Test void orderedHistoryCanProveRecoveryWithoutCurrentPositiveBalance() {
        var saved = new YamlConfiguration();
        AdvancementEvidenceWriter.write(saved, null, snapshot(-2, List.of(), List.of(
                change(3, 0, -2, ReputationChangeOutcome.SUCCEEDED),
                change(1, 0, -12, ReputationChangeOutcome.SUCCEEDED),
                change(2, -12, 0, ReputationChangeOutcome.SUCCEEDED))));
        assertTrue(saved.getBoolean(prefix + RECOVERED_FROM_SEVERE));
    }
}
