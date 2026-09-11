package org.enthusia.rep.rep;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.config.RepConfig;
import org.enthusia.rep.storage.PluginDataSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepServicePolicyTest {
    private final UUID giver = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final UUID alternate = UUID.randomUUID();
    private MockedStatic<Bukkit> bukkit;
    private YamlConfiguration yaml;
    private final AtomicInteger refreshes = new AtomicInteger();

    @BeforeEach
    void setup() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
        bukkit.when(() -> Bukkit.getOfflinePlayer(org.mockito.ArgumentMatchers.any(UUID.class))).thenAnswer(call -> {
            org.bukkit.OfflinePlayer player = mock(org.bukkit.OfflinePlayer.class);
            when(player.getName()).thenReturn("Tester");
            return player;
        });
        yaml = new YamlConfiguration();
        yaml.set("rep.editCooldownHours", 0);
    }

    @AfterEach
    void close() { bukkit.close(); }

    private PluginDataSnapshot initial(String targetHash) {
        return new PluginDataSnapshot(Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(),
                Map.of(target, new RepIdentityState(Set.of(targetHash), Set.of(), 0)));
    }

    private RepService service(PluginDataSnapshot snapshot) {
        return new RepService(mock(CommendPlugin.class), new RepConfig(yaml), snapshot, () -> { },
                ignored -> refreshes.incrementAndGet(), null);
    }

    private RepService.CommendationResult vote(RepService service, UUID author, boolean positive, RepCategory category, String hash) {
        return service.addOrUpdateCommendation(author, target, positive, category, "Reason", hash);
    }

    @Test
    void blocksSharedIpTargetWithoutChangingScores() {
        RepService service = service(initial("shared"));
        assertEquals(RepService.CommendationResult.Failure.IP_RESTRICTED,
                vote(service, giver, true, RepCategory.WAS_KIND, "shared").failure());
        assertEquals(0, service.getScore(target));
        assertTrue(service.getCommendationsAbout(target).isEmpty());
    }

    @Test
    void blocksUnknownAddressesAndSelfReputation() {
        RepService service = service(PluginDataSnapshot.EMPTY);
        assertEquals(RepService.CommendationResult.Failure.ADDRESSES_UNKNOWN,
                vote(service, giver, true, RepCategory.WAS_KIND, "giver-ip").failure());
        assertEquals(RepService.CommendationResult.Failure.IP_RESTRICTED,
                vote(service, target, true, RepCategory.WAS_KIND, "target-ip").failure());
    }

    @Test
    void alternateVoteBlockedEvenAfterRemovalAndRestart() {
        RepService original = service(initial("target-ip"));
        assertTrue(vote(original, giver, true, RepCategory.WAS_KIND, "shared").success());
        original.removeCommendation(giver, target);
        RepService restored = service(original.snapshot(PluginDataSnapshot.EMPTY));
        assertEquals(RepService.CommendationResult.Failure.IP_RESTRICTED,
                vote(restored, alternate, false, RepCategory.GRIEFED, "shared").failure());
        assertEquals(RepService.CommendationResult.Failure.COOLDOWN,
                vote(restored, giver, true, RepCategory.WAS_KIND, "new-address").failure());
        assertTrue(restored.getRemovalCooldownMillis(giver, target) > 23 * 3_600_000L);
    }

    @Test
    void staffRemovalAlsoAppliesConfiguredCooldownAndZeroDisablesIt() {
        RepService service = service(initial("target-ip"));
        assertTrue(vote(service, giver, true, RepCategory.WAS_KIND, "giver-ip").success());
        service.removeCommendationLogged(alternate, giver, target, false);
        assertTrue(service.getRemovalCooldownMillis(giver, target) > 0);
        yaml.set("rep.removalCooldownHours", 0);
        service.reload(new RepConfig(yaml));
        assertTrue(vote(service, giver, false, RepCategory.GRIEFED, "giver-ip").success());
    }

    @Test
    void ipProtectionCanBeDisabledWithoutAllowingSelfRep() {
        yaml.set("rep.ipProtection.enabled", false);
        RepService service = service(initial("shared"));
        assertTrue(vote(service, giver, true, RepCategory.WAS_KIND, "shared").success());
        assertTrue(vote(service, alternate, true, RepCategory.WAS_KIND, "shared").success());
        assertFalse(vote(service, target, true, RepCategory.WAS_KIND, "shared").success());
    }

    @Test
    void tarnishedPersistsExpiresAndNeverOverridesNegativeColor() {
        RepService service = service(initial("target-ip"));
        service.setScore(target, 10);
        assertTrue(vote(service, giver, false, RepCategory.GRIEFED, "giver-ip").success());
        RepService restored = service(service.snapshot(PluginDataSnapshot.EMPTY));
        assertTrue(restored.isTarnished(target));
        assertEquals(ChatColor.GOLD, restored.colorForPlayer(target));
        restored.setScore(target, -5);
        assertEquals(ChatColor.RED, restored.colorForPlayer(target));
        restored.setScore(target, 8);
        yaml.set("rep.tarnished.hours", 0);
        restored.reload(new RepConfig(yaml));
        assertEquals(ChatColor.GREEN, restored.colorForPlayer(target));
    }

    @Test
    void editingNegativeReasonDoesNotExtendTarnishAndCategorySwitchRefreshesEffects() {
        RepService service = service(initial("target-ip"));
        service.setScore(target, 10);
        vote(service, giver, false, RepCategory.GRIEFED, "giver-ip");
        long originalTime = service.snapshot(PluginDataSnapshot.EMPTY).identities().get(target).tarnishedAt();
        int before = refreshes.get();
        assertTrue(vote(service, giver, false, RepCategory.SPAWN_KILLED, "giver-ip").success());
        assertEquals(originalTime, service.snapshot(PluginDataSnapshot.EMPTY).identities().get(target).tarnishedAt());
        assertEquals(before + 1, refreshes.get());
        assertEquals(-2, service.getCategoryScore(target, RepCategory.SPAWN_KILLED));
        assertEquals(0, service.getCategoryScore(target, RepCategory.GRIEFED));
    }

    @Test
    void oldRemovedRecordsSeedAntiAltHistoryAndStallScamMigrates() {
        Commendation old = new Commendation(giver, target, false, RepCategory.SCAM_STALL, "Old", 1, 1, "shared", -2);
        var snapshot = new PluginDataSnapshot(Map.of(), List.of(), List.of(new RepService.RemovedRep("old", old, 2, alternate)),
                List.of(), List.of(), List.of());
        RepService service = service(snapshot);
        assertEquals(RepService.CommendationResult.Failure.IP_RESTRICTED,
                vote(service, alternate, true, RepCategory.WAS_KIND, "shared").failure());
        assertFalse(RepCategory.SCAM_STALL.isSelectable());
        assertEquals(RepCategory.SCAMMED, RepCategory.fromStored("SCAM_STALL", false));
    }

    @Test
    void polarityLeaderboardsSumAllCategoriesWithoutNettingTheOtherSide() {
        RepService service = service(initial("target-ip"));
        vote(service, giver, true, RepCategory.WAS_KIND, "one");
        vote(service, alternate, false, RepCategory.GRIEFED, "two");
        assertEquals(-1, service.getScore(target));
        assertEquals(List.of(Map.entry(target, 1)), service.leaderboardPolarity(true, false));
        assertEquals(List.of(Map.entry(target, -2)), service.leaderboardPolarity(false, true));
    }
}
