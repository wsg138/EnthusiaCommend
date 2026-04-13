package org.enthusia.rep.gui;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.skin.SkinCache;

import java.util.UUID;

/**
 * Helper for creating player heads that keep their owner data for persistence.
 */
public final class HeadUtil {

    private HeadUtil() {
    }

    public static ItemStack createPlayerHead(CommendPlugin plugin, UUID uuid, String displayName) {
        SkinCache cache = plugin.getSkinCache();
        ItemStack head = (cache != null)
                ? cache.createHead(uuid, displayName)
                : new ItemStack(Material.PLAYER_HEAD);

        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            NamespacedKey key = new NamespacedKey(plugin, "owner-uuid");
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, uuid.toString());
            head.setItemMeta(meta);
        }
        return head;
    }
}
