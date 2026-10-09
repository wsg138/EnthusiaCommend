package org.enthusia.rep;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PluginSurfaceContractTest {

    @Test
    void pluginDescriptorRetainsReviewedIdentityDependenciesAndCommandSurface() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(input);
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(yaml.contains("name: EnthusiaCommend"));
            assertTrue(yaml.contains("main: org.enthusia.rep.CommendPlugin"));
            assertTrue(yaml.contains("api-version: '1.21'"));
            assertTrue(yaml.contains("depend: [Vault]"));
            assertTrue(yaml.contains("softdepend: [PlaceholderAPI, EnthusiaTeleport, ProtocolLib, WarzoneDuels, Plan]"));
            assertTrue(yaml.contains("  rep:\n"));
            assertTrue(yaml.contains("    aliases: [reputation]"));
            assertTrue(yaml.contains("    permission: enthusiacommend.rep.view"));
        }
    }
}
