package org.enthusia.rep.moderation;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.enthusia.rep.api.ReputationStateSnapshot;

/**
 * Durable, provider-owned preparation journal for exact-entry correction intents.
 *
 * <p>No reputation mutation, Staff authorization, or completion status exists here.
 * This is an independent prerequisite for a future serialized, provider-owned
 * correction transaction. A prepared intent must never be called a committed
 * correction receipt or used to mark a Staff remedy satisfied.</p>
 */
@SuppressWarnings("PMD.UseConcurrentHashMap") // Ordered, method-local YAML assembly maps; all shared access is guarded.
public final class ReputationCorrectionIntentJournal {
    private static final int MAX_PREPARED = 4096;
    private static final String INTENTS = "prepared-intents";
    private static final String SUBJECT_ID = "subject-id";
    private final Path file;
    private final Object journalLock = new Object();
    private final Map<UUID, Prepared> prepared;
    private boolean storageUncertain;

    public ReputationCorrectionIntentJournal(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        this.prepared = new LinkedHashMap<>(load(file));
    }

    public Prepared prepare(
            UUID operationId,
            UUID reviewerId,
            String caseId,
            ReputationStateSnapshot observed,
            UUID subjectId,
            String expectedChecksum,
            List<ReputationEntrySnapshot> exactEntries,
            Instant requestedAt
    ) {
        synchronized (journalLock) {
            return prepareLocked(operationId, reviewerId, caseId, observed, subjectId,
                    expectedChecksum, exactEntries, requestedAt);
        }
    }

    private Prepared prepareLocked(
            UUID operationId, UUID reviewerId, String caseId, ReputationStateSnapshot observed,
            UUID subjectId, String expectedChecksum, List<ReputationEntrySnapshot> exactEntries,
            Instant requestedAt
    ) {
        requireHealthyStorage();
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(reviewerId, "reviewerId");
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(expectedChecksum, "expectedChecksum");
        Objects.requireNonNull(exactEntries, "exactEntries");
        Objects.requireNonNull(requestedAt, "requestedAt");
        String normalizedCase = checkedCase(caseId);
        Prepared prior = prepared.get(operationId);
        if (prior != null) {
            requireIdenticalRequest(prior, reviewerId, normalizedCase, observed,
                    subjectId, expectedChecksum, exactEntries);
            return prior;
        }
        if (prepared.size() >= MAX_PREPARED) {
            throw new IllegalStateException("Correction intent journal capacity reached; operator review required");
        }
        var selection = ReputationCorrectionPreflight.select(
                observed, subjectId, expectedChecksum, exactEntries);
        Prepared candidate = new Prepared(operationId, reviewerId, normalizedCase, subjectId,
                observed.checksum(), observed, selection.entries(),
                selection.expectedTotalAfterRemoval(), requestedAt);
        persistPrepared(candidate);
        return candidate;
    }

    private void persistPrepared(Prepared candidate) {
        Map<UUID, Prepared> next = new LinkedHashMap<>(prepared);
        next.put(candidate.operationId(), candidate);
        try {
            save(file, next);
        } catch (RuntimeException failure) {
            reconcileFailedWrite(failure);
            throw failure;
        }
        prepared.put(candidate.operationId(), candidate); // Never advertise an unpersisted intent.
    }

    private void reconcileFailedWrite(RuntimeException failure) {
        // A replace may succeed before directory fsync fails. Reload disk first,
        // rather than accepting a reused operation UUID against stale memory.
        try {
            Map<UUID, Prepared> persisted = load(file);
            prepared.clear();
            prepared.putAll(persisted);
        } catch (RuntimeException unreadable) {
            storageUncertain = true;
            failure.addSuppressed(unreadable);
        }
    }

    private static void requireIdenticalRequest(
            Prepared prior, UUID reviewerId, String caseId, ReputationStateSnapshot observed,
            UUID subjectId, String expectedChecksum, List<ReputationEntrySnapshot> exactEntries
    ) {
        if (!prior.reviewerId().equals(reviewerId)
                || !prior.caseId().equals(caseId)
                || !prior.before().equals(observed)
                || !prior.subjectId().equals(subjectId)
                || !prior.expectedChecksum().equals(expectedChecksum)
                || !prior.exactEntries().equals(exactEntries)) {
            throw new IllegalStateException("Correction operation ID was reused with a different request");
        }
    }

