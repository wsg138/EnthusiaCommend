package org.enthusia.rep.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.junit.jupiter.api.Test;

class CorrectionLedgerStateTest {
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000a01");
    private static final UUID REVIEWER = UUID.fromString("00000000-0000-0000-0000-000000000a02");
    private static final UUID GIVER = UUID.fromString("00000000-0000-0000-0000-000000000a03");
    private static final UUID FIRST_OPERATION = UUID.fromString("00000000-0000-0000-0000-000000000a04");
    private static final UUID SECOND_OPERATION = UUID.fromString("00000000-0000-0000-0000-000000000a05");
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String BEFORE = "b".repeat(64);
    private static final String AFTER = "c".repeat(64);

    @Test
    void appendsOneGenerationAndReturnsOriginalForIdenticalReplay() {
        var empty = CorrectionLedgerState.empty();
        var first = candidate(FIRST_OPERATION, 1L);
        var projected = empty.projectAppend(first);
        assertEquals(0L, empty.currentGeneration());
        assertTrue(empty.entries().isEmpty());
        assertEquals(1L, projected.currentGeneration());
        assertEquals(List.of(first), projected.entries());
        assertSame(projected, projected.projectAppend(first));

        var second = candidate(SECOND_OPERATION, 2L);
        var after = projected.projectAppend(second);
        assertEquals(2L, after.currentGeneration());
        assertEquals(List.of(first, second), after.entries());
        assertEquals(List.of(first), projected.entries());
    }

    @Test
    void refusesConflictingReplayAndStaleOrSkippedGenerations() {
        var first = candidate(FIRST_OPERATION, 1L);
        var ledger = CorrectionLedgerState.empty().projectAppend(first);
        assertThrows(IllegalStateException.class, () ->
                ledger.projectAppend(candidate(FIRST_OPERATION, 2L)));
        assertThrows(IllegalStateException.class, () ->
                ledger.projectAppend(candidate(SECOND_OPERATION, 1L)));
        assertThrows(IllegalStateException.class, () ->
                ledger.projectAppend(candidate(SECOND_OPERATION, 3L)));
        assertThrows(IllegalArgumentException.class, () ->
                new CorrectionLedgerState(2L, List.of(first, first)));
        assertThrows(IllegalArgumentException.class, () ->
                new CorrectionLedgerState(1L, List.of(first, candidate(SECOND_OPERATION, 2L))));
        assertThrows(IllegalArgumentException.class, () ->
                new CorrectionLedgerState(-1L, List.of()));
    }

    @Test
    void exactMapAndYamlRoundTripMaintainsEntriesAndGeneration() throws Exception {
        var current = CorrectionLedgerState.empty().projectAppend(candidate(FIRST_OPERATION, 1L))
                .projectAppend(candidate(SECOND_OPERATION, 2L));
        assertEquals(current, CorrectionLedgerState.fromMap(current.toMap()));

        var yaml = new YamlConfiguration();
        yaml.createSection("commitLedger", current.toMap());
        var parsedYaml = new YamlConfiguration();
        parsedYaml.loadFromString(yaml.saveToString());
        var serialized = parsedYaml.getConfigurationSection("commitLedger");
        assertEquals(current, CorrectionLedgerState.fromMap(serialized.getValues(false)));
    }

    @Test
    void rejectsTamperedRootEntriesAndUnexpectedLedgerShapes() {
        var state = CorrectionLedgerState.empty().projectAppend(candidate(FIRST_OPERATION, 1L));
        var badField = new LinkedHashMap<>(state.toMap());
        badField.put("claimedCommitted", true);
        assertThrows(IllegalArgumentException.class, () ->
                CorrectionLedgerState.fromMap(badField));

        var wrongGeneration = new LinkedHashMap<>(state.toMap());
        wrongGeneration.put("generation", 0L);
        assertThrows(IllegalArgumentException.class, () ->
                CorrectionLedgerState.fromMap(wrongGeneration));

        var fractionalGeneration = new LinkedHashMap<>(state.toMap());
        fractionalGeneration.put("generation", 1.5);
        assertThrows(IllegalArgumentException.class, () ->
                CorrectionLedgerState.fromMap(fractionalGeneration));

        var forgedEntry = new LinkedHashMap<>(state.toMap());
        @SuppressWarnings("unchecked")
        var list = (List<Map<String, Object>>) forgedEntry.get("corrections");
        var altered = new LinkedHashMap<>(list.getFirst());
        altered.put("afterChecksum", BEFORE);
        forgedEntry.put("corrections", List.of(altered));
        assertThrows(IllegalArgumentException.class, () ->
                CorrectionLedgerState.fromMap(forgedEntry));
    }

    private static CorrectionCommitMetadata candidate(UUID operation, long generation) {
        return new CorrectionCommitMetadata(
                operation, "CASE-A01", 7L, REVIEWER, SUBJECT,
                FINGERPRINT, BEFORE, AFTER, generation, 1000L,
                List.of(new ReputationEntrySnapshot(
                        GIVER, SUBJECT, true, "WAS_KIND", 1, 10L, 20L)));
    }
}
