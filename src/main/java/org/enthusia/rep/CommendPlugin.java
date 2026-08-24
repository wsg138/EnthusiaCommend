package org.enthusia.rep;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.rep.analytics.ReputationAnalyticsService;
import org.enthusia.rep.api.ReputationModerationApi;
import org.enthusia.rep.command.CommendCommand;
import org.enthusia.rep.config.Messages;
import org.enthusia.rep.config.RepConfig;
import org.enthusia.rep.discord.DiscordWebhookService;
import org.enthusia.rep.discord.MinecraftHeadUrl;
import org.enthusia.rep.effects.RepEffectManager;
import org.enthusia.rep.gui.RepGuiManager;
import org.enthusia.rep.gui.RepLeaderboardGui;
import org.enthusia.rep.integration.TeleportIntegration;
import org.enthusia.rep.integration.WarzoneDuelsHook;
import org.enthusia.rep.integration.plan.PlanIntegrationBootstrap;
import org.enthusia.rep.moderation.ReputationModerationService;
import org.enthusia.rep.moderation.ReputationSnapshotFactory;
import org.enthusia.rep.placeholder.RepPlaceholderExpansion;
import org.enthusia.rep.playtime.PlaytimeService;
import org.enthusia.rep.region.RegionManager;
import org.enthusia.rep.rep.RepService;
import org.enthusia.rep.stalk.StalkManager;
import org.enthusia.rep.storage.PluginDataSnapshot;
import org.enthusia.rep.storage.PluginDataStore;
import org.enthusia.rep.storage.OrderedSnapshotWriter;
import org.enthusia.rep.storage.YamlPluginDataStore;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class CommendPlugin extends JavaPlugin {
    private RepConfig repConfig;
    private Messages messages;
    private RegionManager regionManager;
    private PlaytimeService playtimeService;
    private RepService repService;
    private ReputationModerationService moderationService;
    private ReputationAnalyticsService analyticsService;
    private RepEffectManager effectManager;
    private StalkManager stalkManager;
    private RepGuiManager repGuiManager;
    private RepLeaderboardGui repLeaderboardGui;
    private TeleportIntegration teleportIntegration;
    private WarzoneDuelsHook warzoneDuelsHook;
    private PlanIntegrationBootstrap planIntegration;
    private DiscordWebhookService discordWebhookService;
    private Economy economy;
    private PluginDataStore dataStore;
    private BukkitTask autoSaveTask;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicLong saveSequence = new AtomicLong();
    private OrderedSnapshotWriter snapshotWriter;

    public RepConfig getRepConfig() { return repConfig; }
    public Messages getMessages() { return messages; }
    public RegionManager getRegionManager() { return regionManager; }
    public PlaytimeService getPlaytimeService() { return playtimeService; }
    public RepService getRepService() { return repService; }
    public ReputationAnalyticsService getAnalyticsService() { return analyticsService; }
    public RepEffectManager getEffectManager() { return effectManager; }
    public StalkManager getStalkManager() { return stalkManager; }
    public RepGuiManager getRepGuiManager() { return repGuiManager; }
    public RepLeaderboardGui getRepLeaderboardGui() { return repLeaderboardGui; }
    public TeleportIntegration getTeleportIntegration() { return teleportIntegration; }
    public WarzoneDuelsHook getWarzoneDuelsHook() { return warzoneDuelsHook; }
    public Economy getEconomy() { return economy; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mergeMissingConfigDefaults();

        this.repConfig = new RepConfig(getConfig());
        this.messages = new Messages(this);
        this.messages.reload();
        this.dataStore = new YamlPluginDataStore(this);
        this.snapshotWriter = new OrderedSnapshotWriter(dataStore);
        this.discordWebhookService = new DiscordWebhookService(repConfig.getDiscordWebhookUrl(), getLogger());

        PluginDataSnapshot snapshot = dataStore.load();
        this.regionManager = new RegionManager(this);
        this.playtimeService = new PlaytimeService(repConfig);
        this.analyticsService = new ReputationAnalyticsService(() -> this.repConfig, snapshot.reputationChanges(), this::markDirty);
        this.repService = new RepService(
                this,
                repConfig,
                snapshot,
                this::markDirty,
                playerId -> handleScoreChanged(playerId),
                analyticsService,
                record -> handleAuditRecord(record)
        );
        this.moderationService = new ReputationModerationService(
                Clock.systemUTC(),
                playerId -> ReputationSnapshotFactory.snapshot(repService, playerId),
                getDataFolder().toPath().resolve("moderation-state.yml")
        );
        this.repService.setGrantPolicy(moderationService::canGiveReputation);
        getServer().getServicesManager().register(
                ReputationModerationApi.class,
                moderationService,
                this,
                ServicePriority.Normal
        );
        for (var player : Bukkit.getOnlinePlayers()) {
            repService.rememberName(player.getUniqueId(), player.getName());
        }
        this.stalkManager = new StalkManager(this, regionManager, repService, repConfig, this::markDirty);
        this.stalkManager.load(snapshot);
        this.warzoneDuelsHook = new WarzoneDuelsHook(this);
        this.warzoneDuelsHook.refresh();
        this.effectManager = new RepEffectManager(this, repConfig, regionManager, repService, warzoneDuelsHook);
        this.teleportIntegration = new TeleportIntegration(this, repService);
        this.repGuiManager = new RepGuiManager(this, repService, effectManager);
        this.repLeaderboardGui = new RepLeaderboardGui(this, repService);

        getServer().getPluginManager().registerEvents(stalkManager, this);
        getServer().getPluginManager().registerEvents(repGuiManager, this);
        getServer().getPluginManager().registerEvents(repLeaderboardGui, this);
        effectManager.register(getServer().getPluginManager());
        teleportIntegration.register();

        setupEconomy();
        registerCommands();
        registerPlaceholderExpansion();

        teleportIntegration.refresh();
        effectManager.refreshAll();
        restartAutoSaveTask();
        registerPlanIntegration();

        getLogger().info("EnthusiaCommend enabled.");
    }

    @Override
    public void onDisable() {
        cancelAutoSaveTask();
        getServer().getServicesManager().unregisterAll(this);
        if (repGuiManager != null) {
            repGuiManager.shutdown();
        }
        if (teleportIntegration != null) {
            teleportIntegration.shutdown();
        }
        if (effectManager != null) {
            effectManager.clearAll();
        }
        if (planIntegration != null) {
            planIntegration.shutdown();
        }
        flushDataSync();
        closeDiscordWebhook();
    }

    public void reloadPluginConfig() {
        if (repGuiManager != null) {
            repGuiManager.cancelOpenAnvilSessions(org.bukkit.ChatColor.YELLOW
                    + "Rep anvil input was closed because the plugin reloaded.");
        }
        reloadConfig();
        mergeMissingConfigDefaults();
        this.repConfig = new RepConfig(getConfig());
        this.messages.reload();
        this.regionManager.reload(getConfig(), this);
        this.playtimeService.reload(repConfig);
        this.repService.reload(repConfig);
        if (analyticsService != null) {
            analyticsService.pruneExpired(true);
        }
        this.stalkManager.reload(repConfig);
        this.effectManager.reload(repConfig);
        this.warzoneDuelsHook.refresh();
        this.teleportIntegration.refresh();
        reloadDiscordWebhook();
        restartAutoSaveTask();
        registerPlanIntegration();
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
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().info("PlaceholderAPI is not enabled; skipping EnthusiaCommend placeholder registration.");
            return;
        }
        RepPlaceholderExpansion expansion = new RepPlaceholderExpansion(this);
        if (!expansion.register()) {
            getLogger().warning("Failed to register EnthusiaCommend PlaceholderAPI expansion.");
            return;
        }
        getLogger().info("Registered EnthusiaCommend PlaceholderAPI expansion (%enthusiarep_*%).");
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

    private void restartAutoSaveTask() {
        cancelAutoSaveTask();
        long interval = repConfig.getAutoSaveIntervalTicks();
        autoSaveTask = Bukkit.getScheduler().runTaskTimer(this, this::queueDirtySave, interval, interval);
    }

    private void cancelAutoSaveTask() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
    }

    private void queueDirtySave() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        PluginDataSnapshot snapshot = buildSnapshot();
        long sequence = saveSequence.incrementAndGet();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> saveSnapshot(snapshot, sequence));
    }

    private void flushDataSync() {
        if (dataStore == null || repService == null || stalkManager == null) {
            return;
        }
        long sequence = saveSequence.incrementAndGet();
        OrderedSnapshotWriter.SaveResult result = snapshotWriter.saveIfNewer(sequence, buildSnapshot());
        dirty.set(result == OrderedSnapshotWriter.SaveResult.FAILED);
    }

    private void saveSnapshot(PluginDataSnapshot snapshot, long sequence) {
        OrderedSnapshotWriter.SaveResult result = snapshotWriter.saveIfNewer(sequence, snapshot);
        if (result == OrderedSnapshotWriter.SaveResult.FAILED) {
            dirty.set(true);
        }
    }

    private PluginDataSnapshot buildSnapshot() {
        PluginDataSnapshot stalkSnapshot = new PluginDataSnapshot(
                java.util.Map.of(),
                java.util.List.of(),
                java.util.List.of(),
                stalkManager.snapshotEntries(),
                java.util.List.of(),
                java.util.List.of()
        );
        PluginDataSnapshot repSnapshot = repService.snapshot(stalkSnapshot);
        return new PluginDataSnapshot(
                repSnapshot.scores(),
                repSnapshot.commendations(),
                repSnapshot.removedEntries(),
                repSnapshot.stalkEntries(),
                analyticsService != null ? analyticsService.snapshot() : java.util.List.of(),
                repSnapshot.suspiciousCases(),
                repSnapshot.removalCooldowns(),
                repSnapshot.repTradingAlertPreferences()
        );
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

    private void handleAuditRecord(RepService.AuditRecord record) {
        if (record == null || discordWebhookService == null || !discordWebhookService.isEnabled()) return;
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(this, () -> handleAuditRecord(record));
            return;
        }
        var commendation = record.commendation();
        String giverName = repService.nameOf(commendation.getGiver());
        String targetName = repService.nameOf(commendation.getTarget());
        DiscordWebhookService.Action action = switch (record.action()) {
            case CREATED -> DiscordWebhookService.Action.CREATED;
            case UPDATED -> DiscordWebhookService.Action.UPDATED;
            case REMOVED -> DiscordWebhookService.Action.REMOVED;
            case RESTORED -> DiscordWebhookService.Action.RESTORED;
        };
        String actorName = record.actorName();
        if (actorName == null || actorName.isBlank()) {
            actorName = action == DiscordWebhookService.Action.CREATED
                    || action == DiscordWebhookService.Action.UPDATED ? giverName : "Administrator";
        }
        java.util.UUID thumbnailId = action == DiscordWebhookService.Action.CREATED
                || action == DiscordWebhookService.Action.UPDATED
                ? commendation.getGiver() : record.actorId();
        String thumbnailUrl = thumbnailId == null ? null : MinecraftHeadUrl.resolve(thumbnailId, actorName);
        discordWebhookService.log(new DiscordWebhookService.LogEntry(
                action,
                actorName,
                giverName,
                targetName,
                commendation.getCategory(),
                commendation.getReasonText(),
                Instant.ofEpochMilli(record.timestamp()),
                thumbnailUrl
        ));
    }

    private void reloadDiscordWebhook() {
        closeDiscordWebhook();
        discordWebhookService = new DiscordWebhookService(repConfig.getDiscordWebhookUrl(), getLogger());
    }

    private void closeDiscordWebhook() {
        if (discordWebhookService != null) {
            discordWebhookService.close();
            discordWebhookService = null;
        }
    }

    private void registerPlanIntegration() {
        if (!repConfig.isPlanIntegrationEnabled()) {
            if (planIntegration != null) {
                planIntegration.shutdown();
            }
            return;
        }
        if (planIntegration == null) {
            planIntegration = new PlanIntegrationBootstrap(this);
        }
        planIntegration.register();
    }

    private void mergeMissingConfigDefaults() {
        FileConfiguration config = getConfig();
        try (InputStream inputStream = getResource("config.yml")) {
            if (inputStream == null) {
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            if (mergeMissingSections(config, defaults)) {
                saveConfig();
            }
        } catch (Exception exception) {
            getLogger().warning("Failed to merge config defaults: " + exception.getMessage());
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