    private void requireHealthyStorage() {
        if (storageUncertain) {
            throw new IllegalStateException("Correction intent storage requires operator reconciliation");
        }
    }

    public Optional<Prepared> findOperation(UUID operationId) {
        synchronized (journalLock) {
            requireHealthyStorage();
            return Optional.ofNullable(prepared.get(Objects.requireNonNull(operationId, "operationId")));
        }
    }

    private static String checkedCase(String caseId) {
        if (caseId == null || caseId.isBlank() || caseId.length() > 64
                || !caseId.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException("Correction case ID must be a safe 1-64 character identifier");
        }
        return caseId;
    }

    private static Map<UUID, Prepared> load(Path file) {
        if (!Files.exists(file)) {
            return Map.of();
        }
        YamlConfiguration yaml = readYaml(file);
        ConfigurationSection intents = yaml.getConfigurationSection(INTENTS);
        // A missing file is a fresh journal; an existing rootless file can be a
        // truncated journal and must never be treated as an empty history.
        if (intents == null) {
            throw new IllegalStateException("Existing correction intent journal lacks its required root");
        }
        if (intents.getKeys(false).size() > MAX_PREPARED) {
            throw new IllegalStateException("Correction intent journal exceeds permitted capacity");
        }
        // Ordered map here is a local YAML assembly object, not a concurrently shared map.
        Map<UUID, Prepared> result = new LinkedHashMap<>();
        for (String id : intents.getKeys(false)) {
            Prepared intent = readPrepared(id, intents);
            if (result.putIfAbsent(intent.operationId(), intent) != null) {
                throw new IllegalStateException("Duplicate correction operation");
            }
        }
        return result;
    }

