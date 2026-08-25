package org.enthusia.rep.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.api.ReputationMutationResult;
import org.enthusia.rep.api.ReputationStateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReputationModerationStoreTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID OPERATION = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @TempDir
    Path temporaryDirectory;

    @Test
    void committedOperationPersistsOneCanonicalSnapshot() throws Exception {
        Path file = temporaryDirectory.resolve("state.yml");
        ReputationStateSnapshot snapshot = snapshot(7);
        ReputationModerationStore store = new ReputationModerationStore(file);

        store.save(state(snapshot, snapshot));

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        String operationPath = "operations." + OPERATION;
        assertTrue(yaml.isConfigurationSection(operationPath + ".snapshot"));
        assertFalse(yaml.contains(operationPath + ".before"));
        assertFalse(yaml.contains(operationPath + ".after"));

        ReputationModerationStore.Operation loaded = store.load().operations().get(OPERATION);
        assertEquals(snapshot, loaded.before());
        assertEquals(snapshot, loaded.after());
    }

    @Test
    void legacyTwoSnapshotOperationStillLoadsWhenSnapshotsMatch() throws Exception {
        Path file = temporaryDirectory.resolve("legacy-state.yml");
        ReputationStateSnapshot snapshot = snapshot(7);
        ReputationModerationStore store = new ReputationModerationStore(file);
        store.save(state(snapshot, snapshot));

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        String operationPath = "operations." + OPERATION;
        ConfigurationSection compact = yaml.getConfigurationSection(operationPath + ".snapshot");
        Map<String, Object> values = compact.getValues(false);
        yaml.set(operationPath + ".snapshot", null);
        yaml.createSection(operationPath + ".before", values);
        yaml.createSection(operationPath + ".after", values);
        yaml.save(file.toFile());

        ReputationModerationStore.Operation loaded = store.load().operations().get(OPERATION);
        assertEquals(snapshot, loaded.before());
        assertEquals(snapshot, loaded.after());
    }

    @Test
    void committedOperationWithDifferentSnapshotsFailsClosed() {
        ReputationModerationStore store = new ReputationModerationStore(temporaryDirectory.resolve("invalid-state.yml"));

        assertThrows(IllegalStateException.class, () -> store.save(state(snapshot(7), snapshot(8))));
    }

    private static ReputationModerationStore.State state(
            ReputationStateSnapshot before,
            ReputationStateSnapshot after
    ) {
        ReputationModerationStore.Operation operation = new ReputationModerationStore.Operation(
                OPERATION,
                "fingerprint",
                ReputationMutationResult.Status.APPLIED,
                Optional.empty(),
                before,
                after,
                "detail"
        );
        return new ReputationModerationStore.State(
                Map.of(),
                new LinkedHashMap<>(Map.of(OPERATION, operation)),
                Set.of()
        );
    }

    private static ReputationStateSnapshot snapshot(int totalScore) {
        return new ReputationStateSnapshot(
                PLAYER,
                totalScore,
                List.of(),
                ReputationSnapshotFactory.checksum(PLAYER, totalScore, List.of())
        );
    }
}
