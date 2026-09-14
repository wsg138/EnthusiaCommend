package org.enthusia.rep.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Preserve custom reputation rules while upgrading older configurations. */
public final class KindCategoryMigration {
    private static final String OVERALL_RULES = "rep.effectRules.overall";
    private static final String TELEPORT = "teleport";

    private KindCategoryMigration() { }

    public static boolean migrate(FileConfiguration config) {
        boolean changed = migrateKindCategory(config);
        if (migrateLegacyOverallRules(config)) {
            changed = true;
        }
        return changed;
    }

    private static boolean migrateKindCategory(FileConfiguration config) {
        String oldPath = "rep.effectRules.categories.HELPED_ME";
        String newPath = "rep.effectRules.categories.WAS_KIND";
        if (!config.isList(oldPath)) return false;
        List<Object> combined = new ArrayList<>(config.getList(newPath, List.of()));
        combined.addAll(config.getList(oldPath, List.of()));
        config.set(newPath, combined);
        config.set(oldPath, null);
        return true;
    }

    private static boolean migrateLegacyOverallRules(FileConfiguration config) {
        if (config.isSet(OVERALL_RULES) || !hasLegacyEffectThresholds(config)) {
            return false;
        }

        List<Map<String, Object>> rules = new ArrayList<>();
        addRule(rules, TELEPORT, -10, 1.4D);
        addRule(rules, TELEPORT, -15, 1.6D);
        addRule(rules, TELEPORT, -25, 2.0D);
        addRule(rules, "glow", config.getInt("rep.effects.penalties.glowAt", -10), 1.0D);
        addRule(rules, "redGlow", config.getInt("rep.effects.penalties.redGlowAt", -20), 1.0D);
        addRule(rules, "stalk", config.getInt("rep.effects.penalties.stalkableAt", -12), 1.0D);
        addRule(rules, TELEPORT, 5, 50.0D / 60.0D);
        addRule(rules, TELEPORT, 10, 0.75D);
        addRule(rules, TELEPORT, 15, 40.0D / 60.0D);
        addRule(rules, TELEPORT, 20, 0.5D);
        config.set(OVERALL_RULES, rules);
        return true;
    }

    private static boolean hasLegacyEffectThresholds(FileConfiguration config) {
        return config.isSet("rep.effects.penalties.glowAt")
                || config.isSet("rep.effects.penalties.redGlowAt")
                || config.isSet("rep.effects.penalties.stalkableAt");
    }

    private static void addRule(List<Map<String, Object>> rules, String effect, int threshold, double value) {
        Map<String, Object> rule = new ConcurrentHashMap<>();
        rule.put("effect", effect);
        rule.put("threshold", threshold);
        rule.put("value", value);
        rules.add(rule);
    }
}
