package org.enthusia.rep.stalk;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.effects.RepAppliedEffects;
import org.enthusia.rep.region.RegionManager;
import org.enthusia.rep.rep.RepService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StalkEligibilityTest {
    @Test
    void existingSubscriptionStopsAlertingWhenStalkingEffectIsDisabled() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            RepService service = mock(RepService.class);
            RegionManager regions = mock(RegionManager.class);
            Player target = mock(Player.class);
            Player subscriber = mock(Player.class);
            UUID targetId = UUID.randomUUID();
            UUID subscriberId = UUID.randomUUID();
            when(target.getUniqueId()).thenReturn(targetId);
            when(subscriber.isOnline()).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(subscriberId)).thenReturn(subscriber);
            Location spawn = mock(Location.class);
            Location warzone = mock(Location.class);
            when(spawn.getBlockX()).thenReturn(0);
            when(warzone.getBlockX()).thenReturn(100);
            when(target.getLocation()).thenReturn(spawn);
            when(regions.resolveStalkingZone(spawn)).thenReturn(RegionManager.LogicalZone.SPAWN);
            when(regions.resolveStalkingZone(warzone)).thenReturn(RegionManager.LogicalZone.WARZONE);
            when(service.getEffects(targetId)).thenReturn(new RepAppliedEffects(0, 0, 0, 0, 0, false, null, true, 1));
            StalkManager manager = new StalkManager(mock(CommendPlugin.class), regions, service, () -> { });
            manager.addSubscription(subscriberId, targetId, 60_000L);
            PlayerJoinEvent join = mock(PlayerJoinEvent.class);
            when(join.getPlayer()).thenReturn(target);
            manager.onJoin(join);
            manager.onMove(new PlayerMoveEvent(target, spawn, warzone));
            verify(subscriber).sendMessage(anyString());
            clearInvocations(subscriber);
            when(service.getEffects(targetId)).thenReturn(RepAppliedEffects.NONE);
            manager.onMove(new PlayerMoveEvent(target, warzone, spawn));
            manager.onMove(new PlayerMoveEvent(target, spawn, warzone));
            verify(subscriber, never()).sendMessage(anyString());
        }
    }
}
