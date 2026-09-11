package org.enthusia.rep.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.LecternInventory;
import org.bukkit.inventory.view.LecternView;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewBookMenuTest {
    @Test
    void closingReaderClearsBookImmediatelyAndReturnsOnlyAfterClose() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Plugin plugin = mock(Plugin.class);
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.isOnline()).thenReturn(true);
            LecternView view = mock(LecternView.class);
            LecternInventory inventory = mock(LecternInventory.class);
            when(view.getTopInventory()).thenReturn(inventory);
            when(view.getPlayer()).thenReturn(player);
            when(player.getOpenInventory()).thenReturn(view);
            ReviewBookMenu menu = new ReviewBookMenu(plugin, ignored -> view);
            Runnable returnToProfile = mock(Runnable.class);
            menu.open(player, mock(ItemStack.class), returnToProfile);
            verify(returnToProfile, never()).run();

            InventoryClickEvent click = mock(InventoryClickEvent.class);
            when(click.getWhoClicked()).thenReturn(player);
            when(click.getView()).thenReturn(view);
            menu.onClick(click);
            verify(click).setCancelled(true);
            PlayerTakeLecternBookEvent take = mock(PlayerTakeLecternBookEvent.class);
            when(take.getPlayer()).thenReturn(player);
            menu.onTakeBook(take);
            verify(take).setCancelled(true);

            List<Runnable> tasks = new ArrayList<>();
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                tasks.add(call.getArgument(1)); return null;
            });
            menu.onClose(new InventoryCloseEvent(view, InventoryCloseEvent.Reason.PLAYER));
            verify(inventory).clear();
            verify(returnToProfile, never()).run();
            InventoryView defaultView = mock(InventoryView.class);
            Inventory crafting = mock(Inventory.class);
            when(crafting.getHolder()).thenReturn(player);
            when(crafting.getSize()).thenReturn(5);
            when(defaultView.getTopInventory()).thenReturn(crafting);
            when(player.getOpenInventory()).thenReturn(defaultView);
            assertEquals(1, tasks.size());
            tasks.getFirst().run();
            verify(returnToProfile).run();
            menu.onClose(new InventoryCloseEvent(view, InventoryCloseEvent.Reason.PLAYER));
            assertEquals(1, tasks.size(), "A close must be consumed once");
        }
    }

    @Test
    void openingAnotherMenuDoesNotHijackItAndShutdownClearsDetachedBook() {
        Plugin plugin = mock(Plugin.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        LecternView view = mock(LecternView.class);
        LecternInventory inventory = mock(LecternInventory.class);
        when(view.getTopInventory()).thenReturn(inventory);
        when(view.getPlayer()).thenReturn(player);
        when(player.getOpenInventory()).thenReturn(view);
        Runnable callback = mock(Runnable.class);
        ReviewBookMenu menu = new ReviewBookMenu(plugin, ignored -> view);
        menu.open(player, mock(ItemStack.class), callback);
        menu.onClose(new InventoryCloseEvent(view, InventoryCloseEvent.Reason.OPEN_NEW));
        verify(inventory).clear();
        verify(callback, never()).run();
        menu.open(player, mock(ItemStack.class), callback);
        menu.shutdown();
        verify(inventory, times(2)).clear();
    }
}
