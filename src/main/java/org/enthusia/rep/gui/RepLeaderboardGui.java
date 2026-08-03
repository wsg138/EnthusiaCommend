package org.enthusia.rep.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.events.CommendationLeaderboardViewedEvent;
import org.enthusia.rep.rep.RepCategory;
import org.enthusia.rep.rep.RepService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RepLeaderboardGui implements Listener {
    private static final List<Integer> ENTRY_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43);
    private static final int PREVIOUS_SLOT = 45;
    private static final int NEXT_SLOT = 53;

    private final CommendPlugin plugin;
    private final RepService repService;

    public RepLeaderboardGui(CommendPlugin plugin, RepService repService) {
        this.plugin = plugin;
        this.repService = repService;
    }

    public void open(Player viewer, boolean lowest) {
        open(viewer, lowest, null, 0);
    }

    public void open(Player viewer, boolean lowest, RepCategory category, int page) {
        Bukkit.getPluginManager().callEvent(new CommendationLeaderboardViewedEvent(viewer.getUniqueId()));
        List<Map.Entry<UUID, Integer>> entries = repService.leaderboard(category, lowest);
        int maxPage = Math.max(0, (entries.size() - 1) / ENTRY_SLOTS.size());
        int resolvedPage = Math.max(0, Math.min(page, maxPage));
        String direction = lowest ? "Lowest" : "Top";
        String viewName = RepCategoryGuiSupport.displayName(category);
        Inventory inventory = Bukkit.createInventory(new LeaderboardHolder(lowest, category, resolvedPage), 54,
                ChatColor.DARK_GREEN + direction + " Rep: " + ChatColor.RESET + viewName
                        + ChatColor.GRAY + " [" + (resolvedPage + 1) + "/" + (maxPage + 1) + "]");
        fillBackground(inventory);
        RepCategoryGuiSupport.renderSelectors(inventory, RepCategoryGuiSupport.LEADERBOARD_SELECTOR_SLOTS,
                repService, viewer.getUniqueId(), category, "Your total");

        int start = resolvedPage * ENTRY_SLOTS.size();
        for (int index = 0; index < ENTRY_SLOTS.size() && start + index < entries.size(); index++) {
            Map.Entry<UUID, Integer> entry = entries.get(start + index);
            inventory.setItem(ENTRY_SLOTS.get(index), playerItem(entry.getKey(), entry.getValue(),
                    start + index + 1, category));
        }
        if (resolvedPage > 0) inventory.setItem(PREVIOUS_SLOT, button(Material.ARROW, ChatColor.YELLOW + "Previous page"));
        if (resolvedPage < maxPage) inventory.setItem(NEXT_SLOT, button(Material.ARROW, ChatColor.YELLOW + "Next page"));
        viewer.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !(event.getView().getTopInventory().getHolder() instanceof LeaderboardHolder holder)) return;
        event.setCancelled(true);
        event.setResult(Event.Result.DENY);
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        int slot = event.getRawSlot();
        if (RepCategoryGuiSupport.isSelectorSlot(RepCategoryGuiSupport.LEADERBOARD_SELECTOR_SLOTS, slot)) {
            RepCategory selected = RepCategoryGuiSupport.categoryAt(RepCategoryGuiSupport.LEADERBOARD_SELECTOR_SLOTS, slot);
            open(player, holder.lowest(), selected, 0);
            return;
        }
        if (slot == PREVIOUS_SLOT) {
            open(player, holder.lowest(), holder.category(), holder.page() - 1);
            return;
        }
        if (slot == NEXT_SLOT) {
            open(player, holder.lowest(), holder.category(), holder.page() + 1);
            return;
        }
        int relative = ENTRY_SLOTS.indexOf(slot);
        if (relative < 0) return;
        List<Map.Entry<UUID, Integer>> entries = repService.leaderboard(holder.category(), holder.lowest());
        int absolute = holder.page() * ENTRY_SLOTS.size() + relative;
        if (absolute < 0 || absolute >= entries.size()) return;
        OfflinePlayer target = Bukkit.getOfflinePlayer(entries.get(absolute).getKey());
        plugin.getRepGuiManager().openProfile(player, target, holder.category());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof LeaderboardHolder) event.setCancelled(true);
    }

    private ItemStack playerItem(UUID playerId, int value, int rank, RepCategory category) {
        ItemStack item = HeadUtil.createPlayerHead(plugin, playerId, ChatColor.GOLD + repService.nameOf(playerId));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Rank: " + ChatColor.YELLOW + "#" + rank);
            lore.add(ChatColor.GRAY + RepCategoryGuiSupport.displayName(category) + ": "
                    + RepCategoryGuiSupport.coloredValue(value));
            lore.add(ChatColor.YELLOW + "Click to open profile");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillBackground(Inventory inventory) {
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
    }

    private ItemStack button(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    private record LeaderboardHolder(boolean lowest, RepCategory category, int page) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
}
