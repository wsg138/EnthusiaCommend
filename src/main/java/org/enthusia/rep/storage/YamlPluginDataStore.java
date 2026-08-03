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
import java.util.UUID;

public final class YamlPluginDataStore implements PluginDataStore {
    private static final int DATA_VERSION = 6;

    private final CommendPlugin plugin;
    private final File file;

    public YamlPluginDataStore(CommendPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    @Override
    public PluginDataSnapshot load() {
        if (!file.exists()) {
            return PluginDataSnapshot.EMPTY;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        Map<UUID, Integer> scores = new LinkedHashMap<>();
        List<Commendation> commendations = new ArrayList<>();
        List<RepService.RemovedRep> removedEntries = new ArrayList<>();
        List<PluginDataSnapshot.StalkEntry> stalkEntries = new ArrayList<>();
        List<ReputationChangeRecord> reputationChanges = new ArrayList<>();
        List<RepService.SuspiciousRepCase> suspiciousCases = new ArrayList<>();
        List<PluginDataSnapshot.RemovalCooldownEntry> removalCooldowns = new ArrayList<>();
        Map<UUID, Boolean> alertPreferences = new LinkedHashMap<>();

        ConfigurationSection players = config.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    scores.put(uuid, players.getInt(key + ".score", 0));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        ConfigurationSection commendationSection = config.getConfigurationSection("commendations");
        if (commendationSection != null) {
            for (String key : commendationSection.getKeys(false)) {
                Commendation commendation = Commendation.fromSection(commendationSection.getConfigurationSection(key));
                if (commendation != null) {
                    commendations.add(commendation);
                }
            }
        }

        for (Map<?, ?> rawRemoved : config.getMapList("removed")) {
            RepService.RemovedRep removed = RepService.RemovedRep.fromMap(rawRemoved);
            if (removed != null) {
                removedEntries.add(removed);
            }
        }

        for (Map<?, ?> rawChange : config.getMapList("reputationChanges")) {
            ReputationChangeRecord change = ReputationChangeRecord.fromMap(rawChange);
            if (change != null) {
                reputationChanges.add(change);
            }
        }

        for (Map<?, ?> rawCase : config.getMapList("suspiciousCases")) {
            RepService.SuspiciousRepCase caseData = RepService.SuspiciousRepCase.fromMap(rawCase);
            if (caseData != null) {
                suspiciousCases.add(caseData);
            }
        }

        for (Map<?, ?> rawCooldown : config.getMapList("removalCooldowns")) {
            try {
                UUID giverId = UUID.fromString(String.valueOf(rawCooldown.get("giver")));
                UUID targetId = UUID.fromString(String.valueOf(rawCooldown.get("target")));
                long removedAt = rawCooldown.get("removedAt") instanceof Number value
                        ? value.longValue() : Long.parseLong(String.valueOf(rawCooldown.get("removedAt")));
                removalCooldowns.add(new PluginDataSnapshot.RemovalCooldownEntry(giverId, targetId, removedAt));
            } catch (Exception ignored) {
            }
        }

        ConfigurationSection preferenceSection = config.getConfigurationSection("playerSettings");
        if (preferenceSection != null) {
            for (String key : preferenceSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    String path = key + ".repTradingAlertsEnabled";
                    if (preferenceSection.isSet(path)) {
                        alertPreferences.put(uuid, preferenceSection.getBoolean(path));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        ConfigurationSection stalkSection = config.getConfigurationSection("stalks");
        if (stalkSection != null) {
            for (String key : stalkSection.getKeys(false)) {
                try {
                    UUID stalkerId = UUID.fromString(stalkSection.getString(key + ".stalker"));
                    UUID targetId = UUID.fromString(stalkSection.getString(key + ".target"));
                    long expiresAt = stalkSection.getLong(key + ".expiresAt");
                    stalkEntries.add(new PluginDataSnapshot.StalkEntry(stalkerId, targetId, expiresAt));
                } catch (Exception ignored) {
                }
            }
        }

        return new PluginDataSnapshot(scores, commendations, removedEntries, stalkEntries, reputationChanges,
                suspiciousCases, removalCooldowns, alertPreferences);
    }

    @Override
    public boolean save(PluginDataSnapshot snapshot) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("dataVersion", DATA_VERSION);

        for (Map.Entry<UUID, Integer> entry : snapshot.scores().entrySet()) {
            config.set("players." + entry.getKey() + ".score", entry.getValue());
        }

        int commendationIndex = 0;
        for (Commendation commendation : snapshot.commendations()) {
            config.createSection("commendations." + commendationIndex++, commendation.serialize());
        }

        List<Map<String, Object>> removed = new ArrayList<>();
        for (RepService.RemovedRep entry : snapshot.removedEntries()) {
            removed.add(entry.serialize());
        }
        config.set("removed", removed);

        List<Map<String, Object>> reputationChanges = new ArrayList<>();
        for (ReputationChangeRecord entry : snapshot.reputationChanges()) {
            reputationChanges.add(entry.serialize());
        }
        config.set("reputationChanges", reputationChanges);

        List<Map<String, Object>> suspiciousCases = new ArrayList<>();
        for (RepService.SuspiciousRepCase entry : snapshot.suspiciousCases()) {
            suspiciousCases.add(entry.serialize());
        }
        config.set("suspiciousCases", suspiciousCases);

        List<Map<String, Object>> removalCooldowns = new ArrayList<>();
        for (PluginDataSnapshot.RemovalCooldownEntry entry : snapshot.removalCooldowns()) {
            Map<String, Object> serialized = new LinkedHashMap<>();
            serialized.put("giver", entry.giverId().toString());
            serialized.put("target", entry.targetId().toString());
            serialized.put("removedAt", entry.removedAt());
            removalCooldowns.add(serialized);
        }
        config.set("removalCooldowns", removalCooldowns);

        for (Map.Entry<UUID, Boolean> entry : snapshot.repTradingAlertPreferences().entrySet()) {
            config.set("playerSettings." + entry.getKey() + ".repTradingAlertsEnabled", entry.getValue());
        }

        int stalkIndex = 0;
        for (PluginDataSnapshot.StalkEntry entry : snapshot.stalkEntries()) {
            String path = "stalks." + stalkIndex++;
            config.set(path + ".stalker", entry.stalkerId().toString());
            config.set(path + ".target", entry.targetId().toString());
            config.set(path + ".expiresAt", entry.expiresAt());
        }

        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data directory.");
            return false;
        }
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            config.save(temporary);
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to save data.yml: " + exception.getMessage());
            if (temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit();
            }
            return false;
        }
    }
}
