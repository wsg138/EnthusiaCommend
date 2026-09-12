package org.enthusia.rep.config;

import org.bukkit.configuration.file.FileConfiguration;
import java.util.ArrayList;
import java.util.List;

/** Preserve custom effect rules when the two positive categories become one. */
public final class KindCategoryMigration {
    private KindCategoryMigration() { }
    public static boolean migrate(FileConfiguration config) {
        String oldPath = "rep.effectRules.categories.HELPED_ME";
        String newPath = "rep.effectRules.categories.WAS_KIND";
        if (!config.isList(oldPath)) return false;
        List<Object> combined = new ArrayList<>(config.getList(newPath, List.of()));
        combined.addAll(config.getList(oldPath, List.of()));
        config.set(newPath, combined);
        config.set(oldPath, null);
        return true;
    }
}
