package org.enthusia.rep.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.analytics.ReputationChangeRecord;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepService;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class YamlPluginDataStore implements PluginDataStore {
    private static final int DATA_VERSION = 6;

    private final File file;
    private final Logger logger;

    public YamlPluginDataStore(CommendPlugin plugin) {
        this(plugin.getDataFolder(), plugin.getLogger());
    }

    YamlPluginDataStore(File dataFolder, Logger logger) {
        this.file = new File(Objects.requireNonNull(dataFolder, "dataFolder"), "data.yml");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public PluginDataSnapshot load() {
        if (!file.exists()) {
            return PluginDataSnapshot.EMPTY;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        return new PluginDataSnapshot(
                loadScores(config),
                loadCommendations(config),
                loadMappedEntries(config, "removed", RepService.RemovedRep::fromMap),
                loadStalkEntries(config),
                loadMappedEntries(config, "reputationChanges", ReputationChangeRecord::fromMap),
                loadMappedEntries(config, "suspiciousCases", RepService.SuspiciousRepCase::fromMap),
                loadRemovalCooldowns(config),
                loadAlertPreferences(config)
        );
    }

    private Map<UUID, Integer> loadScores(YamlConfiguration config) {
        Map<UUID, Integer> scores = new LinkedHashMap<>();
        ConfigurationSection players = config.getConfigurationSection("players");
        if (players == null) {
            return scores;
        }
        for (String key : players.getKeys(false)) {
            try {
                scores.put(UUID.fromString(key), players.getInt(key + ".score", 0));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return scores;
    }

    private List<Commendation> loadCommendations(YamlConfiguration config) {
        List<Commendation> commendations = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("commendations");
        if (section == null) {
            return commendations;
        }
        for (String key : section.getKeys(false)) {
            Commendation commendation = Commendation.fromSection(section.getConfigurationSection(key));
            if (commendation != null) {
                commendations.add(commendation);
            }
        }
        return commendations;
    }

    private <T> List<T> loadMappedEntries(YamlConfiguration config, String path,
                                          Function<Map<?, ?>, T> parser) {
        List<T> entries = new ArrayList<>();
        for (Map<?, ?> rawEntry : config.getMapList(path)) {
            T entry = parser.apply(rawEntry);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private List<PluginDataSnapshot.RemovalCooldownEntry> loadRemovalCooldowns(YamlConfiguration config) {
        List<PluginDataSnapshot.RemovalCooldownEntry> entries = new ArrayList<>();
        for (Map<?, ?> rawEntry : config.getMapList("removalCooldowns")) {
            PluginDataSnapshot.RemovalCooldownEntry entry = parseRemovalCooldown(rawEntry);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private PluginDataSnapshot.RemovalCooldownEntry parseRemovalCooldown(Map<?, ?> rawEntry) {
        try {
            UUID giverId = UUID.fromString(String.valueOf(rawEntry.get("giver")));
            UUID targetId = UUID.fromString(String.valueOf(rawEntry.get("target")));
            Object rawRemovedAt = rawEntry.get("removedAt");
            long removedAt = rawRemovedAt instanceof Number value
                    ? value.longValue()
                    : Long.parseLong(String.valueOf(rawRemovedAt));
            return new PluginDataSnapshot.RemovalCooldownEntry(giverId, targetId, removedAt);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<UUID, Boolean> loadAlertPreferences(YamlConfiguration config) {
        Map<UUID, Boolean> preferences = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("playerSettings");
        if (section == null) {
            return preferences;
        }
        for (String key : section.getKeys(false)) {
            loadAlertPreference(section, key, preferences);
        }
        return preferences;
    }

    private void loadAlertPreference(ConfigurationSection section, String key,
                                     Map<UUID, Boolean> preferences) {
        try {
            UUID playerId = UUID.fromString(key);
            String path = key + ".repTradingAlertsEnabled";
            if (section.isSet(path)) {
                preferences.put(playerId, section.getBoolean(path));
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private List<PluginDataSnapshot.StalkEntry> loadStalkEntries(YamlConfiguration config) {
        List<PluginDataSnapshot.StalkEntry> entries = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("stalks");
        if (section == null) {
            return entries;
        }
        for (String key : section.getKeys(false)) {
            PluginDataSnapshot.StalkEntry entry = parseStalkEntry(section, key);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private PluginDataSnapshot.StalkEntry parseStalkEntry(ConfigurationSection section, String key) {
        try {
            UUID stalkerId = UUID.fromString(section.getString(key + ".stalker"));
            UUID targetId = UUID.fromString(section.getString(key + ".target"));
            long expiresAt = section.getLong(key + ".expiresAt");
            return new PluginDataSnapshot.StalkEntry(stalkerId, targetId, expiresAt);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public boolean save(PluginDataSnapshot snapshot) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("dataVersion", DATA_VERSION);
        writeScores(config, snapshot.scores());
        writeCommendations(config, snapshot.commendations());
        config.set("removed", serialize(snapshot.removedEntries(), RepService.RemovedRep::serialize));
        config.set("reputationChanges", serialize(
                snapshot.reputationChanges(), ReputationChangeRecord::serialize));
        config.set("suspiciousCases", serialize(
                snapshot.suspiciousCases(), RepService.SuspiciousRepCase::serialize));
        config.set("removalCooldowns", serializeRemovalCooldowns(snapshot.removalCooldowns()));
        writeAlertPreferences(config, snapshot.repTradingAlertPreferences());
        writeStalkEntries(config, snapshot.stalkEntries());
        return saveConfiguration(config);
    }

    private void writeScores(YamlConfiguration config, Map<UUID, Integer> scores) {
        for (Map.Entry<UUID, Integer> entry : scores.entrySet()) {
            config.set("players." + entry.getKey() + ".score", entry.getValue());
        }
    }

    private void writeCommendations(YamlConfiguration config, List<Commendation> commendations) {
        for (int index = 0; index < commendations.size(); index++) {
            config.createSection("commendations." + index, commendations.get(index).serialize());
        }
    }

    private <T> List<Map<String, Object>> serialize(List<T> entries,
                                                    Function<T, Map<String, Object>> serializer) {
        return entries.stream().map(serializer).toList();
    }

    private List<Map<String, Object>> serializeRemovalCooldowns(
            List<PluginDataSnapshot.RemovalCooldownEntry> entries) {
        return entries.stream().map(this::serializeRemovalCooldown).toList();
    }

    private Map<String, Object> serializeRemovalCooldown(PluginDataSnapshot.RemovalCooldownEntry entry) {
        Map<String, Object> serialized = new LinkedHashMap<>();
        serialized.put("giver", entry.giverId().toString());
        serialized.put("target", entry.targetId().toString());
        serialized.put("removedAt", entry.removedAt());
        return serialized;
    }

    private void writeAlertPreferences(YamlConfiguration config, Map<UUID, Boolean> preferences) {
        for (Map.Entry<UUID, Boolean> entry : preferences.entrySet()) {
            config.set("playerSettings." + entry.getKey() + ".repTradingAlertsEnabled", entry.getValue());
        }
    }

    private void writeStalkEntries(YamlConfiguration config, List<PluginDataSnapshot.StalkEntry> entries) {
        for (int index = 0; index < entries.size(); index++) {
            PluginDataSnapshot.StalkEntry entry = entries.get(index);
            String path = "stalks." + index;
            config.set(path + ".stalker", entry.stalkerId().toString());
            config.set(path + ".target", entry.targetId().toString());
            config.set(path + ".expiresAt", entry.expiresAt());
        }
    }

    private boolean saveConfiguration(YamlConfiguration config) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            logger.warning("Could not create plugin data directory.");
            return false;
        }
        File temporary = new File(parent, file.getName() + ".tmp");
        try {
            config.save(temporary);
            replaceDataFile(temporary);
            return true;
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Failed to save data.yml.", exception);
            cleanTemporaryFile(temporary);
            return false;
        }
    }

    private void replaceDataFile(File temporary) throws IOException {
        try {
            Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void cleanTemporaryFile(File temporary) {
        if (temporary.exists() && !temporary.delete()) {
            temporary.deleteOnExit();
        }
    }
}
