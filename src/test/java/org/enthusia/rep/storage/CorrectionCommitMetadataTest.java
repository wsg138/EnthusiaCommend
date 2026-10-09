package org.enthusia.rep.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.junit.jupiter.api.Test;

class CorrectionCommitMetadataTest {
    private static final UUID OPERATION = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final UUID REVIEWER = UUID.fromString("00000000-0000-0000-0000-000000000902");
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000903");
    private static final UUID GIVER = UUID.fromString("00000000-0000-0000-0000-000000000904");
    private static final UUID SECOND_GIVER = UUID.fromString("00000000-0000-0000-0000-000000000905");
    private static final String PREPARED_DIGEST = "a".repeat(64);
    private static final String BEFORE_DIGEST = "b".repeat(64);
    private static final String AFTER_DIGEST = "c".repeat(64);

    @Test
    void strictMetadataRoundTripsEveryCaseRevisionAndExactRemovedEntry() {
        var expected = sample();
        var map = expected.toMap();
        var restored = CorrectionCommitMetadata.fromMap(map);
        assertEquals(expected, restored);
        assertEquals(1, map.get("schema"));
        assertEquals("CASE-901", map.get("case"));
        assertEquals(7L, map.get("caseRevision"));
        assertEquals(19L, map.get("generation"));
        assertEquals(2, restored.removedEntries().size());
        assertEquals(SECOND_GIVER, restored.removedEntries().getLast().giverId());
        assertEquals(map, restored.toMap());
        assertTrue(restored.toMap().containsKey("preparedFingerprint"));
    }

    @Test
    void refusesUnknownMissingWronglyTypedOrTamperedFields() {
        var good = sample().toMap();
        var unknown = new LinkedHashMap<>(good);
        unknown.put("status", "COMMITTED");
        assertThrows(IllegalArgumentException.class, () ->
                CorrectionCommitMetadata.fromMap(unknown));

        var missing = new LinkedHashMap<>(good);
        missing.remove("reviewer");
        assertThrows(IllegalArgumentException.class, () ->
                CorrectionCommitMetadata.fromMap(missing));

        var wrongSchema = new LinkedHashMap<>(good);
        wrongSchema.put("schema", 2);
        assertThrows(IllegalArgumentException.class, () ->
                CorrectionCommitMetadata.fromMap(wrongSchema));

        var changedRevision = new LinkedHashMap<>(good);
        changedRevision.put("caseRevision", 0L);
        assertThrows(IllegalArgumentException.class, () ->
                CorrectionCommitMetadata.fromMap(changedRevision));

        var fractionalGeneration = new LinkedHashMap<>(good);
        fractionalGeneration.put("generation", 1.5);
        assertThrows(IllegalArgumentException.class, () ->
                CorrectionCommitMetadata.fromMap(fractionalGeneration));

        var forgedChecksum = new LinkedHashMap<>(good);
        forgedChecksum.put("afterChecksum", "bad");
        assertThrows(IllegalArgumentException.class, () ->
                CorrectionCommitMetadata.fromMap(forgedChecksum));

        var unknownEntry = new LinkedHashMap<>(good);
        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, Object>>) good.get("entries");
        var changedEntry = new LinkedHashMap<>(entries.getFirst());
        changedEntry.put("tampered", true);
        unknownEntry.put("entries", List.of(changedEntry));
        assertThrows(IllegalArgumentException.class, () ->
                CorrectionCommitMetadata.fromMap(unknownEntry));

        var malformedSign = new LinkedHashMap<>(good);
        var invalidEntry = new LinkedHashMap<>(entries.getFirst());
        invalidEntry.put("scoreValue", -2);
        malformedSign.put("entries", List.of(invalidEntry));
        assertThrows(IllegalArgumentException.class, () ->
                CorrectionCommitMetadata.fromMap(malformedSign));
    }

    @Test
    void refusesForgedSubjectsDuplicateGiversEmptySelectionAndUnboundedEntries() {
        var originals = sample().removedEntries();
        var conflicting = new ReputationEntrySnapshot(
                GIVER, SUBJECT, true, "WAS_KIND", 1, 100L, 200L);
        assertThrows(IllegalArgumentException.class, () ->
                metadata(List.of(originals.getFirst(), conflicting)));

        var foreign = new ReputationEntrySnapshot(
                SECOND_GIVER, REVIEWER, false, "SCAMMED", -3, 101L, 201L);
        assertThrows(IllegalArgumentException.class, () -> metadata(List.of(foreign)));
        assertThrows(IllegalArgumentException.class, () -> metadata(List.of()));

        var tooMany = new ArrayList<ReputationEntrySnapshot>();
        for (int i = 0; i < 101; i++) {
            tooMany.add(new ReputationEntrySnapshot(new UUID(0L, i + 1),
                    SUBJECT, true, "WAS_KIND", 1, 1L, 2L));
        }
        assertThrows(IllegalArgumentException.class, () -> metadata(tooMany));

        assertThrows(IllegalArgumentException.class, () ->
                new CorrectionCommitMetadata(OPERATION, "CASE-901", 7L, REVIEWER, SUBJECT,
                        PREPARED_DIGEST, BEFORE_DIGEST, BEFORE_DIGEST, 19L,
                        1000L, originals));
    }

    private static CorrectionCommitMetadata sample() {
        return metadata(List.of(
                new ReputationEntrySnapshot(GIVER, SUBJECT, true, "WAS_KIND", 2, 100L, 200L),
                new ReputationEntrySnapshot(SECOND_GIVER, SUBJECT, false, "SCAMMED", -3, 110L, 220L)));
    }

    private static CorrectionCommitMetadata metadata(List<ReputationEntrySnapshot> entries) {
        return new CorrectionCommitMetadata(OPERATION, "CASE-901", 7L, REVIEWER, SUBJECT,
                PREPARED_DIGEST, BEFORE_DIGEST, AFTER_DIGEST, 19L, 1000L, entries);
    }
}
