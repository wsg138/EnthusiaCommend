package org.enthusia.rep.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.config.RepConfig;
import org.enthusia.rep.rep.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepNavigationTest {
    @Test
    void recentLeaderboardRoundTripPreservesPageAndReviewClicksStayInProfile() {
        try (var bukkit = mockStatic(Bukkit.class); var heads = mockStatic(HeadUtil.class);
             var items = mockConstruction(ItemStack.class)) {
            var plugin = mock(CommendPlugin.class);
            when(plugin.getName()).thenReturn("EnthusiaCommend");
            when(plugin.namespace()).thenReturn("enthusiacommend");
            when(plugin.getRepConfig()).thenReturn(mock(RepConfig.class));
            var service = mock(RepService.class);
            var manager = new RepGuiManager(plugin, service, null);
            when(plugin.getRepGuiManager()).thenReturn(manager);
            var gui = new RepLeaderboardGui(plugin, service);
            var player = mock(Player.class);
            when(player.getName()).thenReturn("Tester");
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.isOnline()).thenReturn(true);
            var current = new AtomicReference<Inventory>();
            var view = mock(InventoryView.class);
            when(player.getOpenInventory()).thenReturn(view);
            when(view.getTopInventory()).thenAnswer(call -> current.get());
            when(player.openInventory(any(Inventory.class))).thenAnswer(call -> { current.set(call.getArgument(0)); return view; });
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            var scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> { call.<Runnable>getArgument(1).run(); return null; });
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(), anyString())).thenAnswer(call -> {
                var inventory = mock(Inventory.class);
                when(inventory.getHolder()).thenReturn(call.getArgument(0));
                when(inventory.getSize()).thenReturn(call.getArgument(1));
                return inventory;
            });
            bukkit.when(() -> Bukkit.getOfflinePlayer(any(UUID.class))).thenAnswer(call -> {
                var target = mock(OfflinePlayer.class);
                when(target.getUniqueId()).thenReturn(call.getArgument(0));
                when(target.getName()).thenReturn("Target");
                return target;
            });
            heads.when(() -> HeadUtil.createPlayerHead(eq(plugin), any(UUID.class), anyString())).thenReturn(mock(ItemStack.class));
            var scores = IntStream.range(1, 31).mapToObj(i -> Map.entry(new UUID(0, i), i)).toList();
            var reviews = scores.stream().map(e -> new Commendation(UUID.randomUUID(), e.getKey(), true,
                    RepCategory.WAS_KIND, "full reason", 1L, e.getValue().longValue(), null, 1)).toList();
            when(service.leaderboard(null, false)).thenReturn(scores);
            when(service.leaderboardPolarity(true, false)).thenReturn(scores);
            when(service.recentCommendationSnapshots(Integer.MAX_VALUE)).thenReturn(reviews);
            when(service.getCommendationsAbout(any(UUID.class))).thenAnswer(call -> reviews.stream().filter(r -> r.getTarget().equals(call.getArgument(0))).toList());
            gui.open(player, false);
            gui.onClick(click(player, view, 2));
            gui.onClick(click(player, view, 10)); // All positive filter
            gui.onClick(click(player, view, 8)); // Recent order
            gui.onClick(click(player, view, 53)); // Second page
            var leaderboard = current.get().getHolder();
            gui.onClick(click(player, view, 10));
            var profile = current.get();
            manager.onInventoryClick(click(player, view, 10));
            assertSame(profile, current.get());
            verify(player, never()).openBook(any(ItemStack.class));
            manager.onInventoryClick(click(player, view, 46));
            assertEquals(leaderboard, current.get().getHolder());
        }
    }

    private InventoryClickEvent click(Player player, InventoryView view, int slot) {
        var event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getView()).thenReturn(view);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        when(event.getRawSlot()).thenReturn(slot);
        return event;
    }
}

