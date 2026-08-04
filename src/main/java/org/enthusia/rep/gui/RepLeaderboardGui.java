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
    private static final int POSITIVE_FILTER_SLOT = 2;
    private static final int CURRENT_VIEW_SLOT = 4;
    private static final int NEGATIVE_FILTER_SLOT = 6;
    private static final int PREVIOUS_SLOT = 45;
    private static final int NEXT_SLOT = 53;
    private static final int FILTER_BACK_SLOT = 22;
    private static final List<Integer> FILTER_OPTION_SLOTS = List.of(10, 11, 12, 14, 15, 16);

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
        RepCategory selected = canonicalCategory(category);
        List<Map.Entry<UUID, Integer>> entries = repService.leaderboard(selected, lowest);
        int maxPage = Math.max(0, (entries.size() - 1) / ENTRY_SLOTS.size());
        int resolvedPage = Math.max(0, Math.min(page, maxPage));
        int start = resolvedPage * ENTRY_SLOTS.size();
        List<UUID> visiblePlayerIds = entries.stream()
                .skip(start)
                .limit(ENTRY_SLOTS.size())
                .map(Map.Entry::getKey)
                .toList();
        String direction = lowest ? "Lowest" : "Top";
        String viewName = RepCategoryGuiSupport.displayName(selected);
        Inventory inventory = Bukkit.createInventory(
                new LeaderboardHolder(lowest, selected, resolvedPage, visiblePlayerIds), 54,
                ChatColor.DARK_GREEN + direction + " Rep: " + ChatColor.RESET + viewName
                        + ChatColor.GRAY + " [" + (resolvedPage + 1) + "/" + (maxPage + 1) + "]");
        fillBackground(inventory);
        inventory.setItem(POSITIVE_FILTER_SLOT, categoryMenuButton(true, selected));
        inventory.setItem(CURRENT_VIEW_SLOT, currentViewButton(viewer, selected));
        inventory.setItem(NEGATIVE_FILTER_SLOT, categoryMenuButton(false, selected));

        if (entries.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, emptyStateLabel(selected), List.of(
                    ChatColor.DARK_GRAY + "Choose another filter to view a different ranking.")));
        }

        for (int index = 0; index < visiblePlayerIds.size(); index++) {
            Map.Entry<UUID, Integer> entry = entries.get(start + index);
            inventory.setItem(ENTRY_SLOTS.get(index), playerItem(entry.getKey(), entry.getValue(),
                    start + index + 1, selected));
        }
        if (resolvedPage > 0) {
            inventory.setItem(PREVIOUS_SLOT, button(Material.ARROW, ChatColor.YELLOW + "Previous page", List.of()));
        }
        if (resolvedPage < maxPage) {
            inventory.setItem(NEXT_SLOT, button(Material.ARROW, ChatColor.YELLOW + "Next page", List.of()));
        }
        viewer.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof LeaderboardHolder) && !(holder instanceof LeaderboardFilterHolder)) {
            return;
        }
        event.setCancelled(true);
        event.setResult(Event.Result.DENY);
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) {
            return;
        }

        if (holder instanceof LeaderboardHolder leaderboard) {
            handleLeaderboardClick(player, leaderboard, event.getRawSlot());
        } else if (holder instanceof LeaderboardFilterHolder filter) {
            handleFilterClick(player, filter, event.getRawSlot());
        }
    }

    private void handleLeaderboardClick(Player player, LeaderboardHolder holder, int slot) {
        if (slot == POSITIVE_FILTER_SLOT) {
            openFilterMenu(player, holder.lowest(), true, holder.category(), holder.page());
            return;
        }
        if (slot == NEGATIVE_FILTER_SLOT) {
            openFilterMenu(player, holder.lowest(), false, holder.category(), holder.page());
            return;
        }
        if (slot == CURRENT_VIEW_SLOT && holder.category() != null) {
            open(player, holder.lowest(), null, 0);
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
        UUID targetId = GuiSnapshotTargets.at(holder.visiblePlayerIds(), relative);
        if (targetId == null) {
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
        plugin.getRepGuiManager().openProfile(player, target, holder.category());
    }

    private void handleFilterClick(Player player, LeaderboardFilterHolder holder, int slot) {
        if (slot == FILTER_BACK_SLOT) {
            open(player, holder.lowest(), holder.returnCategory(), holder.returnPage());
            return;
        }
        if (slot == CURRENT_VIEW_SLOT && holder.returnCategory() != null) {
            open(player, holder.lowest(), null, 0);
            return;
        }
        if (!isFilterOptionSlot(slot)) {
            return;
        }
        open(player, holder.lowest(), filterCategoryAt(holder.positive(), slot), 0);
    }

    private void openFilterMenu(Player viewer, boolean lowest, boolean positive,
                                RepCategory returnCategory, int returnPage) {
        Inventory inventory = Bukkit.createInventory(
                new LeaderboardFilterHolder(lowest, positive, canonicalCategory(returnCategory), returnPage), 27,
                positive ? ChatColor.GREEN + "Positive Rep Filters" : ChatColor.RED + "Negative Rep Filters");
        fillBackground(inventory);
        inventory.setItem(CURRENT_VIEW_SLOT, currentViewButton(viewer, returnCategory));

        inventory.setItem(FILTER_OPTION_SLOTS.get(0), filterChoiceButton(viewer, null, returnCategory));
        List<RepCategory> options = categories(positive);
        for (int index = 0; index < options.size(); index++) {
            inventory.setItem(FILTER_OPTION_SLOTS.get(index + 1),
                    filterChoiceButton(viewer, options.get(index), returnCategory));
        }
        inventory.setItem(FILTER_BACK_SLOT, button(Material.ARROW, ChatColor.YELLOW + "Back to leaderboard",
                List.of(ChatColor.GRAY + "Return without changing the filter.")));
        viewer.openInventory(inventory);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof LeaderboardHolder || holder instanceof LeaderboardFilterHolder) {
            event.setCancelled(true);
        }
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

    private ItemStack currentViewButton(Player viewer, RepCategory category) {
        RepCategory selected = canonicalCategory(category);
        List<String> lore = new ArrayList<>();
        if (selected == null) {
            lore.add(ChatColor.GRAY + "All positive and negative reputation combined.");
        } else {
            lore.add(ChatColor.GRAY + selected.description());
        }
        lore.add(ChatColor.GRAY + "Your score: " + RepCategoryGuiSupport.coloredValue(
                RepCategoryGuiSupport.total(repService, viewer.getUniqueId(), selected)));
        if (selected == null) {
            lore.add(ChatColor.GREEN + "Currently viewing the overall leaderboard.");
        } else {
            lore.add(ChatColor.YELLOW + "Click to return to overall reputation.");
        }
        return button(selected == null ? Material.NETHER_STAR : selected.icon(),
                ChatColor.GOLD + "Viewing: " + RepCategoryGuiSupport.displayName(selected), lore);
    }

    private ItemStack categoryMenuButton(boolean positive, RepCategory selected) {
        boolean active = selected != null && selected.isPositive() == positive;
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Choose from " + categories(positive).size() + " "
                + (positive ? "positive" : "negative") + " reputation categories.");
        if (active) {
            lore.add(ChatColor.GREEN + "Current: " + selected.displayName());
        } else {
            lore.add(ChatColor.YELLOW + "Click to choose a category.");
        }
        return button(positive ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                (positive ? ChatColor.GREEN : ChatColor.RED)
                        + (positive ? "Positive Categories" : "Negative Categories"), lore);
    }

    private ItemStack filterChoiceButton(Player viewer, RepCategory option, RepCategory selected) {
        RepCategory category = canonicalCategory(option);
        RepCategory current = canonicalCategory(selected);
        boolean active = category == current;
        List<String> lore = new ArrayList<>();
        if (category == null) {
            lore.add(ChatColor.GRAY + "All positive and negative reputation combined.");
        } else {
            lore.add(ChatColor.GRAY + category.description());
        }
        lore.add(ChatColor.GRAY + "Your score: " + RepCategoryGuiSupport.coloredValue(
                RepCategoryGuiSupport.total(repService, viewer.getUniqueId(), category)));
        lore.add(active ? ChatColor.GREEN + "Currently selected" : ChatColor.YELLOW + "Click to view");
        return button(category == null ? Material.NETHER_STAR : category.icon(),
                (active ? ChatColor.GREEN + "Selected: " : ChatColor.GOLD)
                        + RepCategoryGuiSupport.displayName(category), lore);
    }

    static String emptyStateLabel(RepCategory category) {
        return category == null
                ? ChatColor.YELLOW + "No reputation entries yet."
                : ChatColor.YELLOW + "No entries in this category.";
    }

    static List<RepCategory> categories(boolean positive) {
        return RepCategory.selectableValues().stream()
                .filter(category -> category.isPositive() == positive)
                .toList();
    }

    static boolean isFilterOptionSlot(int rawSlot) {
        return FILTER_OPTION_SLOTS.contains(rawSlot);
    }

    static RepCategory filterCategoryAt(boolean positive, int rawSlot) {
        int optionIndex = FILTER_OPTION_SLOTS.indexOf(rawSlot);
        if (optionIndex <= 0) {
            return null;
        }
        List<RepCategory> options = categories(positive);
        int categoryIndex = optionIndex - 1;
        return categoryIndex < options.size() ? options.get(categoryIndex) : null;
    }

    private static RepCategory canonicalCategory(RepCategory category) {
        return category == null ? null : category.migratedCategory();
    }

    private void fillBackground(Inventory inventory) {
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private record LeaderboardHolder(boolean lowest, RepCategory category, int page,
                                     List<UUID> visiblePlayerIds) implements InventoryHolder {
        private LeaderboardHolder {
            category = canonicalCategory(category);
            visiblePlayerIds = visiblePlayerIds == null ? List.of() : List.copyOf(visiblePlayerIds);
        }

        @Override public Inventory getInventory() { return null; }
    }

    private record LeaderboardFilterHolder(boolean lowest, boolean positive, RepCategory returnCategory,
                                           int returnPage) implements InventoryHolder {
        private LeaderboardFilterHolder {
            returnCategory = canonicalCategory(returnCategory);
            returnPage = Math.max(0, returnPage);
        }

        @Override public Inventory getInventory() { return null; }
    }
}
