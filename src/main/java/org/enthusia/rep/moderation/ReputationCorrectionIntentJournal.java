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
public final class ReputationCorrectionIntentJournal {
    private static final int MAX_PREPARED = 4096;
    private static final String INTENTS = "prepared-intents";
    private final Path file;
    private final Map<UUID, Prepared> prepared;

    public ReputationCorrectionIntentJournal(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        this.prepared = new LinkedHashMap<>(load(file));
    }

    public synchronized Prepared prepare(
            UUID operationId,
            UUID reviewerId,
            String caseId,
            ReputationStateSnapshot observed,
            UUID subjectId,
            String expectedChecksum,
            List<ReputationEntrySnapshot> exactEntries,
            Instant requestedAt
    ) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(reviewerId, "reviewerId");
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(expectedChecksum, "expectedChecksum");
        Objects.requireNonNull(exactEntries, "exactEntries");
        Objects.requireNonNull(requestedAt, "requestedAt");
        String checkedCase = checkedCase(caseId);
        Prepared prior = prepared.get(operationId);
        if (prior != null) {
            if (!prior.reviewerId().equals(reviewerId)
                    || !prior.caseId().equals(checkedCase)
                    || !prior.before().equals(observed)
                    || !prior.subjectId().equals(subjectId)
                    || !prior.expectedChecksum().equals(expectedChecksum)
                    || !prior.exactEntries().equals(exactEntries)) {
                throw new IllegalStateException("Correction operation ID was reused with a different request");
            }
            return prior; // A retry does not rewrite the original timestamp or create a new intent.
        }
        if (prepared.size() >= MAX_PREPARED) {
            throw new IllegalStateException("Correction intent journal capacity reached; operator review required");
        }
        var selection = ReputationCorrectionPreflight.select(
                observed, subjectId, expectedChecksum, exactEntries);
        Prepared candidate = new Prepared(
                operationId, reviewerId, checkedCase, subjectId,
                observed.checksum(), observed, selection.entries(),
                selection.expectedTotalAfterRemoval(), requestedAt);
        Map<UUID, Prepared> next = new LinkedHashMap<>(prepared);
        next.put(operationId, candidate);
        save(file, next);
        prepared.put(operationId, candidate); // Never advertise an unpersisted intent.
        return candidate;
    }

    public synchronized Optional<Prepared> findOperation(UUID operationId) {
        return Optional.ofNullable(prepared.get(Objects.requireNonNull(operationId, "operationId")));
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
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Correction intent journal cannot be read", exception);
        }
        Map<UUID, Prepared> result = new LinkedHashMap<>();
        ConfigurationSection intents = yaml.getConfigurationSection(INTENTS);
        if (yaml.contains(INTENTS) && intents == null) {
            throw new IllegalStateException("Correction intent journal has invalid root");
        }
        if (intents != null) {
            if (intents.getKeys(false).size() > MAX_PREPARED) {
                throw new IllegalStateException("Correction intent journal exceeds permitted capacity");
            }
            for (String id : intents.getKeys(false)) {
                try {
                    ConfigurationSection section = Objects.requireNonNull(
                            intents.getConfigurationSection(id), "missing intent section");
                    UUID operationId = UUID.fromString(id);
                    ReputationStateSnapshot before = readSnapshot(
                            Objects.requireNonNull(section.getConfigurationSection("before"), "missing snapshot"));
                    List<ReputationEntrySnapshot> exact = readEntries(section.getMapList("selected"));
                    UUID subjectId = UUID.fromString(section.getString("subject-id"));
                    String expectedChecksum = section.getString("expected-checksum");
                    var selected = ReputationCorrectionPreflight.select(
                            before, subjectId, expectedChecksum, exact);
                    Prepared intent = new Prepared(
                            operationId, UUID.fromString(section.getString("reviewer-id")),
                            checkedCase(section.getString("case-id")), subjectId, expectedChecksum,
                            before, selected.entries(), section.getInt("expected-total"),
                            Instant.ofEpochMilli(section.getLong("prepared-at")));
                    if (intent.expectedTotalAfterRemoval() != selected.expectedTotalAfterRemoval()) {
                        throw new IllegalStateException("Persisted correction total has changed");
                    }
                    if (result.putIfAbsent(operationId, intent) != null) {
                        throw new IllegalStateException("Duplicate correction operation");
                    }
                } catch (RuntimeException exception) {
                    throw new IllegalStateException("Correction intent journal has malformed record " + id, exception);
                }
            }
        }
        return result;
    }

    private static void save(Path file, Map<UUID, Prepared> values) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Prepared intent : values.values()) {
            ConfigurationSection item = yaml.createSection(INTENTS + "." + intent.operationId());
            item.set("reviewer-id", intent.reviewerId().toString());
            item.set("case-id", intent.caseId());
            item.set("subject-id", intent.subjectId().toString());
            item.set("expected-checksum", intent.expectedChecksum());
            item.set("expected-total", intent.expectedTotalAfterRemoval());
            item.set("prepared-at", intent.preparedAt().toEpochMilli());
            ConfigurationSection before = item.createSection("before");
            before.set("subject-id", intent.before().playerId().toString());
            before.set("total-score", intent.before().totalScore());
            before.set("checksum", intent.before().checksum());
            before.set("entries", intent.before().entries().stream()
                    .map(ReputationCorrectionIntentJournal::writeEntry).toList());
            item.set("selected", intent.exactEntries().stream()
                    .map(ReputationCorrectionIntentJournal::writeEntry).toList());
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Path directory = file.getParent();
        try {
            if (directory != null) {
                Files.createDirectories(directory);
            }
            yaml.save(temporary.toFile());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            if (directory != null) {
                try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                    channel.force(true);
                } catch (AccessDeniedException exception) {
                    if (java.io.File.separatorChar != '\\' || !Files.isDirectory(directory)) {
                        throw exception;
                    }
                }
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw new IllegalStateException("Could not durably persist correction intent", exception);
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
        UUID subject = UUID.fromString(section.getString("subject-id"));
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
