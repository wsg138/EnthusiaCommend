package org.enthusia.rep.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.analytics.ReputationChangeAction;
import org.enthusia.rep.analytics.ReputationChangeOutcome;
import org.enthusia.rep.analytics.ReputationChangeRecord;
import org.enthusia.rep.analytics.ReputationChangeSource;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;
import org.enthusia.rep.rep.RepService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlPluginDataStoreTest {
    private static final String INVALID_VALUE = "invalid";

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsEveryPersistedDataSection() {
        UUID giverId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Commendation commendation = new Commendation(
                giverId, targetId, true, RepCategory.WAS_KIND, "Helped with a build",
                100L, 110L, "ip-hash", 1
        );
        ReputationChangeRecord change = new ReputationChangeRecord(
                "change-1", 120L, targetId, giverId, "Giver", 1,
                ReputationChangeAction.ADD, ReputationChangeSource.PLAYER_ACTION,
                ReputationChangeOutcome.SUCCEEDED, "Helped with a build",
                RepCategory.WAS_KIND, 3, 4
        );
        PluginDataSnapshot snapshot = new PluginDataSnapshot(
                Map.of(targetId, 4),
                List.of(commendation),
                List.of(new RepService.RemovedRep("removed-1", commendation, 130L, staffId)),
                List.of(new PluginDataSnapshot.StalkEntry(giverId, targetId, 140L)),
                List.of(change),
                List.of(new RepService.SuspiciousRepCase(
                        targetId, "ALT_IP", "case-key", List.of(giverId), 150L, false, "Shared address")),
                List.of(new PluginDataSnapshot.RemovalCooldownEntry(giverId, targetId, 160L)),
                Map.of(targetId, false)
        );
        YamlPluginDataStore store = new YamlPluginDataStore(
                temporaryDirectory.toFile(), testLogger());

        assertTrue(store.save(snapshot));
        PluginDataSnapshot loaded = store.load();

        assertEquals(Map.of(targetId, 4), loaded.scores());
        assertCommendation(commendation, loaded.commendations().getFirst());
        assertEquals("removed-1", loaded.removedEntries().getFirst().id());
        assertCommendation(commendation, loaded.removedEntries().getFirst().commendation());
        assertEquals(snapshot.stalkEntries(), loaded.stalkEntries());
        assertEquals(List.of(change), loaded.reputationChanges());
        assertEquals("case-key", loaded.suspiciousCases().getFirst().key());
        assertEquals("Shared address", loaded.suspiciousCases().getFirst().detail());
        assertEquals(snapshot.removalCooldowns(), loaded.removalCooldowns());
        assertFalse(loaded.repTradingAlertPreferences().get(targetId));
        assertFalse(Files.exists(temporaryDirectory.resolve("data.yml.tmp")));
    }

    @Test
    void missingDataFileLoadsAnEmptySnapshot() {
        YamlPluginDataStore store = new YamlPluginDataStore(
                temporaryDirectory.toFile(), testLogger());

        assertEquals(PluginDataSnapshot.EMPTY, store.load());
    }

    @Test
    void skipsMalformedPersistedEntries() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("players.not-a-uuid.score", 9);
        config.set("commendations.0.giver", "not-a-uuid");
        config.set("removed", List.of(Map.of("id", INVALID_VALUE)));
        config.set("reputationChanges", List.of(Map.of("target", INVALID_VALUE)));
        config.set("suspiciousCases", List.of(Map.of("target", INVALID_VALUE)));
        config.set("removalCooldowns", List.of(Map.of("giver", INVALID_VALUE)));
        config.set("playerSettings.not-a-uuid.repTradingAlertsEnabled", true);
        config.set("stalks.0.stalker", "not-a-uuid");
        config.save(temporaryDirectory.resolve("data.yml").toFile());
        YamlPluginDataStore store = new YamlPluginDataStore(
                temporaryDirectory.toFile(), testLogger());

        assertEquals(PluginDataSnapshot.EMPTY, store.load());
    }

    @Test
    void reportsSaveFailureWhenDataFolderIsBlocked() throws Exception {
        Path blockedDataFolder = temporaryDirectory.resolve("blocked-data-folder");
        Files.writeString(blockedDataFolder, "not a directory");
        YamlPluginDataStore store = new YamlPluginDataStore(
                blockedDataFolder.toFile(), testLogger());

        assertFalse(store.save(PluginDataSnapshot.EMPTY));
        assertFalse(Files.exists(temporaryDirectory.resolve("blocked-data-folder/data.yml.tmp")));
    }

    private Logger testLogger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return logger;
    }

    private void assertCommendation(Commendation expected, Commendation actual) {
        assertEquals(expected.getGiver(), actual.getGiver());
        assertEquals(expected.getTarget(), actual.getTarget());
        assertEquals(expected.isPositive(), actual.isPositive());
        assertEquals(expected.getCategory(), actual.getCategory());
        assertEquals(expected.getReasonText(), actual.getReasonText());
        assertEquals(expected.getCreatedAt(), actual.getCreatedAt());
        assertEquals(expected.getLastEditedAt(), actual.getLastEditedAt());
        assertEquals(expected.getIpHash(), actual.getIpHash());
        assertEquals(expected.getScoreValue(), actual.getScoreValue());
    }
}
