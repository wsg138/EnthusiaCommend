package org.enthusia.rep.storage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.enthusia.rep.api.ReputationEntrySnapshot;

/**
 * Schema-checked candidate metadata for a future provider-committed correction.
 *
 * <p>Having this object or a valid serialized map is NOT proof of authorization,
 * durable commit, or an issued receipt. A trusted provider must bind it to the
 * complete corrected primary snapshot in the SAME atomic file generation.
 * Consumers cannot accept caller-provided maps as a commit confirmation.</p>
 */
public record CorrectionCommitMetadata(
        UUID operationId,
        String caseId,
        long caseRevision,
        UUID reviewerId,
        UUID subjectId,
        String preparedFingerprint,
        String beforeChecksum,
        String afterChecksum,
        long dataGeneration,
        long committedAtMillis,
        List<ReputationEntrySnapshot> removedEntries
) {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_ENTRIES = 100;
    private static final Pattern CASE_ID_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]+");
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> FIELDS = Set.of(
            "schema", "operation", "case", "caseRevision", "reviewer", "subject",
            "preparedFingerprint", "beforeChecksum", "afterChecksum", "generation",
            "committedAt", "entries");
    private static final Set<String> ENTRY_FIELDS = Set.of(
            "giver", "target", "positive", "category", "scoreValue", "createdAt", "lastEditedAt");

    public CorrectionCommitMetadata {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(reviewerId, "reviewerId");
        Objects.requireNonNull(subjectId, "subjectId");
        caseId = validateCase(caseId);
        preparedFingerprint = digest(preparedFingerprint, "prepared fingerprint");
        beforeChecksum = digest(beforeChecksum, "before checksum");
        afterChecksum = digest(afterChecksum, "after checksum");
        if (caseRevision <= 0L || dataGeneration <= 0L || committedAtMillis < 0L) {
            throw new IllegalArgumentException("Case revision, generation and timestamp are invalid");
        }
        if (beforeChecksum.equals(afterChecksum)) {
            throw new IllegalArgumentException("A correction must change the subject snapshot");
        }
        removedEntries = List.copyOf(Objects.requireNonNull(removedEntries, "removedEntries"));
        if (removedEntries.isEmpty() || removedEntries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Correction entry count is invalid");
        }
        Set<UUID> givers = new HashSet<>();
        for (ReputationEntrySnapshot entry : removedEntries) {
            if (!entry.targetId().equals(subjectId) || !givers.add(entry.giverId())) {
                throw new IllegalArgumentException("Correction entries have wrong target or duplicate giver");
            }
        }
    }

    /**
     * YAML-compatible record value intended for a future *primary* data file.
     * A copy of this map in a sidecar file is never a commit receipt.
     */
    // Local YAML assembly map; insertion order is intentional and no concurrent sharing occurs.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", SCHEMA_VERSION);
        result.put("operation", operationId.toString());
        result.put("case", caseId);
        result.put("caseRevision", caseRevision);
        result.put("reviewer", reviewerId.toString());
        result.put("subject", subjectId.toString());
        result.put("preparedFingerprint", preparedFingerprint);
        result.put("beforeChecksum", beforeChecksum);
        result.put("afterChecksum", afterChecksum);
        result.put("generation", dataGeneration);
        result.put("committedAt", committedAtMillis);
        result.put("entries", removedEntries.stream().map(CorrectionCommitMetadata::entryMap).toList());
        return result;
    }

    public static CorrectionCommitMetadata fromMap(Map<?, ?> source) {
        requireKeys(source, FIELDS);
        if (integer(source, "schema") != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported correction metadata schema");
        }
        Object rawEntries = source.get("entries");
        if (!(rawEntries instanceof List<?> entries)) {
            throw new IllegalArgumentException("Correction entries must be a list");
        }
        List<ReputationEntrySnapshot> parsed = new ArrayList<>();
        if (entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Correction entry count is invalid");
        }
        for (Object raw : entries) {
            if (!(raw instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException("Correction entry is not a map");
            }
            parsed.add(parseEntry(entry));
        }
        return new CorrectionCommitMetadata(
                uuid(source, "operation"), string(source, "case"), integer(source, "caseRevision"),
                uuid(source, "reviewer"), uuid(source, "subject"),
                string(source, "preparedFingerprint"), string(source, "beforeChecksum"),
                string(source, "afterChecksum"), integer(source, "generation"),
                integer(source, "committedAt"), parsed);
    }

    // Per-entry ordered YAML assembly; thread-safe map replacement would lose ordering.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private static Map<String, Object> entryMap(ReputationEntrySnapshot entry) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("giver", entry.giverId().toString());
        record.put("target", entry.targetId().toString());
        record.put("positive", entry.positive());
        record.put("category", entry.category());
        record.put("scoreValue", entry.scoreValue());
        record.put("createdAt", entry.createdAt());
        record.put("lastEditedAt", entry.lastEditedAt());
        return record;
    }

    private static ReputationEntrySnapshot parseEntry(Map<?, ?> record) {
        requireKeys(record, ENTRY_FIELDS);
        Object positive = record.get("positive");
        if (!(positive instanceof Boolean value)) {
            throw new IllegalArgumentException("Correction entry polarity must be a boolean");
        }
        long rawScore = integer(record, "scoreValue");
        int score = Math.toIntExact(rawScore);
        return new ReputationEntrySnapshot(uuid(record, "giver"), uuid(record, "target"),
                value, string(record, "category"), score,
                integer(record, "createdAt"), integer(record, "lastEditedAt"));
    }

    private static void requireKeys(Map<?, ?> source, Set<String> expected) {
        if (source == null || !source.keySet().equals(expected)) {
            throw new IllegalArgumentException("Correction metadata contains missing or unknown fields");
        }
    }

    private static String string(Map<?, ?> map, String name) {
        Object value = map.get(name);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Correction metadata field is not text: " + name);
        }
        return text;
    }

    private static UUID uuid(Map<?, ?> map, String name) {
        return UUID.fromString(string(map, name));
    }

    private static long integer(Map<?, ?> map, String name) {
        Object value = map.get(name);
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Integer intValue) {
            return intValue.longValue();
        }
        throw new IllegalArgumentException("Correction metadata field is not an integer: " + name);
    }

    private static String validateCase(String caseId) {
        if (caseId == null || caseId.isBlank() || caseId.length() > 64
                || !CASE_ID_PATTERN.matcher(caseId).matches()) {
            throw new IllegalArgumentException("Correction case identifier is invalid");
        }
        return caseId;
    }

    private static String digest(String input, String field) {
        if (input == null || !SHA256_PATTERN.matcher(input).matches()) {
            throw new IllegalArgumentException("Invalid SHA-256 " + field);
        }
        return input;
    }
}
