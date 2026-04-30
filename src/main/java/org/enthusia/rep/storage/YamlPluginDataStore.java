package org.enthusia.rep.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.analytics.ReputationChangeRecord;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class YamlPluginDataStore implements PluginDataStore {

    private static final int DATA_VERSION = 3;

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

        return new PluginDataSnapshot(scores, commendations, removedEntries, stalkEntries, reputationChanges);
    }

    @Override
    public void save(PluginDataSnapshot snapshot) {
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

        int stalkIndex = 0;
        for (PluginDataSnapshot.StalkEntry entry : snapshot.stalkEntries()) {
            String path = "stalks." + stalkIndex++;
            config.set(path + ".stalker", entry.stalkerId().toString());
            config.set(path + ".target", entry.targetId().toString());
            config.set(path + ".expiresAt", entry.expiresAt());
        }

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save data.yml: " + e.getMessage());
        }
    }
}
