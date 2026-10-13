package org.enthusia.rep.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.analytics.ReputationChangeAction;
import org.enthusia.rep.analytics.ReputationChangeOutcome;
import org.enthusia.rep.analytics.ReputationChangeRecord;
import org.enthusia.rep.analytics.ReputationChangeSource;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Monotonic evidence only: never pays rewards or changes reputation. */
final class AdvancementEvidenceWriter {
    private static final String MIN_OVERALL = "minOverall";
    private AdvancementEvidenceWriter() { }

    static void write(YamlConfiguration output, ConfigurationSection previous, PluginDataSnapshot snapshot) {
        ConfigurationSection evidence = output.createSection("advancementEvidence");
        copyPrevious(evidence, previous);
        replayHistory(evidence, snapshot);
        snapshot.scores().forEach((id, score) -> total(player(evidence, id), score));
        writeCategories(evidence, snapshot);
    }

    private static void copyPrevious(ConfigurationSection evidence, ConfigurationSection previous) {
        if (previous != null) {
            previous.getValues(true).forEach((key, value) -> {
                if (!(value instanceof ConfigurationSection)) evidence.set(key, value);
            });
        }
    }

    private static void replayHistory(ConfigurationSection evidence, PluginDataSnapshot snapshot) {
        Map<UUID, Integer> historicalMinimum = new ConcurrentHashMap<>();
        snapshot.reputationChanges().stream()
                .filter(change -> change.outcome() == ReputationChangeOutcome.SUCCEEDED)
                .sorted(Comparator.comparingLong(ReputationChangeRecord::timestamp))
                .forEach(change -> replayChange(evidence, historicalMinimum, change));
    }

    private static void replayChange(ConfigurationSection evidence, Map<UUID, Integer> historicalMinimum,
                                     ReputationChangeRecord change) {
        ConfigurationSection section = player(evidence, change.targetId());
        int minimum = Math.min(historicalMinimum.getOrDefault(change.targetId(), 0), change.oldTotal());
        if (minimum <= -12 && change.newTotal() >= 0) section.set("recoveredFromSevere", true);
        historicalMinimum.put(change.targetId(), Math.min(minimum, change.newTotal()));
        extremes(section, change.oldTotal());
        extremes(section, change.newTotal());
        if (isPositivePlayerCommendation(change)) section.set("positiveReceived", true);
    }

    private static boolean isPositivePlayerCommendation(ReputationChangeRecord change) {
        return change.source() == ReputationChangeSource.PLAYER_ACTION
                && (change.action() == ReputationChangeAction.ADD || change.action() == ReputationChangeAction.UPDATE)
                && change.amount() > 0 && change.category() != null && change.category().isPositive();
    }

    private static void writeCategories(ConfigurationSection evidence, PluginDataSnapshot snapshot) {
        Map<UUID, Map<String, Integer>> categories = new ConcurrentHashMap<>();
        snapshot.commendations().forEach(commendation -> {
            if (!commendation.isPositive()) return;
            player(evidence, commendation.getTarget()).set("positiveReceived", true);
            categories.computeIfAbsent(commendation.getTarget(), ignored -> new ConcurrentHashMap<>())
                    .merge(commendation.getCategory().name(), commendation.getScoreValue(), Math::addExact);
        });
        categories.forEach((id, totals) -> {
            ConfigurationSection section = player(evidence, id);
            totals.forEach((category, count) -> {
                String key = "categoryMax." + category;
                section.set(key, Math.max(section.getInt(key), count));
            });
        });
    }

    private static ConfigurationSection player(ConfigurationSection evidence, UUID id) {
        String key = id.toString();
        ConfigurationSection section = evidence.getConfigurationSection(key);
        if (section == null) {
            section = evidence.createSection(key);
            section.set("positiveReceived", false);
            section.set("maxOverall", 0);
            section.set(MIN_OVERALL, 0);
            section.set("recoveredFromSevere", false);
        }
        return section;
    }

    private static void total(ConfigurationSection section, int score) {
        if (section.getInt(MIN_OVERALL) <= -12 && score >= 0) section.set("recoveredFromSevere", true);
        extremes(section, score);
    }

    private static void extremes(ConfigurationSection section, int score) {
        section.set("maxOverall", Math.max(section.getInt("maxOverall"), score));
        section.set(MIN_OVERALL, Math.min(section.getInt(MIN_OVERALL), score));
    }
}