    private static YamlConfiguration readYaml(Path file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Correction intent journal cannot be read", exception);
        }
        return yaml;
    }

    private static Prepared readPrepared(String id, ConfigurationSection intents) {
        try {
            ConfigurationSection section = Objects.requireNonNull(
                    intents.getConfigurationSection(id), "missing intent section");
            UUID operationId = UUID.fromString(id);
            ReputationStateSnapshot before = readSnapshot(
                    Objects.requireNonNull(section.getConfigurationSection("before"), "missing snapshot"));
            List<ReputationEntrySnapshot> exact = readEntries(section.getMapList("selected"));
            UUID subjectId = UUID.fromString(section.getString(SUBJECT_ID));
            String checksum = section.getString("expected-checksum");
            var selection = ReputationCorrectionPreflight.select(before, subjectId, checksum, exact);
            // Bukkit's getInt/getLong return zero for missing keys. Zero can be
            // a valid post-correction score, so explicit, typed presence checks
            // are needed to avoid accepting truncated metadata on replay.
            if (!section.isString("reviewer-id")
                    || !(section.get("expected-total") instanceof Number)
                    || !(section.get("prepared-at") instanceof Number)) {
                throw new IllegalStateException("Correction intent is missing required typed metadata");
            }
            Prepared intent = new Prepared(operationId, UUID.fromString(section.getString("reviewer-id")),
                    checkedCase(section.getString("case-id")), subjectId, checksum,
                    before, selection.entries(), section.getInt("expected-total"),
                    Instant.ofEpochMilli(section.getLong("prepared-at")));
            if (intent.expectedTotalAfterRemoval() != selection.expectedTotalAfterRemoval()) {
                throw new IllegalStateException("Persisted correction total has changed");
            }
            return intent;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Correction intent journal has malformed record " + id, exception);
        }
    }

    private static void save(Path file, Map<UUID, Prepared> values) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Prepared intent : values.values()) {
            writePrepared(yaml, intent);
        }
        persistYaml(file, yaml);
    }

    private static void writePrepared(YamlConfiguration yaml, Prepared intent) {
        ConfigurationSection item = yaml.createSection(INTENTS + "." + intent.operationId());
        item.set("reviewer-id", intent.reviewerId().toString());
        item.set("case-id", intent.caseId());
        item.set(SUBJECT_ID, intent.subjectId().toString());
        item.set("expected-checksum", intent.expectedChecksum());
        item.set("expected-total", intent.expectedTotalAfterRemoval());
        item.set("prepared-at", intent.preparedAt().toEpochMilli());
        ConfigurationSection before = item.createSection("before");
        before.set(SUBJECT_ID, intent.before().playerId().toString());
        before.set("total-score", intent.before().totalScore());
        before.set("checksum", intent.before().checksum());
        before.set("entries", intent.before().entries().stream()
                .map(ReputationCorrectionIntentJournal::writeEntry).toList());
        item.set("selected", intent.exactEntries().stream()
                .map(ReputationCorrectionIntentJournal::writeEntry).toList());
    }

    private static void persistYaml(Path file, YamlConfiguration yaml) {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Path directory = file.getParent();
        try {
            if (directory != null) {
                Files.createDirectories(directory);
            }
            yaml.save(temporary.toFile());
            forceFile(temporary);
            replaceFile(temporary, file);
            forceDirectory(directory);
        } catch (IOException exception) {
            cleanupAfterFailure(temporary, exception);
            throw new IllegalStateException("Could not durably persist correction intent", exception);
        }
    }

    private static void forceFile(Path temporary) throws IOException {
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void replaceFile(Path temporary, Path file) throws IOException {
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (AccessDeniedException exception) {
            if (java.io.File.separatorChar != '\\' || !Files.isDirectory(directory)) {
                throw exception;
            }
        }
    }

    private static void cleanupAfterFailure(Path temporary, IOException failure) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static Map<String, Object> writeEntry(ReputationEntrySnapshot entry) {
        return Map.of(
                "giver-id", entry.giverId().toString(),
                "target-id", entry.targetId().toString(),
                "positive", entry.positive(),
                "category", entry.category(),
                "score-value", entry.scoreValue(),
                "created-at", entry.createdAt(),
                "last-edited-at", entry.lastEditedAt());
    }

    private static ReputationStateSnapshot readSnapshot(ConfigurationSection section) {
        UUID subject = UUID.fromString(section.getString(SUBJECT_ID));
        return new ReputationStateSnapshot(subject, section.getInt("total-score"),
                readEntries(section.getMapList("entries")), section.getString("checksum"));
    }

    private static List<ReputationEntrySnapshot> readEntries(List<Map<?, ?>> values) {
        return values.stream().map(value -> new ReputationEntrySnapshot(
                UUID.fromString(String.valueOf(value.get("giver-id"))),
                UUID.fromString(String.valueOf(value.get("target-id"))),
                Boolean.parseBoolean(String.valueOf(value.get("positive"))),
                String.valueOf(value.get("category")),
                Integer.parseInt(String.valueOf(value.get("score-value"))),
                Long.parseLong(String.valueOf(value.get("created-at"))),
                Long.parseLong(String.valueOf(value.get("last-edited-at"))))).toList();
    }

    /** A restart-safe PREPARATION record, not a mutation, authorization or completion receipt. */
    public record Prepared(
            UUID operationId,
            UUID reviewerId,
            String caseId,
            UUID subjectId,
            String expectedChecksum,
            ReputationStateSnapshot before,
            List<ReputationEntrySnapshot> exactEntries,
            int expectedTotalAfterRemoval,
            Instant preparedAt
    ) {
        public Prepared {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(reviewerId, "reviewerId");
            checkedCase(caseId);
            Objects.requireNonNull(subjectId, "subjectId");
            Objects.requireNonNull(expectedChecksum, "expectedChecksum");
            Objects.requireNonNull(before, "before");
            exactEntries = List.copyOf(Objects.requireNonNull(exactEntries, "exactEntries"));
            Objects.requireNonNull(preparedAt, "preparedAt");
        }
    }
}
