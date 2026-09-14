package org.enthusia.rep.rep;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.config.RepConfig;
import org.enthusia.rep.storage.PluginDataSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class RepRestoreTarnishedTest {
    private final UUID giver = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private MockedStatic<Bukkit> bukkit;
    private YamlConfiguration yaml;

    @BeforeEach
    void setup() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
        yaml = new YamlConfiguration();
        yaml.set("rep.editCooldownHours", 0);
        yaml.set("rep.ipProtection.enabled", false);
        yaml.set("rep.tarnished.hours", 24);
    }

    @AfterEach
    void close() {
        bukkit.close();
    }

    @Test
    void adminRestoreReinstatesNegativeTarnishWithOriginalTimestamp() {
        RepService service = service(PluginDataSnapshot.EMPTY);
        service.setScore(target, 10);
        assertTrue(service.addOrUpdateCommendation(
                giver, target, false, RepCategory.GRIEFED, "Reason", "giver-ip").success());
        long originalTimestamp = service.snapshot(PluginDataSnapshot.EMPTY)
                .identities().get(target).tarnishedAt();
        assertTrue(service.isTarnished(target));

        RepService.RemovedRep removed = service.removeCommendationByStaffCommand(null, giver, target, true);
        assertNotNull(removed);
        assertFalse(service.isTarnished(target));

        assertTrue(service.restoreRemoved(removed.id(), null));
        assertTrue(service.isTarnished(target));
        assertEquals(originalTimestamp, service.snapshot(PluginDataSnapshot.EMPTY)
                .identities().get(target).tarnishedAt());

        RepService restarted = service(service.snapshot(PluginDataSnapshot.EMPTY));
        assertTrue(restarted.isTarnished(target));
        assertEquals(originalTimestamp, restarted.snapshot(PluginDataSnapshot.EMPTY)
                .identities().get(target).tarnishedAt());
    }

    @Test
    void restoringOlderContributorDoesNotReplaceNewerTarnishTimestamp() {
        UUID newerGiver = UUID.randomUUID();
        long older = System.currentTimeMillis() - 60_000L;
        long newer = System.currentTimeMillis();
        RepIdentityState state = RepIdentityState.EMPTY.tarnish(giver, older).tarnish(newerGiver, newer);

        RepIdentityState restoredOlder = state.tarnish(giver, older);

        assertEquals(newer, restoredOlder.tarnishedAt());
        assertEquals(older, restoredOlder.tarnishSources().get(giver.toString()));
        assertEquals(newer, restoredOlder.tarnishSources().get(newerGiver.toString()));
    }

    private RepService service(PluginDataSnapshot snapshot) {
        return new RepService(
                mock(CommendPlugin.class),
                new RepConfig(yaml),
                snapshot,
                () -> { },
                ignored -> { },
                null
        );
    }
}
