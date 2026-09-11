package org.enthusia.rep.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.LecternView;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** A detached lectern has a server-visible close, unlike Player.openBook. */
final class ReviewBookMenu implements Listener {
    private final Plugin plugin;
    private final java.util.function.Function<Player, LecternView> viewFactory;
    private final Map<UUID, Session> sessions = new HashMap<>();

    ReviewBookMenu(Plugin plugin) {
        this(plugin, player -> MenuType.LECTERN.builder().checkReachable(false).build(player));
    }

    ReviewBookMenu(Plugin plugin, java.util.function.Function<Player, LecternView> viewFactory) {
        this.plugin = plugin;
        this.viewFactory = viewFactory;
    }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void open(Player player, ItemStack book, Runnable returnToProfile) {
        Session previous = sessions.remove(player.getUniqueId());
        if (previous != null) previous.inventory().clear();
        LecternView view = viewFactory.apply(player);
        view.getTopInventory().setItem(0, book);
        Session session = new Session(view.getTopInventory(), returnToProfile);
        sessions.put(player.getUniqueId(), session);
        player.openInventory(view);
        if (!view.getTopInventory().equals(player.getOpenInventory().getTopInventory())) {
            sessions.remove(player.getUniqueId(), session);
            view.getTopInventory().clear();
        }
    }

    private boolean active(UUID player, Inventory inventory) {
        Session session = sessions.get(player);
        return session != null && session.inventory().equals(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (active(event.getWhoClicked().getUniqueId(), event.getView().getTopInventory())) event.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (active(event.getWhoClicked().getUniqueId(), event.getView().getTopInventory())) event.setCancelled(true);
    }

    @EventHandler
    public void onTakeBook(PlayerTakeLecternBookEvent event) {
        if (active(event.getPlayer().getUniqueId(), event.getPlayer().getOpenInventory().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!active(id, event.getInventory())) return;
        Session session = sessions.remove(id);
        session.inventory().clear();
        if (event.getReason() != InventoryCloseEvent.Reason.PLAYER) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.getPlayer() instanceof Player player && player.isOnline() && isDefaultInventory(player)) {
                session.returnToProfile().run();
            }
        });
    }

    private static boolean isDefaultInventory(Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        return top.equals(player.getInventory()) || (top.getSize() == 5 && top.getHolder() == player);
    }

    void shutdown() {
        for (Session session : sessions.values()) session.inventory().clear();
        sessions.clear();
    }

    private record Session(Inventory inventory, Runnable returnToProfile) { }
}
