package org.enthusia.rep.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.enthusia.rep.effects.RepAppliedEffects;
import org.enthusia.rep.rep.RepCategory;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;

/** Rules are evaluated independently; the strongest penalty wins over rewards. */
final class RepEffectRules {
    private static final String TELEPORT = "teleport";
    private final List<Rule> overall;
    private final java.util.Set<String> disabled;
    private final Map<RepCategory, List<Rule>> categories = new ConcurrentHashMap<>();

    RepEffectRules(FileConfiguration config) {
        disabled = java.util.Set.copyOf(config.getStringList("rep.effectRules.disabledEffects"));
        List<Rule> defaults = List.of(
                new Rule(TELEPORT, -10, 1.4), new Rule(TELEPORT, -15, 1.6),
                new Rule(TELEPORT, -25, 2), new Rule(TELEPORT, 5, 50.0 / 60),
                new Rule(TELEPORT, 10, .75), new Rule(TELEPORT, 15, 40.0 / 60),
                new Rule(TELEPORT, 20, .5),
                new Rule("glow", config.getInt("rep.effects.penalties.glowAt", -10), 1),
                new Rule("redGlow", config.getInt("rep.effects.penalties.redGlowAt", -20), 1),
                new Rule("stalk", config.getInt("rep.effects.penalties.stalkableAt", -12), 1));
        overall = read(config, "rep.effectRules.overall", defaults);
        for (RepCategory category : RepCategory.selectableValues()) {
            categories.put(category, read(config, "rep.effectRules.categories." + category.name(),
                    defaults.stream().filter(rule -> (rule.threshold() > 0) == category.isPositive()).toList()));
        }
    }

    private static List<Rule> read(FileConfiguration config, String path, List<Rule> fallback) {
        if (!config.contains(path)) return fallback;
        List<Rule> rules = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList(path)) {
            if (Boolean.FALSE.equals(entry.get("enabled"))) continue;
            if (!(entry.get("threshold") instanceof Number threshold)) continue;
            if (!(entry.get("value") instanceof Number value)) continue;
            if (threshold.intValue() == 0 || !Double.isFinite(value.doubleValue())) continue;
            rules.add(new Rule(String.valueOf(entry.get("effect")), threshold.intValue(), value.doubleValue()));
        }
        return List.copyOf(rules);
    }

    RepAppliedEffects resolve(int score, Map<RepCategory, Integer> scores) {
        Map<String, Double> effects = new ConcurrentHashMap<>();
        apply(overall, score, effects);
        scores.forEach((category, value) -> apply(categories.getOrDefault(category, List.of()), value, effects));
        disabled.forEach(effects::remove);
        return new RepAppliedEffects(integer(effects, "movement"), integer(effects, "potion"),
                integer(effects, "firework"), integer(effects, "pearl"), integer(effects, "wind"),
                effects.getOrDefault("glow", 0D) > 0 || effects.getOrDefault("redGlow", 0D) > 0,
                effects.getOrDefault("redGlow", 0D) > 0 ? ChatColor.RED : RepAppliedEffects.NONE.glowColor(),
                effects.getOrDefault("stalk", 0D) > 0, effects.getOrDefault(TELEPORT, 1D));
    }

    private static int integer(Map<String, Double> effects, String key) {
        return effects.getOrDefault(key, 0D).intValue();
    }

    private static void apply(List<Rule> rules, int score, Map<String, Double> effects) {
        for (Rule rule : rules) {
            if (rule.threshold() > 0 ? score < rule.threshold() : score > rule.threshold()) continue;
            double value = rule.value();
            if (rule.effect().equals(TELEPORT) && value <= 0) continue;
            effects.merge(rule.effect(), value, (old, next) -> strongest(rule.effect(), old, next));
        }
    }

    private static double strongest(String effect, double old, double next) {
        return switch (effect) {
            case TELEPORT -> old > 1 || next > 1 ? Math.max(old, next) : Math.min(old, next);
            case "movement", "potion", "firework" -> old < 0 || next < 0 ? Math.min(old, next) : Math.max(old, next);
            default -> Math.max(old, next);
        };
    }

    private record Rule(String effect, int threshold, double value) { }
}
