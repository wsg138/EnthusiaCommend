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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlPluginDataStoreTest {
    private static final String INVALID_VALUE = "invalid";
    private static final String PROTECTED_HASH = "h1:00112233445566778899aabbccddeeff";

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsEveryPersistedDataSection() {
        UUID giverId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Commendation commendation = new Commendation(
                giverId, targetId, true, RepCategory.WAS_KIND, "Helped with a build",
                100L, 110L, PROTECTED_HASH, 1
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
                        targetId, "ALT_IP", PROTECTED_HASH, List.of(giverId), 150L, false, "Shared address")),
                List.of(new PluginDataSnapshot.RemovalCooldownEntry(giverId, targetId, 160L)),
                Map.of(targetId, false),
                Map.of(giverId, new org.enthusia.rep.rep.RepIdentityState(java.util.Set.of(PROTECTED_HASH), java.util.Set.of(targetId), 170L, Map.of(targetId.toString(), 170L)))
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
        assertEquals(PROTECTED_HASH, loaded.suspiciousCases().getFirst().key());
        assertEquals("Shared address", loaded.suspiciousCases().getFirst().detail());
        assertEquals(snapshot.removalCooldowns(), loaded.removalCooldowns());
        assertEquals(snapshot.identities(), loaded.identities());
        assertFalse(loaded.repTradingAlertPreferences().get(targetId));
        assertFalse(Files.exists(temporaryDirectory.resolve("data.yml.tmp")));
    }

    @Test
    void migratesLegacyAddressIdentifiersOutOfPersistedData() throws Exception {
        UUID giverId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        YamlConfiguration config = new YamlConfiguration();
        config.set("dataVersion", 8);
        config.set("players." + targetId + ".score", 1);
        config.set("identities." + giverId + ".ipHashes", List.of("legacy-unsalted-hash"));
        config.set("identities." + giverId + ".givenTargets", List.of(targetId.toString()));
        config.set("commendations.0.giver", giverId.toString());
        config.set("commendations.0.target", targetId.toString());
        config.set("commendations.0.positive", true);
        config.set("commendations.0.category", RepCategory.WAS_KIND.name());
        config.set("commendations.0.reason", "legacy");
        config.set("commendations.0.createdAt", 10L);
        config.set("commendations.0.lastEditedAt", 20L);
        config.set("commendations.0.scoreValue", 1);
        config.set("commendations.0.ipHash", "legacy-unsalted-hash");
        config.save(temporaryDirectory.resolve("data.yml").toFile());

        YamlPluginDataStore store = new YamlPluginDataStore(temporaryDirectory.toFile(), testLogger());
        PluginDataSnapshot loaded = store.load();

        assertTrue(loaded.identities().get(giverId).ipHashes().isEmpty());
        assertNull(loaded.commendations().getFirst().getIpHash());

        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(temporaryDirectory.resolve("data.yml").toFile());
        assertEquals(9, migrated.getInt("dataVersion"));
        assertTrue(migrated.getStringList("identities." + giverId + ".ipHashes").isEmpty());
        assertFalse(migrated.isSet("commendations.0.ipHash"));
    }

    @Test
    void duplicateCanonicalIdentityKeysDoNotAbortLoad() throws Exception {
        UUID playerId = UUID.fromString("abcdefab-cdef-abcd-efab-cdefabcdefab");
        String lower = playerId.toString();
        String upper = lower.toUpperCase(java.util.Locale.ROOT);
        YamlConfiguration config = new YamlConfiguration();
        config.set("dataVersion", 9);
        config.set("identities." + lower + ".ipHashes", List.of(PROTECTED_HASH));
        config.set("identities." + lower + ".givenTargets", List.of());
        config.set("identities." + upper + ".ipHashes", List.of(PROTECTED_HASH));
        config.set("identities." + upper + ".givenTargets", List.of());
        config.save(temporaryDirectory.resolve("data.yml").toFile());

        YamlPluginDataStore store = new YamlPluginDataStore(temporaryDirectory.toFile(), testLogger());
        PluginDataSnapshot loaded = store.load();

        assertEquals(1, loaded.identities().size());
        assertTrue(loaded.identities().containsKey(playerId));
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
