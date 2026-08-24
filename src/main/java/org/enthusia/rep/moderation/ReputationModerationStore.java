package org.enthusia.rep.moderation;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.api.ReputationBlacklist;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.enthusia.rep.api.ReputationMutationResult;
import org.enthusia.rep.api.ReputationStateSnapshot;

final class ReputationModerationStore {
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
        Map<UUID, ReputationBlacklist> blacklists = new LinkedHashMap<>();
        ConfigurationSection blacklistSection = yaml.getConfigurationSection("blacklists");
        if (blacklistSection != null) {
            for (String key : blacklistSection.getKeys(false)) {
                ReputationBlacklist value = readBlacklist(blacklistSection.getConfigurationSection(key));
                if (value == null) {
                    throw new IllegalStateException("Invalid reputation blacklist record: " + key);
                }
                blacklists.put(value.playerId(), value);
            }
        }
        LinkedHashMap<UUID, Operation> operations = new LinkedHashMap<>();
        ConfigurationSection operationSection = yaml.getConfigurationSection("operations");
        if (operationSection != null) {
            for (String key : operationSection.getKeys(false)) {
                Operation value = readOperation(operationSection.getConfigurationSection(key));
                if (value == null) {
                    throw new IllegalStateException("Invalid reputation moderation operation: " + key);
                }
                operations.put(value.operationId(), value);
            }
        }
        return new State(blacklists, operations);
    }

    void save(State state) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, ReputationBlacklist> entry : state.blacklists().entrySet()) {
            writeBlacklist(yaml.createSection("blacklists." + entry.getKey()), entry.getValue());
        }
        for (Map.Entry<UUID, Operation> entry : state.operations().entrySet()) {
            ConfigurationSection section = yaml.createSection("operations." + entry.getKey());
            Operation operation = entry.getValue();
            section.set("fingerprint", operation.fingerprint());
            section.set("status", operation.status().name());
            section.set("detail", operation.detail());
            operation.blacklist().ifPresent(value -> writeBlacklist(section.createSection("blacklist"), value));
            writeSnapshot(section.createSection("before"), operation.before());
            writeSnapshot(section.createSection("after"), operation.after());
        }
        Path parent = file.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            yaml.save(temporary.toFile());
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not persist reputation moderation state", exception);
        }
    }

    private static void writeBlacklist(ConfigurationSection section, ReputationBlacklist value) {
        section.set("player-id", value.playerId().toString());
        section.set("starts-at", value.startsAt().toEpochMilli());
        section.set("expiration-at", value.expirationAt().map(Instant::toEpochMilli).orElse(null));
        section.set("case-id", value.caseId());
        section.set("last-action-case-id", value.lastActionCaseId());
        section.set("status", value.status().name());
        section.set("revision", value.revision());
        section.set("updated-at", value.updatedAt().toEpochMilli());
    }

    private static ReputationBlacklist readBlacklist(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        try {
            UUID playerId = UUID.fromString(section.getString("player-id"));
            Optional<Instant> expiration = section.isLong("expiration-at")
                    ? Optional.of(Instant.ofEpochMilli(section.getLong("expiration-at")))
                    : Optional.empty();
            return new ReputationBlacklist(
                    playerId,
                    Instant.ofEpochMilli(section.getLong("starts-at")),
                    expiration,
                    section.getString("case-id"),
                    section.getString("last-action-case-id"),
                    ReputationBlacklist.Status.valueOf(section.getString("status")),
                    section.getLong("revision"),
                    Instant.ofEpochMilli(section.getLong("updated-at"))
            );
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static void writeSnapshot(ConfigurationSection section, ReputationStateSnapshot snapshot) {
        section.set("player-id", snapshot.playerId().toString());
        section.set("total-score", snapshot.totalScore());
        section.set("checksum", snapshot.checksum());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ReputationEntrySnapshot entry : snapshot.entries()) {
            Map<String, Object> serialized = new LinkedHashMap<>();
            serialized.put("giver-id", entry.giverId().toString());
            serialized.put("target-id", entry.targetId().toString());
            serialized.put("positive", entry.positive());
            serialized.put("category", entry.category());
            serialized.put("score-value", entry.scoreValue());
            serialized.put("created-at", entry.createdAt());
            serialized.put("last-edited-at", entry.lastEditedAt());
            entries.add(serialized);
        }
        section.set("entries", entries);
    }

    private static ReputationStateSnapshot readSnapshot(ConfigurationSection section) {
        if (section == null) {
            throw new IllegalArgumentException("snapshot section is missing");
        }
        UUID playerId = UUID.fromString(section.getString("player-id"));
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
            return new Operation(
                    operationId,
                    section.getString("fingerprint"),
                    ReputationMutationResult.Status.valueOf(section.getString("status")),
                    Optional.ofNullable(readBlacklist(section.getConfigurationSection("blacklist"))),
                    before,
                    after,
                    section.getString("detail", "")
            );
        } catch (RuntimeException exception) {
            return null;
        }
    }

    record State(Map<UUID, ReputationBlacklist> blacklists, LinkedHashMap<UUID, Operation> operations) {
        State {
            blacklists = Map.copyOf(blacklists);
            operations = new LinkedHashMap<>(operations);
        }

        static State empty() {
            return new State(Map.of(), new LinkedHashMap<>());
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
