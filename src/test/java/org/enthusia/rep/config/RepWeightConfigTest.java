package org.enthusia.rep.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RepWeightConfigTest {
    @Test
    void defaultsAndCustomSignedWeights() {
        var yaml = new YamlConfiguration();
        var config = new RepConfig(yaml);
        assertEquals(1, config.getVoteWeight(true));
        assertEquals(-2, config.getVoteWeight(false));
        yaml.set("rep.weights.positive", 3);
        yaml.set("rep.weights.negative", -5);
        assertEquals(3, config.getVoteWeight(true));
        assertEquals(-5, config.getVoteWeight(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-4", "1.5", "bad", "999999999999"})
    void invalidPositiveWeightUsesDefault(String value) {
        var yaml = new YamlConfiguration();
        yaml.set("rep.weights.positive", value);
        assertEquals(1, new RepConfig(yaml).getVoteWeight(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "4", "-1.5", "bad", "-2147483648", "-999999999999"})
    void invalidNegativeWeightUsesDefault(String value) {
        var yaml = new YamlConfiguration();
        yaml.set("rep.weights.negative", value);
        assertEquals(-2, new RepConfig(yaml).getVoteWeight(false));
    }
}
