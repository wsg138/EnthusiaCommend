package org.enthusia.rep.placeholder;

import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.config.RepConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepPlaceholderExpansionTest {

    @Test
    void rendersPositiveNeutralAndNegativeMiniMessageScoresAndTokens() {
        RepConfig config = new RepConfig(new YamlConfiguration());

        assertEquals("<green>125</green>",
                RepPlaceholderExpansion.resolvePlaceholder(125, "score_mm", config));
        assertEquals("<green>",
                RepPlaceholderExpansion.resolvePlaceholder(125, "color_mm", config));
        assertEquals("<yellow>0</yellow>",
                RepPlaceholderExpansion.resolvePlaceholder(0, "score_mm", config));
        assertEquals("<yellow>",
                RepPlaceholderExpansion.resolvePlaceholder(0, "color_mm", config));
        assertEquals("<red>-8</red>",
                RepPlaceholderExpansion.resolvePlaceholder(-8, "score_mm", config));
        assertEquals("<red>",
                RepPlaceholderExpansion.resolvePlaceholder(-8, "color_mm", config));
    }

    @Test
    void rendersGlowMiniMessageColorAndWhiteFallback() {
        RepConfig config = new RepConfig(new YamlConfiguration());

        assertEquals("<white>",
                RepPlaceholderExpansion.resolvePlaceholder(-10, "glowcolor_mm", config));
        assertEquals("<red>",
                RepPlaceholderExpansion.resolvePlaceholder(-20, "glowcolor_mm", config));
    }

    @Test
    void preservesEveryLegacyPlaceholderContract() {
        RepConfig config = new RepConfig(new YamlConfiguration());

        assertEquals("125", RepPlaceholderExpansion.resolvePlaceholder(125, "score", config));
        assertEquals("125", RepPlaceholderExpansion.resolvePlaceholder(125, "score_raw", config));
        assertEquals(ChatColor.GREEN + "125",
                RepPlaceholderExpansion.resolvePlaceholder(125, "score_colored", config));
        assertEquals(ChatColor.GREEN.toString(),
                RepPlaceholderExpansion.resolvePlaceholder(125, "color", config));
        assertEquals("&f",
                RepPlaceholderExpansion.resolvePlaceholder(-10, "glowcolor", config));
        assertEquals(ChatColor.RED.toString(),
                RepPlaceholderExpansion.resolvePlaceholder(-20, "glowcolor", config));
    }

    @Test
    void returnsSafeEmptyValuesForUnavailablePlayers() {
        RepConfig config = new RepConfig(new YamlConfiguration());
        RepPlaceholderExpansion expansion = new RepPlaceholderExpansion(
                () -> "test",
                ignored -> 1,
                () -> config
        );

        assertEquals("", expansion.onRequest(null, "score_mm"));
        assertEquals("", expansion.onRequest(offlinePlayer(null), "score_mm"));
        assertNull(expansion.onRequest(offlinePlayer(UUID.randomUUID()), "unknown"));
    }

    @Test
    void readsTheCurrentConfigAfterReloadWithoutRecreatingTheExpansion() {
        AtomicReference<RepConfig> config = new AtomicReference<>(new RepConfig(new YamlConfiguration()));
        RepPlaceholderExpansion expansion = new RepPlaceholderExpansion(
                () -> "test",
                ignored -> -10,
                config::get
        );
        OfflinePlayer player = offlinePlayer(UUID.randomUUID());

        assertEquals("<white>", expansion.onRequest(player, "glowcolor_mm"));

        YamlConfiguration reloadedValues = new YamlConfiguration();
        reloadedValues.set("rep.effects.penalties.redGlowAt", -5);
        config.set(new RepConfig(reloadedValues));

        assertEquals("<red>", expansion.onRequest(player, "glowcolor_mm"));
    }

    @Test
    void placeholderEvaluationUsesOnlyOneCacheLookup() {
        AtomicInteger lookups = new AtomicInteger();
        RepConfig config = new RepConfig(new YamlConfiguration());
        RepPlaceholderExpansion expansion = new RepPlaceholderExpansion(
                () -> "test",
                ignored -> {
                    lookups.incrementAndGet();
                    return 12;
                },
                () -> config
        );

        assertEquals("<green>12</green>",
                expansion.onRequest(offlinePlayer(UUID.randomUUID()), "score_mm"));
        assertEquals(1, lookups.get());
        assertEquals("enthusiarep", expansion.getIdentifier());
        assertEquals("test", expansion.getVersion());
        assertTrue(expansion.persist());
    }

    private OfflinePlayer offlinePlayer(UUID playerId) {
        return (OfflinePlayer) Proxy.newProxyInstance(
                OfflinePlayer.class.getClassLoader(),
                new Class<?>[]{OfflinePlayer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) {
                        return playerId;
                    }
                    if (method.getName().equals("toString")) {
                        return "OfflinePlayer[" + playerId + ']';
                    }
                    if (method.getReturnType().isPrimitive()) {
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == char.class) return '\0';
                        return 0;
                    }
                    return null;
                }
        );
    }
}
