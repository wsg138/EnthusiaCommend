package org.enthusia.rep;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Inventory guard for deterministic EnthusiaCommend feature families that are expected to retain
 * concrete repository-local regression suites. This is not a substitute for behavioral tests.
 */
class FullFeatureCoverageContractTest {
    private static final Map<String, List<String>> FEATURE_MARKERS = featureMarkers();

    @Test
    void everyReviewedFeatureFamilyHasConcreteRegressionCoverage() throws IOException {
        Path root = repositoryRoot();
        Set<String> tests;
        try (Stream<Path> files = Files.walk(root.resolve("src/test/java"))) {
            tests = files
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace('\\', '/').toLowerCase(Locale.ROOT))
                    .filter(path -> path.endsWith("test.java"))
                    .collect(Collectors.toSet());
        }

        Map<String, List<String>> missing = new LinkedHashMap<>();
        FEATURE_MARKERS.forEach((feature, markers) -> {
            boolean covered = markers.stream()
                    .map(marker -> marker.toLowerCase(Locale.ROOT))
                    .anyMatch(marker -> tests.stream().anyMatch(path -> path.contains(marker)));
            if (!covered) {
                missing.put(feature, markers);
            }
        });

        if (!missing.isEmpty()) {
            fail("Reviewed EnthusiaCommend feature families without concrete regression tests: " + missing);
        }
    }

    private static Map<String, List<String>> featureMarkers() {
        Map<String, List<String>> markers = new LinkedHashMap<>();
        markers.put("plugin metadata/surface", List.of("pluginsurfacecontract"));
        markers.put("moderation API contracts", List.of("reputationapicontract"));
        markers.put("analytics", List.of("analytics/"));
        markers.put("commands and permissions", List.of("command/"));
        markers.put("configuration and migrations", List.of("config/"));
        markers.put("Discord webhook/head output", List.of("discord/"));
        markers.put("reputation effects", List.of("effects/"));
        markers.put("GUI navigation/input/snapshot safety", List.of("gui/"));
        markers.put("moderation persistence/policy", List.of("moderation/"));
        markers.put("placeholder output", List.of("placeholder/"));
        markers.put("regions and logical zones", List.of("region/"));
        markers.put("reputation rules/identity/leaderboards", List.of("rep/"));
        markers.put("stalk movement/eligibility", List.of("stalk/"));
        markers.put("snapshot persistence", List.of("storage/"));
        markers.put("date/time presentation", List.of("util/"));
        return Map.copyOf(markers);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("src/test/java"))) {
            return current;
        }
        throw new IllegalStateException("Could not locate EnthusiaCommend repository root from " + current);
    }
}
