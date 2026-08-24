package org.enthusia.rep.moderation;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.api.ReputationBlacklist;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.enthusia.rep.api.ReputationMutationResult;
import org.enthusia.rep.api.ReputationStateSnapshot;

final class ReputationModerationStore {
    private static final String BLACKLISTS_KEY = "blacklists";
    private static final String OPERATIONS_KEY = "operations";
    private static final String RECONCILIATION_PENDING_KEY = "reconciliation-pending";
    private static final String PLAYER_ID_KEY = "player-id";
    private static final String STATUS_KEY = "status";

    private final Path file;

    ReputationModerationStore(Path file) {
        this.file = file;
    }

    State load() {
        if (!Files.exists(file)) {
            return State.empty();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Reputation moderation state is unreadable", exception);
        }
        return new State(
                readBlacklists(yaml.getConfigurationSection(BLACKLISTS_KEY)),
                readOperations(yaml.getConfigurationSection(OPERATIONS_KEY)),
                readReconciliationPending(yaml)
        );
    }

    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private static Map<UUID, ReputationBlacklist> readBlacklists(ConfigurationSection section) {
        // This ordered map is a method-local YAML assembly object and is never accessed concurrently.
        if (section == null) {
            return Map.of();
        }
        Map<UUID, ReputationBlacklist> blacklists = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            ReputationBlacklist value = readBlacklist(section.getConfigurationSection(key));
            if (value == null) {
                throw new IllegalStateException("Invalid reputation blacklist record: " + key);
            }
            blacklists.put(value.playerId(), value);
        }
        return blacklists;
    }

    private static LinkedHashMap<UUID, Operation> readOperations(ConfigurationSection section) {
        LinkedHashMap<UUID, Operation> operations = new LinkedHashMap<>();
        if (section == null) {
            return operations;
        }
        for (String key : section.getKeys(false)) {
            Operation value = readOperation(section.getConfigurationSection(key));
            if (value == null) {
                throw new IllegalStateException("Invalid reputation moderation operation: " + key);
            }
            operations.put(value.operationId(), value);
        }
        return operations;
    }

    private static Set<UUID> readReconciliationPending(YamlConfiguration yaml) {
        if (!yaml.contains(RECONCILIATION_PENDING_KEY)) {
            return Set.of();
        }
        if (!yaml.isList(RECONCILIATION_PENDING_KEY)) {
            throw new IllegalStateException("Invalid reputation reconciliation-pending state");
        }
        Set<UUID> pending = new LinkedHashSet<>();
        try {
            for (String value : yaml.getStringList(RECONCILIATION_PENDING_KEY)) {
                pending.add(UUID.fromString(value));
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid reputation reconciliation-pending player id", exception);
        }
        return pending;
    }

    void save(State state) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, ReputationBlacklist> entry : state.blacklists().entrySet()) {
            writeBlacklist(yaml.createSection(BLACKLISTS_KEY + "." + entry.getKey()), entry.getValue());
        }
        for (Map.Entry<UUID, Operation> entry : state.operations().entrySet()) {
            ConfigurationSection section = yaml.createSection(OPERATIONS_KEY + "." + entry.getKey());
            Operation operation = entry.getValue();
            section.set("fingerprint", operation.fingerprint());
            section.set(STATUS_KEY, operation.status().name());
            section.set("detail", operation.detail());
            operation.blacklist().ifPresent(value -> writeBlacklist(section.createSection("blacklist"), value));
            writeSnapshot(section.createSection("before"), operation.before());
            writeSnapshot(section.createSection("after"), operation.after());
        }
        yaml.set(RECONCILIATION_PENDING_KEY, state.reconciliationPending().stream()
                .map(UUID::toString)
                .sorted()
                .toList());
        persistAtomically(yaml);
    }

    private void persistAtomically(YamlConfiguration yaml) {
        Path parent = file.getParent();
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            yaml.save(temporary.toFile());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            moveIntoPlace(temporary);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw new IllegalStateException("Could not persist reputation moderation state", exception);
        }
    }

    private void moveIntoPlace(Path temporary) throws IOException {
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeBlacklist(ConfigurationSection section, ReputationBlacklist value) {
        section.set(PLAYER_ID_KEY, value.playerId().toString());
        section.set("starts-at", value.startsAt().toEpochMilli());
        section.set("expiration-at", value.expirationAt().map(Instant::toEpochMilli).orElse(null));
        section.set("case-id", value.caseId());
        section.set("last-action-case-id", value.lastActionCaseId());
        section.set(STATUS_KEY, value.status().name());
        section.set("revision", value.revision());
        section.set("updated-at", value.updatedAt().toEpochMilli());
    }

    private static ReputationBlacklist readBlacklist(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        try {
            UUID playerId = UUID.fromString(section.getString(PLAYER_ID_KEY));
            Optional<Instant> expiration = section.contains("expiration-at")
                    ? Optional.of(Instant.ofEpochMilli(section.getLong("expiration-at")))
                    : Optional.empty();
            return new ReputationBlacklist(
                    playerId,
                    Instant.ofEpochMilli(section.getLong("starts-at")),
                    expiration,
                    section.getString("case-id"),
                    section.getString("last-action-case-id"),
                    ReputationBlacklist.Status.valueOf(section.getString(STATUS_KEY)),
                    section.getLong("revision"),
                    Instant.ofEpochMilli(section.getLong("updated-at"))
            );
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static void writeSnapshot(ConfigurationSection section, ReputationStateSnapshot snapshot) {
        section.set(PLAYER_ID_KEY, snapshot.playerId().toString());
        section.set("total-score", snapshot.totalScore());
        section.set("checksum", snapshot.checksum());
        List<Map<String, Object>> entries = snapshot.entries().stream()
                .map(ReputationModerationStore::serializeEntry)
                .toList();
        section.set("entries", entries);
    }

    private static Map<String, Object> serializeEntry(ReputationEntrySnapshot entry) {
        return Map.of(
                "giver-id", entry.giverId().toString(),
                "target-id", entry.targetId().toString(),
                "positive", entry.positive(),
                "category", entry.category(),
                "score-value", entry.scoreValue(),
                "created-at", entry.createdAt(),
                "last-edited-at", entry.lastEditedAt()
        );
    }

    private static ReputationStateSnapshot readSnapshot(ConfigurationSection section) {
        if (section == null) {
            throw new IllegalArgumentException("snapshot section is missing");
        }
        UUID playerId = UUID.fromString(section.getString(PLAYER_ID_KEY));
        List<ReputationEntrySnapshot> entries = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("entries")) {
            entries.add(new ReputationEntrySnapshot(
                    UUID.fromString(String.valueOf(raw.get("giver-id"))),
                    UUID.fromString(String.valueOf(raw.get("target-id"))),
                    Boolean.parseBoolean(String.valueOf(raw.get("positive"))),
                    String.valueOf(raw.get("category")),
                    Integer.parseInt(String.valueOf(raw.get("score-value"))),
                    Long.parseLong(String.valueOf(raw.get("created-at"))),
                    Long.parseLong(String.valueOf(raw.get("last-edited-at")))
            ));
        }
        int totalScore = section.getInt("total-score");
        ReputationStateSnapshot snapshot = new ReputationStateSnapshot(
                playerId,
                totalScore,
                entries,
                section.getString("checksum")
        );
        String computed = ReputationSnapshotFactory.checksum(playerId, totalScore, entries);
        if (!computed.equals(snapshot.checksum())) {
            throw new IllegalArgumentException("snapshot checksum does not match persisted content");
        }
        return snapshot;
    }

    private static Operation readOperation(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        try {
            UUID operationId = UUID.fromString(section.getName());
            ReputationStateSnapshot before = readSnapshot(section.getConfigurationSection("before"));
            ReputationStateSnapshot after = readSnapshot(section.getConfigurationSection("after"));
            ConfigurationSection blacklistSection = section.getConfigurationSection("blacklist");
            ReputationBlacklist parsedBlacklist = readBlacklist(blacklistSection);
            if (blacklistSection != null && parsedBlacklist == null) {
                return null;
            }
            return new Operation(
                    operationId,
                    section.getString("fingerprint"),
                    ReputationMutationResult.Status.valueOf(section.getString(STATUS_KEY)),
                    Optional.ofNullable(parsedBlacklist),
                    before,
                    after,
                    section.getString("detail", "")
            );
        } catch (RuntimeException exception) {
            return null;
        }
    }

    record State(
            Map<UUID, ReputationBlacklist> blacklists,
            LinkedHashMap<UUID, Operation> operations,
            Set<UUID> reconciliationPending
    ) {
        State {
            blacklists = Map.copyOf(blacklists);
            operations = new LinkedHashMap<>(operations);
            reconciliationPending = Set.copyOf(reconciliationPending);
        }

        static State empty() {
            return new State(Map.of(), new LinkedHashMap<>(), Set.of());
        }
    }

    record Operation(
            UUID operationId,
            String fingerprint,
            ReputationMutationResult.Status status,
            Optional<ReputationBlacklist> blacklist,
            ReputationStateSnapshot before,
            ReputationStateSnapshot after,
            String detail
    ) {
    }
}
