package org.enthusia.rep.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Offline synthetic-byte test only. Never substitutes for provider transaction tests. */
@EnabledOnOs(OS.LINUX)
class CorrectionStorageIntegrationTest {
    private static final String DATA_FILE = "simulated-primary.yml";
    private static final String LEDGER_SECTION = "correctionLedger";
    private static final String SUBJECT_SECTION = "syntheticSubject";
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000b01");
    private static final UUID REVIEWER = UUID.fromString("00000000-0000-0000-0000-000000000b02");
    private static final UUID GIVER = UUID.fromString("00000000-0000-0000-0000-000000000b03");
    private static final UUID OPERATION = UUID.fromString("00000000-0000-0000-0000-000000000b04");

    @TempDir
    Path folder;

    @Test
    void syntheticAfterStateAndLedgerSurviveOneAtomicPrimaryReplacement() throws Exception {
        Path file = folder.resolve(DATA_FILE);
        StrictAtomicFileReplacement.replace(file, serialized(8, CorrectionLedgerState.empty()));

        var committed = CorrectionLedgerState.empty().projectAppend(metadata());
        byte[] nextGeneration = serialized(6, committed);
        StrictAtomicFileReplacement.replace(file, nextGeneration);

        assertArrayEquals(nextGeneration, Files.readAllBytes(file));
        assertEquals(6, load(file).getInt(SUBJECT_SECTION + ".score"));
        assertEquals(committed, restoredLedger(file));
        assertEquals(metadata(), restoredLedger(file).entries().getFirst());
    }

    @Test
    void ambiguousPostRenameFailureRequiresReadbackRatherThanBlindReplay() throws Exception {
        Path file = folder.resolve(DATA_FILE);
        StrictAtomicFileReplacement.replace(file, serialized(8, CorrectionLedgerState.empty()));
        var projected = CorrectionLedgerState.empty().projectAppend(metadata());

        assertThrows(IOException.class, () ->
                StrictAtomicFileReplacement.replace(file, serialized(6, projected), checkpoint -> {
                    if (checkpoint == StrictAtomicFileReplacement.Checkpoint.AFTER_ATOMIC_MOVE) {
                        throw new IOException("Simulated crash before directory sync");
                    }
                }));

        // The method raised an error, yet the whole *synthetic* new generation
        // is visible. Only authoritative primary-file readback can distinguish
        // that state from an old generation after an ambiguous write.
        assertEquals(6, load(file).getInt(SUBJECT_SECTION + ".score"));
        var persisted = restoredLedger(file);
        assertEquals(projected, persisted);
        assertEquals(persisted, persisted.projectAppend(metadata()));
    }

    private static byte[] serialized(int score, CorrectionLedgerState ledger) {
        var yaml = new YamlConfiguration();
        yaml.createSection(SUBJECT_SECTION, Map.of("score", score));
        yaml.createSection(LEDGER_SECTION, ledger.toMap());
        return yaml.saveToString().getBytes(StandardCharsets.UTF_8);
    }

    private static YamlConfiguration load(Path file) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        return yaml;
    }

    private static CorrectionLedgerState restoredLedger(Path file) throws Exception {
        var section = load(file).getConfigurationSection(LEDGER_SECTION);
        assertNotNull(section);
        return CorrectionLedgerState.fromMap(section.getValues(false));
    }

    private static CorrectionCommitMetadata metadata() {
        return new CorrectionCommitMetadata(
                OPERATION, "CASE-B01", 2L, REVIEWER, SUBJECT,
                "a".repeat(64), "b".repeat(64), "c".repeat(64),
                1L, 1_000L,
                List.of(new ReputationEntrySnapshot(
                        GIVER, SUBJECT, true, "WAS_KIND", 2, 10L, 20L)));
    }
}
