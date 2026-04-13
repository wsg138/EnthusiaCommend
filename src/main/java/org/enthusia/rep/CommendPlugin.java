package org.enthusia.rep;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.rep.command.CommendCommand;
import org.enthusia.rep.config.Messages;
import org.enthusia.rep.config.RepConfig;
import org.enthusia.rep.effects.RepEffectManager;
import org.enthusia.rep.gui.RepGuiManager;
import org.enthusia.rep.integration.TeleportIntegration;
import org.enthusia.rep.placeholder.RepPlaceholderExpansion;
import org.enthusia.rep.playtime.PlaytimeService;
import org.enthusia.rep.region.RegionManager;
import org.enthusia.rep.rep.RepService;
import org.enthusia.rep.skin.SkinCache;
import org.enthusia.rep.skin.SkinListener;
import org.enthusia.rep.stalk.StalkManager;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class CommendPlugin extends JavaPlugin {

    private static CommendPlugin instance;

    private RepConfig repConfig;
    private RegionManager regionManager;
    private RepService repService;
    private RepEffectManager effectManager;
    private Economy economy;
    private StalkManager stalkManager;
    private Messages messages;
    private RepGuiManager repGuiManager;
    private TeleportIntegration teleportIntegration;
    private PlaytimeService playtimeService;
    private SkinCache skinCache;

    public static CommendPlugin getInstance() {
        return instance;
    }

    public RepConfig getRepConfig() {
        return repConfig;
    }

    public RegionManager getRegionManager() {
        return regionManager;
    }

    public RepService getRepService() {
        return repService;
    }

    public RepEffectManager getEffectManager() {
        return effectManager;
    }

    public StalkManager getStalkManager() {
        return stalkManager;
    }

    public Economy getEconomy() {
        return economy;
    }

    public Messages getMessages() {
        return messages;
    }

    public RepGuiManager getRepGuiManager() {
        return repGuiManager;
    }

    public TeleportIntegration getTeleportIntegration() {
        return teleportIntegration;
    }

    public PlaytimeService getPlaytimeService() {
        return playtimeService;
    }

    public SkinCache getSkinCache() {
        return skinCache;
    }

    @Override
    public void onEnable() {
        instance = this;

        // Config + messages
        saveDefaultConfig();
        boolean configUpdated = mergeMissingConfigDefaults();
        this.repConfig = new RepConfig(getConfig());
        this.messages = new Messages(this);
        this.messages.reload();
        if (configUpdated) {
            getLogger().info("config.yml was missing settings and has been updated with defaults.");
        }

        // Core services
        this.regionManager = new RegionManager(this);
        this.repService = new RepService(this);
        this.effectManager = new RepEffectManager(this, repConfig, regionManager, repService);
        this.stalkManager = new StalkManager(this, regionManager);
        this.skinCache = new SkinCache(this);
        this.skinCache.load();
        getServer().getPluginManager().registerEvents(new SkinListener(skinCache), this);
        this.repGuiManager = new RepGuiManager(this, repService, effectManager); // also registers its own listeners
        this.teleportIntegration = new TeleportIntegration(this, repService);
        this.teleportIntegration.refresh();
        this.playtimeService = new PlaytimeService(this);

        // Economy (Vault)
        if (!setupEconomy()) {
            getLogger().warning("Vault/Economy not found! Stalk/cashback money hooks may not work.");
        }

        // === REP COMMAND (/rep) ===
        CommendCommand repCmd = new CommendCommand(this, repService);
        PluginCommand repCommand = getCommand("rep");
        if (repCommand != null) {
            repCommand.setExecutor(repCmd);
            repCommand.setTabCompleter(repCmd);
        } else {
            getLogger().warning("Command 'rep' is not defined in plugin.yml – /rep will not work.");
        }

        // Periodic effect updater
        Bukkit.getScheduler().runTaskTimer(this,
                () -> {
                    effectManager.tickEffects();
                    if (teleportIntegration != null) {
                        teleportIntegration.tick();
                    }
                },
                20L, 20L);

        // PlaceholderAPI
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new RepPlaceholderExpansion(repService, repConfig).register();
            getLogger().info("Registered PlaceholderAPI expansion for EnthusiaRep.");
        }

        getLogger().info("EnthusiaCommend enabled.");
    }

    @Override
    public void onDisable() {
        if (effectManager != null) {
            effectManager.clearAll();
        }
        if (repService != null) {
            repService.saveAll();
        }
        if (skinCache != null) {
            skinCache.save();
        }
        instance = null;
    }

    private boolean setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    public void reloadPluginConfig() {
        reloadConfig();
        boolean configUpdated = mergeMissingConfigDefaults();
        this.repConfig = new RepConfig(getConfig());
        this.regionManager.reload(this);
        this.effectManager.reload(repConfig);
        if (repService != null) {
            repService.setRepConfig(repConfig);
        }
        if (messages != null) {
            messages.reload();
        }
        if (teleportIntegration != null) {
            teleportIntegration.refresh();
        }
        if (configUpdated) {
            getLogger().info("config.yml was missing settings and has been updated with defaults.");
        }
    }

    private boolean mergeMissingConfigDefaults() {
        FileConfiguration config = getConfig();
        try (InputStream in = getResource("config.yml")) {
            if (in == null) {
                getLogger().warning("Default config.yml is missing from the jar; cannot merge defaults.");
                return false;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            boolean changed = mergeMissingSections(config, defaults);
            if (changed) {
                saveConfig();
            }
            return changed;
        } catch (Exception ex) {
            getLogger().warning("Failed to merge default config.yml: " + ex.getMessage());
            return false;
        }
    }

    private boolean mergeMissingSections(ConfigurationSection target, ConfigurationSection defaults) {
        boolean updated = false;
        for (String key : defaults.getKeys(false)) {
            ConfigurationSection defaultChild = defaults.getConfigurationSection(key);
            if (defaultChild != null) {
                ConfigurationSection targetChild = target.getConfigurationSection(key);
                if (targetChild == null) {
                    targetChild = target.createSection(key);
                    updated = true;
                }
                if (mergeMissingSections(targetChild, defaultChild)) {
                    updated = true;
                }
                continue;
            }
            if (!target.isSet(key)) {
                target.set(key, defaults.get(key));
                updated = true;
            }
        }
        return updated;
    }
}
