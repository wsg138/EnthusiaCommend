package org.enthusia.rep;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
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
import org.enthusia.rep.storage.PluginDataSnapshot;
import org.enthusia.rep.storage.PluginDataStore;
import org.enthusia.rep.storage.YamlPluginDataStore;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CommendPlugin extends JavaPlugin {

    private RepConfig repConfig;
    private Messages messages;
    private RegionManager regionManager;
    private PlaytimeService playtimeService;
    private RepService repService;
    private RepEffectManager effectManager;
    private StalkManager stalkManager;
    private RepGuiManager repGuiManager;
    private TeleportIntegration teleportIntegration;
    private SkinCache skinCache;
    private Economy economy;
    private PluginDataStore dataStore;
    private BukkitTask autoSaveTask;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final Object saveLock = new Object();

    public RepConfig getRepConfig() {
        return repConfig;
    }

    public Messages getMessages() {
        return messages;
    }

    public RegionManager getRegionManager() {
        return regionManager;
    }

    public PlaytimeService getPlaytimeService() {
        return playtimeService;
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

    public RepGuiManager getRepGuiManager() {
        return repGuiManager;
    }

    public TeleportIntegration getTeleportIntegration() {
        return teleportIntegration;
    }

    public SkinCache getSkinCache() {
        return skinCache;
    }

    public Economy getEconomy() {
        return economy;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mergeMissingConfigDefaults();

        this.repConfig = new RepConfig(getConfig());
        this.messages = new Messages(this);
        this.messages.reload();
        this.dataStore = new YamlPluginDataStore(this);

        PluginDataSnapshot snapshot = dataStore.load();
        this.regionManager = new RegionManager(this);
        this.playtimeService = new PlaytimeService(repConfig);
        this.repService = new RepService(this, repConfig, snapshot, this::markDirty, this::handleScoreChanged);
        this.stalkManager = new StalkManager(regionManager, repService, repConfig, this::markDirty);
        this.stalkManager.load(snapshot);
        this.effectManager = new RepEffectManager(this, repConfig, regionManager, repService);
        this.teleportIntegration = new TeleportIntegration(this, repService);
        this.skinCache = new SkinCache(this);
        this.skinCache.load();
        this.repGuiManager = new RepGuiManager(this, repService, effectManager);

        getServer().getPluginManager().registerEvents(new SkinListener(skinCache), this);
        getServer().getPluginManager().registerEvents(stalkManager, this);
        getServer().getPluginManager().registerEvents(repGuiManager, this);
        effectManager.register(getServer().getPluginManager());
        teleportIntegration.register();

        setupEconomy();
        registerCommands();
        registerPlaceholderExpansion();

        teleportIntegration.refresh();
        effectManager.refreshAll();
        startAutoSaveTask();

        getLogger().info("EnthusiaCommend enabled.");
    }

    @Override
    public void onDisable() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
        if (repGuiManager != null) {
            repGuiManager.shutdown();
        }
        if (teleportIntegration != null) {
            teleportIntegration.shutdown();
        }
        if (effectManager != null) {
            effectManager.clearAll();
        }
        flushDataSync();
        if (skinCache != null) {
            skinCache.save();
        }
    }

    public void reloadPluginConfig() {
        if (repGuiManager != null) {
            repGuiManager.cancelOpenAnvilSessions(org.bukkit.ChatColor.YELLOW + "Rep anvil input was closed because the plugin reloaded.");
        }
        reloadConfig();
        mergeMissingConfigDefaults();
        this.repConfig = new RepConfig(getConfig());
        this.messages.reload();
        this.regionManager.reload(getConfig(), this);
        this.playtimeService.reload(repConfig);
        this.repService.reload(repConfig);
        this.stalkManager.reload(repConfig);
        this.effectManager.reload(repConfig);
        this.teleportIntegration.refresh();
    }

    private void registerCommands() {
        CommendCommand commendCommand = new CommendCommand(this, repService);
        PluginCommand repCommand = getCommand("rep");
        if (repCommand == null) {
            getLogger().severe("Command 'rep' is missing from plugin.yml.");
            return;
        }
        repCommand.setExecutor(commendCommand);
        repCommand.setTabCompleter(commendCommand);
    }

    private void registerPlaceholderExpansion() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new RepPlaceholderExpansion(this).register();
        }
    }

    private void setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found; economy-backed features are disabled.");
            return;
        }
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            getLogger().warning("Vault economy provider not found; economy-backed features are disabled.");
            return;
        }
        this.economy = provider.getProvider();
    }

    private void startAutoSaveTask() {
        autoSaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!dirty.compareAndSet(true, false)) {
                return;
            }
            PluginDataSnapshot snapshot = buildSnapshot();
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                synchronized (saveLock) {
                    dataStore.save(snapshot);
                }
            });
        }, repConfig.getAutoSaveIntervalTicks(), repConfig.getAutoSaveIntervalTicks());
    }

    private void flushDataSync() {
        if (dataStore == null || repService == null || stalkManager == null) {
            return;
        }
        synchronized (saveLock) {
            dataStore.save(buildSnapshot());
        }
        dirty.set(false);
    }

    private PluginDataSnapshot buildSnapshot() {
        PluginDataSnapshot stalkSnapshot = new PluginDataSnapshot(
                java.util.Map.of(),
                java.util.List.of(),
                java.util.List.of(),
                stalkManager.snapshotEntries()
        );
        return repService.snapshot(stalkSnapshot);
    }

    private void markDirty() {
        dirty.set(true);
    }

    private void handleScoreChanged(java.util.UUID playerId) {
        if (effectManager != null) {
            effectManager.handleScoreChanged(playerId);
        }
        if (teleportIntegration != null) {
            teleportIntegration.updatePlayer(playerId);
        }
        markDirty();
    }

    private void mergeMissingConfigDefaults() {
        FileConfiguration config = getConfig();
        try (InputStream inputStream = getResource("config.yml")) {
            if (inputStream == null) {
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            if (mergeMissingSections(config, defaults)) {
                saveConfig();
            }
        } catch (Exception ex) {
            getLogger().warning("Failed to merge config defaults: " + ex.getMessage());
        }
    }

    private boolean mergeMissingSections(ConfigurationSection target, ConfigurationSection defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            ConfigurationSection defaultChild = defaults.getConfigurationSection(key);
            if (defaultChild != null) {
                ConfigurationSection targetChild = target.getConfigurationSection(key);
                if (targetChild == null) {
                    targetChild = target.createSection(key);
                    changed = true;
                }
                if (mergeMissingSections(targetChild, defaultChild)) {
                    changed = true;
                }
            } else if (!target.isSet(key)) {
                target.set(key, defaults.get(key));
                changed = true;
            }
        }
        return changed;
    }
}
