package org.enthusia.rep.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.CommendPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class Messages {

    private final CommendPlugin plugin;
    private File file;
    private FileConfiguration config;

    public Messages(CommendPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
    }

    public void reload() {
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

    public FileConfiguration raw() {
        return config;
    }

    public String get(String path) {
        if (config == null) reload();
        String msg = config.getString(path);
        if (msg == null) {
            msg = "&cMissing message: &7" + path;
        }
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    public String get(String path, Map<String, String> placeholders) {
        String msg = get(path);
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            msg = msg.replace("{" + e.getKey() + "}", e.getValue());
        }
        return msg;
    }

    public void save() {
        if (config == null) return;
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save messages.yml: " + e.getMessage());
        }
    }
}
