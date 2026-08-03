package org.enthusia.rep.gui;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;
import org.enthusia.rep.rep.RepService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Shared category-selection, filtering, and total logic for reputation GUIs. */
final class RepCategoryGuiSupport {
    static final List<Integer> PROFILE_SELECTOR_SLOTS = List.of(0, 1, 2, 3, 5, 6, 7, 8, 46, 47, 51);
    static final List<Integer> LEADERBOARD_SELECTOR_SLOTS = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 46, 52);

    private RepCategoryGuiSupport() { }

    static List<RepCategory> categoryOptions() {
        return RepCategory.selectableValues();
    }

    static RepCategory categoryAt(List<Integer> slots, int rawSlot) {
        int index = slots.indexOf(rawSlot);
        if (index <= 0) {
            return null;
        }
        List<RepCategory> categories = categoryOptions();
        int categoryIndex = index - 1;
        return categoryIndex < categories.size() ? categories.get(categoryIndex) : null;
    }

    static boolean isSelectorSlot(List<Integer> slots, int rawSlot) {
        return slots.contains(rawSlot);
    }

    static List<Commendation> filter(List<Commendation> entries, RepCategory selected) {
        if (selected == null) {
            return entries == null ? List.of() : List.copyOf(entries);
        }
        RepCategory canonical = selected.migratedCategory();
        return entries == null ? List.of() : entries.stream()
                .filter(entry -> entry.getCategory().migratedCategory() == canonical)
                .toList();
    }

    static int total(RepService service, UUID playerId, RepCategory selected) {
        return selected == null ? service.getScore(playerId) : service.getCategoryScore(playerId, selected);
    }

    static String displayName(RepCategory selected) {
        return selected == null ? "Overall Reputation" : selected.displayName();
    }

    static void renderSelectors(org.bukkit.inventory.Inventory inventory, List<Integer> slots,
                                RepService service, UUID targetId, RepCategory selected, String totalLabel) {
        for (int index = 0; index < slots.size(); index++) {
            RepCategory option = index == 0 ? null : categoryOptions().get(index - 1);
            int total = total(service, targetId, option);
            inventory.setItem(slots.get(index), selectorItem(option, total, option == selected, totalLabel));
        }
    }

    private static ItemStack selectorItem(RepCategory category, int total, boolean selected, String totalLabel) {
        Material material = category == null ? Material.NETHER_STAR : category.icon();
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        String name = displayName(category);
        meta.setDisplayName((selected ? ChatColor.GREEN + "Selected: " : ChatColor.GOLD) + name);
        List<String> lore = new ArrayList<>();
        if (category != null) {
            lore.add(ChatColor.GRAY + category.description());
        } else {
            lore.add(ChatColor.GRAY + "All positive and negative reputation combined.");
        }
        lore.add(ChatColor.GRAY + totalLabel + ": " + coloredValue(total));
        lore.add(selected ? ChatColor.GREEN + "Currently selected" : ChatColor.YELLOW + "Click to view");
        meta.setLore(lore);
        if (selected) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        item.setItemMeta(meta);
        return item;
    }

    static String coloredValue(int value) {
        return (value > 0 ? ChatColor.GREEN : value < 0 ? ChatColor.RED : ChatColor.YELLOW)
                + (value > 0 ? "+" + value : Integer.toString(value));
    }
}
