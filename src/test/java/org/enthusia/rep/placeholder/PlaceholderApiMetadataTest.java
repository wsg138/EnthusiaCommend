package org.enthusia.rep.placeholder;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderApiMetadataTest {

    @Test
    void placeholderApiRemainsAnOptionalDependency() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(input);
            String pluginYaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(pluginYaml.contains("softdepend: [PlaceholderAPI"));
        }
    }
}
