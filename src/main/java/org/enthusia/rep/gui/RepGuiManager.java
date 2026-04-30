package org.enthusia.rep.gui;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.persistence.PersistentDataType;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.effects.RepAppliedEffects;
import org.enthusia.rep.effects.RepEffectManager;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;
import org.enthusia.rep.rep.RepService;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class RepGuiManager implements Listener {

    private static final List<Integer> REVIEW_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    );

    private final CommendPlugin plugin;
    private final RepService repService;
    private final RepEffectManager effectManager;
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Map<UUID, PendingTextInput> pendingChatInputs = new HashMap<>();
    private final Map<UUID, Integer> pendingChatTimeoutTasks = new HashMap<>();
    private final Map<UUID, AnvilSession> pendingAnvils = new HashMap<>();
    private final Map<UUID, DraftReason> pendingDrafts = new HashMap<>();
    private final Map<UUID, ProfileContext> returnFromBook = new HashMap<>();
    private final Map<UUID, String> liveAnvilText = new HashMap<>();
    private final java.util.Set<UUID> transitioningAnvil = new java.util.HashSet<>();

    public RepGuiManager(CommendPlugin plugin, RepService repService, RepEffectManager effectManager) {
        this.plugin = plugin;
        this.repService = repService;
        this.effectManager = effectManager;
    }

    public void shutdown() {
        cancelOpenAnvilSessions(null);
        for (Integer taskId : pendingChatTimeoutTasks.values()) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        pendingChatInputs.clear();
        pendingChatTimeoutTasks.clear();
        pendingAnvils.clear();
        pendingDrafts.clear();
        returnFromBook.clear();
        liveAnvilText.clear();
        transitioningAnvil.clear();
    }

    public void cancelOpenAnvilSessions(String message) {
        List<UUID> playerIds = new ArrayList<>(pendingAnvils.keySet());
        for (UUID playerId : playerIds) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                if (message != null && !message.isBlank()) {
                    player.sendMessage(message);
                }
                if (isActiveAnvilSession(player, player.getOpenInventory())) {
                    player.closeInventory();
                }
            }
            pendingAnvils.remove(playerId);
            liveAnvilText.remove(playerId);
            transitioningAnvil.remove(playerId);
        }
    }

    public void openProfile(Player viewer, OfflinePlayer target) {
        openProfile(viewer, target, 0);
    }

    public void openProfile(Player viewer, OfflinePlayer target, int page) {
        Bukkit.getPluginManager().callEvent(new org.enthusia.rep.events.CommendationProfileViewedEvent(viewer.getUniqueId(), target.getUniqueId()));

        UUID targetId = target.getUniqueId();
        int score = repService.getScore(targetId);
        ChatColor scoreColor = plugin.getRepConfig().colorForScore(score);
        List<Commendation> reviews = repService.getCommendationsAbout(targetId).stream()
                .sorted(Comparator.comparingLong(Commendation::getCreatedAt).reversed())
                .toList();
        long positives = reviews.stream().filter(Commendation::isPositive).count();
        long negatives = reviews.size() - positives;

        int maxPage = Math.max(0, (reviews.size() - 1) / REVIEW_SLOTS.size());
        int resolvedPage = Math.max(0, Math.min(page, maxPage));

        Inventory inventory = Bukkit.createInventory(new ProfileHolder(targetId, resolvedPage), 54,
                ChatColor.DARK_GREEN + "Rep: " + ChatColor.RESET + safeName(target) + ChatColor.GRAY + " [" + (resolvedPage + 1) + "/" + (maxPage + 1) + "]");
        fillBackground(inventory, viewer);

        ItemStack head = HeadUtil.createPlayerHead(plugin, targetId, scoreColor + safeName(target));
        ItemMeta headMeta = head.getItemMeta();
        if (headMeta != null) {
            headMeta.setLore(List.of(
                    ChatColor.GRAY + "Total Rep: " + scoreColor + score,
                    ChatColor.GRAY + "Positives: " + ChatColor.GREEN + "+" + positives,
                    ChatColor.GRAY + "Negatives: " + ChatColor.RED + "-" + negatives
            ));
            head.setItemMeta(headMeta);
        }
        inventory.setItem(4, head);

        int start = resolvedPage * REVIEW_SLOTS.size();
        for (int i = 0; i < REVIEW_SLOTS.size(); i++) {
            int index = start + i;
            if (index >= reviews.size()) {
                break;
            }
            inventory.setItem(REVIEW_SLOTS.get(i), reviewItem(reviews.get(index), viewer.hasPermission("enthusiacommend.rep.admin")));
        }

        if (resolvedPage > 0) {
            inventory.setItem(45, simpleButton(Material.ARROW, ChatColor.YELLOW + "Prev", List.of()));
        }
        if (resolvedPage < maxPage) {
            inventory.setItem(53, simpleButton(Material.ARROW, ChatColor.YELLOW + "Next", List.of()));
        }

        if (viewer.getUniqueId().equals(targetId)) {
            RepAppliedEffects effects = effectManager.getCurrentEffects(targetId);
            inventory.setItem(49, simpleButton(Material.BOOK, ChatColor.AQUA + "Your Rep Effects", buildCurrentEffectsLore(effects)));
            inventory.setItem(48, simpleButton(Material.BARRIER, ChatColor.GRAY + "You cannot rep yourself", List.of()));
            inventory.setItem(50, simpleButton(Material.BARRIER, ChatColor.GRAY + "You cannot rep yourself", List.of()));
        } else {
            Commendation existing = repService.getCommendation(viewer.getUniqueId(), targetId);
            long remaining = cooldownRemaining(viewer.getUniqueId(), targetId, existing);
            if (remaining > 0L) {
                inventory.setItem(48, simpleButton(Material.BARRIER, ChatColor.RED + "On cooldown", buildGiveLore(existing, true, remaining)));
                inventory.setItem(50, simpleButton(Material.BARRIER, ChatColor.RED + "On cooldown", buildGiveLore(existing, false, remaining)));
            } else {
                inventory.setItem(48, simpleButton(Material.LIME_WOOL, ChatColor.GREEN + "Leave Positive", buildGiveLore(existing, true, 0L)));
                inventory.setItem(50, simpleButton(Material.RED_WOOL, ChatColor.RED + "Leave Negative", buildGiveLore(existing, false, 0L)));
            }
            if (existing != null) {
                inventory.setItem(49, simpleButton(Material.PAPER, ChatColor.YELLOW + "Remove my rep",
                        List.of(ChatColor.GRAY + "Click to remove your commendation", ChatColor.GRAY + "(applies cooldown)")));
            }
        }

        viewer.openInventory(inventory);
    }

    public void openRemovedLog(Player admin, int page) {
        List<RepService.RemovedRep> removed = repService.getRemovedLog().stream()
                .sorted(Comparator.comparingLong(RepService.RemovedRep::removedAt).reversed())
                .toList();
        int maxPage = Math.max(0, (removed.size() - 1) / REVIEW_SLOTS.size());
        int resolvedPage = Math.max(0, Math.min(page, maxPage));
        Inventory inventory = Bukkit.createInventory(new RemovedLogHolder(resolvedPage), 54,
                ChatColor.DARK_RED + "Removed Reps [" + (resolvedPage + 1) + "/" + (maxPage + 1) + "]");
        fillBackground(inventory, admin);

        int start = resolvedPage * REVIEW_SLOTS.size();
        for (int i = 0; i < REVIEW_SLOTS.size(); i++) {
            int index = start + i;
            if (index >= removed.size()) {
                break;
            }
            inventory.setItem(REVIEW_SLOTS.get(i), removedLogItem(removed.get(index)));
        }

        if (resolvedPage > 0) inventory.setItem(45, simpleButton(Material.ARROW, ChatColor.YELLOW + "Prev", List.of()));
        if (resolvedPage < maxPage) inventory.setItem(53, simpleButton(Material.ARROW, ChatColor.YELLOW + "Next", List.of()));
        admin.openInventory(inventory);
    }

    public void openActiveReports(Player admin, int page) {
        List<RepService.SuspiciousRepCase> cases = repService.getSuspiciousCases().stream()
                .filter(caseData -> !caseData.isResolved())
                .sorted(Comparator.comparingLong(RepService.SuspiciousRepCase::getCreatedAt).reversed())
                .toList();

        int maxPage = Math.max(0, (cases.size() - 1) / REVIEW_SLOTS.size());
        int resolvedPage = Math.max(0, Math.min(page, maxPage));
        Inventory inventory = Bukkit.createInventory(new ActiveReportsHolder(resolvedPage), 54,
                ChatColor.DARK_RED + "Active Rep Reports [" + (resolvedPage + 1) + "/" + (maxPage + 1) + "]");
        fillBackground(inventory, admin);

        if (cases.isEmpty()) {
            inventory.setItem(22, simpleButton(Material.PAPER, ChatColor.GRAY + "No active reports", List.of()));
            admin.openInventory(inventory);
            return;
        }

        int start = resolvedPage * REVIEW_SLOTS.size();
        for (int i = 0; i < REVIEW_SLOTS.size(); i++) {
            int index = start + i;
            if (index >= cases.size()) {
                break;
            }
            inventory.setItem(REVIEW_SLOTS.get(i), activeReportItem(cases.get(index)));
        }

        if (resolvedPage > 0) inventory.setItem(45, simpleButton(Material.ARROW, ChatColor.YELLOW + "Prev", List.of()));
        if (resolvedPage < maxPage) inventory.setItem(53, simpleButton(Material.ARROW, ChatColor.YELLOW + "Next", List.of()));
        admin.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory topInventory = event.getView().getTopInventory();
        if (topInventory == null || topInventory.getHolder() == null) {
            if (isActiveAnvilSession(player, event.getView())) {
                event.setCancelled(true);
                handleAnvilResultClick(player, event);
            }
            return;
        }
        InventoryHolder holder = topInventory.getHolder();
        if (!(holder instanceof HolderMarker)) {
            if (isActiveAnvilSession(player, event.getView())) {
                event.setCancelled(true);
                handleAnvilResultClick(player, event);
            }
            return;
        }

        event.setCancelled(true);

        if (holder instanceof ProfileHolder profile) {
            handleProfileClick(player, profile, event);
        } else if (holder instanceof ReasonHolder reason) {
            handleReasonClick(player, reason, event.getRawSlot());
        } else if (holder instanceof InputChoiceHolder inputChoice) {
            handleInputChoiceClick(player, inputChoice, event.getRawSlot());
        } else if (holder instanceof ConfirmReasonHolder confirmReason) {
            handleConfirmReasonClick(player, confirmReason, event.getRawSlot());
        } else if (holder instanceof ConfirmRemovalHolder removal) {
            handleRemovalClick(player, removal, event.getRawSlot());
        } else if (holder instanceof RemovedLogHolder removed) {
            handleRemovedLogClick(player, removed, event.getRawSlot(), event.getCurrentItem());
        } else if (holder instanceof ActiveReportsHolder reports) {
            handleReportsClick(player, reports, event.getRawSlot());
        } else if (holder instanceof ConfirmRestoreHolder restore) {
            handleRestoreClick(player, restore, event.getRawSlot());
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof HolderMarker
                || (event.getWhoClicked() instanceof Player player && isActiveAnvilSession(player, event.getView()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        AnvilSession session = pendingAnvils.get(player.getUniqueId());
        if (session == null || event.getInventory().getType() != org.bukkit.event.inventory.InventoryType.ANVIL) {
            return;
        }
        String text = event.getView() instanceof AnvilView anvilView ? anvilView.getRenameText() : event.getInventory().getRenameText();
        liveAnvilText.put(player.getUniqueId(), text == null ? "" : text);
        resetAnvilCosts(event.getInventory());
        String normalized = normalizeReason(text);
        if (normalized.isEmpty()) {
            event.setResult(null);
            return;
        }
        event.setResult(simpleButton(materialFor(session.category().isPositive()), ChatColor.YELLOW + normalized, List.of(ChatColor.GRAY + "Click to continue")));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (pendingAnvils.containsKey(player.getUniqueId()) && event.getInventory().getType() == org.bukkit.event.inventory.InventoryType.ANVIL) {
            UUID playerId = player.getUniqueId();
            if (transitioningAnvil.remove(playerId)) {
                return;
            }
            pendingAnvils.remove(playerId);
            liveAnvilText.remove(playerId);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PendingTextInput pending = pendingChatInputs.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }

        event.setCancelled(true);
        cancelChatTimeout(player.getUniqueId());

        String message = event.getMessage().trim();
        if (message.equalsIgnoreCase("cancel") || message.equalsIgnoreCase("stop")) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage(ChatColor.YELLOW + "Rep message entry cancelled.");
                openProfile(player, Bukkit.getOfflinePlayer(pending.targetId()), pending.returnPage());
            });
            return;
        }

        String normalized = normalizeReason(message);
        if (normalized.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage(ChatColor.RED + "Your message was empty. Type it again or type cancel.");
                beginChatInput(player, pending.targetId(), pending.category(), pending.returnPage());
            });
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> openConfirmReason(player, pending.targetId(), pending.category(), pending.returnPage(), normalized));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        pendingChatInputs.remove(playerId);
        cancelChatTimeout(playerId);
        pendingAnvils.remove(playerId);
        pendingDrafts.remove(playerId);
        returnFromBook.remove(playerId);
        liveAnvilText.remove(playerId);
        transitioningAnvil.remove(playerId);
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (pendingChatInputs.containsKey(event.getPlayer().getUniqueId())) {
            event.getPlayer().sendMessage(ChatColor.RED + "Finish your rep message first, or type cancel.");
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (pendingAnvils.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (pendingAnvils.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onHeldSlotChange(PlayerItemHeldEvent event) {
        if (pendingAnvils.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBookEdit(PlayerEditBookEvent event) {
        if (pendingAnvils.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            ProfileContext context = returnFromBook.remove(player.getUniqueId());
            if (context != null) {
                Bukkit.getScheduler().runTask(plugin, () -> openProfile(player, Bukkit.getOfflinePlayer(context.targetId()), context.page()));
            }
        }
    }

    private void handleProfileClick(Player player, ProfileHolder profile, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == 45) {
            openProfile(player, Bukkit.getOfflinePlayer(profile.targetId()), profile.page() - 1);
            return;
        }
        if (slot == 53) {
            openProfile(player, Bukkit.getOfflinePlayer(profile.targetId()), profile.page() + 1);
            return;
        }
        if ((slot == 48 || slot == 50) && !player.getUniqueId().equals(profile.targetId())) {
            if (!canStartRep(player, profile.targetId())) {
                return;
            }
            openReasonMenu(player, profile.targetId(), slot == 48, profile.page());
            return;
        }
        if (slot == 49 && !player.getUniqueId().equals(profile.targetId())) {
            Commendation existing = repService.getCommendation(player.getUniqueId(), profile.targetId());
            if (existing != null) {
                openRemovalConfirm(player, existing, profile.page(), true, false);
            }
            return;
        }

        int reviewIndex = REVIEW_SLOTS.indexOf(slot);
        if (reviewIndex == -1) {
            return;
        }
        List<Commendation> reviews = repService.getCommendationsAbout(profile.targetId()).stream()
                .sorted(Comparator.comparingLong(Commendation::getCreatedAt).reversed())
                .toList();
        int absoluteIndex = profile.page() * REVIEW_SLOTS.size() + reviewIndex;
        if (absoluteIndex < 0 || absoluteIndex >= reviews.size()) {
            return;
        }

        Commendation selected = reviews.get(absoluteIndex);
        if (player.hasPermission("enthusiacommend.rep.admin") && event.isRightClick()) {
            openRemovalConfirm(player, selected, profile.page(), false, true);
            return;
        }
        returnFromBook.put(player.getUniqueId(), new ProfileContext(profile.targetId(), profile.page()));
        openReviewBook(player, selected);
    }

    private void handleReasonClick(Player player, ReasonHolder reason, int slot) {
        List<RepCategory> categories = reason.positive() ? positiveCategories() : negativeCategories();
        int[] slots = {10, 11, 12, 14, 15, 16};
        for (int i = 0; i < categories.size() && i < slots.length; i++) {
            if (slot == slots[i]) {
                openInputChoice(player, reason.targetId(), categories.get(i), reason.returnPage());
                return;
            }
        }
    }

    private void handleInputChoiceClick(Player player, InputChoiceHolder inputChoice, int slot) {
        if (slot == 11) {
            player.closeInventory();
            beginChatInput(player, inputChoice.targetId(), inputChoice.category(), inputChoice.returnPage());
        } else if (slot == 15) {
            openAnvilInput(player, inputChoice.targetId(), inputChoice.category(), inputChoice.returnPage());
        } else if (slot == 22) {
            openReasonMenu(player, inputChoice.targetId(), inputChoice.category().isPositive(), inputChoice.returnPage());
        }
    }

    private void handleConfirmReasonClick(Player player, ConfirmReasonHolder confirmReason, int slot) {
        if (slot == 11) {
            submitReason(player, confirmReason.targetId(), confirmReason.category(), confirmReason.reason(), confirmReason.returnPage());
        } else if (slot == 13) {
            openInputChoice(player, confirmReason.targetId(), confirmReason.category(), confirmReason.returnPage());
        } else if (slot == 15) {
            pendingDrafts.remove(player.getUniqueId());
            openProfile(player, Bukkit.getOfflinePlayer(confirmReason.targetId()), confirmReason.returnPage());
        }
    }

    private void handleRemovalClick(Player player, ConfirmRemovalHolder removal, int slot) {
        if (slot == 11) {
            if (removal.logRemoval()) {
                repService.removeCommendationLogged(player.getUniqueId(), removal.giverId(), removal.targetId(), removal.applyCooldown());
            } else if (removal.applyCooldown()) {
                repService.removeCommendationWithCooldown(removal.giverId(), removal.targetId());
            } else {
                repService.removeCommendation(removal.giverId(), removal.targetId());
            }
            openProfile(player, Bukkit.getOfflinePlayer(removal.targetId()), removal.returnPage());
        } else if (slot == 15) {
            openProfile(player, Bukkit.getOfflinePlayer(removal.targetId()), removal.returnPage());
        }
    }

    private void handleRemovedLogClick(Player player, RemovedLogHolder holder, int slot, ItemStack clicked) {
        if (slot == 45) {
            openRemovedLog(player, holder.page() - 1);
            return;
        }
        if (slot == 53) {
            openRemovedLog(player, holder.page() + 1);
            return;
        }
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        String removalId = clicked.getItemMeta().getPersistentDataContainer()
                .get(new org.bukkit.NamespacedKey(plugin, "rep-removed-id"), PersistentDataType.STRING);
        if (removalId != null) {
            openRestoreConfirm(player, removalId, holder.page());
        }
    }

    private void handleReportsClick(Player player, ActiveReportsHolder holder, int slot) {
        if (slot == 45) {
            openActiveReports(player, holder.page() - 1);
            return;
        }
        if (slot == 53) {
            openActiveReports(player, holder.page() + 1);
            return;
        }
        int reviewIndex = REVIEW_SLOTS.indexOf(slot);
        if (reviewIndex == -1) {
            return;
        }
        List<RepService.SuspiciousRepCase> activeCases = repService.getSuspiciousCases().stream()
                .filter(caseData -> !caseData.isResolved())
                .sorted(Comparator.comparingLong(RepService.SuspiciousRepCase::getCreatedAt).reversed())
                .toList();
        int absoluteIndex = holder.page() * REVIEW_SLOTS.size() + reviewIndex;
        if (absoluteIndex >= 0 && absoluteIndex < activeCases.size()) {
            sendReportDetails(player, activeCases.get(absoluteIndex));
        }
    }

    private void handleRestoreClick(Player player, ConfirmRestoreHolder restore, int slot) {
        if (slot == 11) {
            if (repService.restoreRemoved(restore.removalId(), player)) {
                player.sendMessage(ChatColor.GREEN + "Restored rep entry " + restore.removalId() + ".");
            } else {
                player.sendMessage(ChatColor.RED + "Could not restore that rep entry.");
            }
            openRemovedLog(player, restore.returnPage());
        } else if (slot == 15) {
            openRemovedLog(player, restore.returnPage());
        }
    }

    private void handleAnvilResultClick(Player player, InventoryClickEvent event) {
        if (event.getRawSlot() != 2) {
            return;
        }
        AnvilSession anvil = pendingAnvils.get(player.getUniqueId());
        if (anvil == null) {
            return;
        }
        String text = resolveAnvilReasonText(player, event);
        if (text.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> retryAnvilResultClick(player));
            return;
        }
        completeAnvilReasonEntry(player, anvil, text);
    }

    private void retryAnvilResultClick(Player player) {
        AnvilSession anvil = pendingAnvils.get(player.getUniqueId());
        if (anvil == null || !isActiveAnvilSession(player, player.getOpenInventory())) {
            return;
        }
        String text = resolveAnvilReasonText(player, player.getOpenInventory());
        if (text.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Type a message in the anvil first.");
            return;
        }
        completeAnvilReasonEntry(player, anvil, text);
    }

    private void completeAnvilReasonEntry(Player player, AnvilSession anvil, String text) {
        transitioningAnvil.add(player.getUniqueId());
        pendingAnvils.remove(player.getUniqueId());
        liveAnvilText.remove(player.getUniqueId());
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin,
                () -> openConfirmReason(player, anvil.targetId(), anvil.category(), anvil.returnPage(), text));
    }

    private void openReasonMenu(Player viewer, UUID targetId, boolean positive, int returnPage) {
        Inventory inventory = Bukkit.createInventory(new ReasonHolder(targetId, positive, returnPage), 27,
                positive ? ChatColor.GREEN + "Choose Positive Reason" : ChatColor.RED + "Choose Negative Reason");
        fillBackground(inventory, viewer);
        List<RepCategory> categories = positive ? positiveCategories() : negativeCategories();
        int[] slots = {10, 11, 12, 14, 15, 16};
        for (int i = 0; i < categories.size() && i < slots.length; i++) {
            inventory.setItem(slots[i], simpleButton(materialFor(positive), (positive ? ChatColor.GREEN : ChatColor.RED) + displayName(categories.get(i)),
                    List.of(ChatColor.GRAY + "Click to continue")));
        }
        viewer.openInventory(inventory);
    }

    private void openInputChoice(Player viewer, UUID targetId, RepCategory category, int returnPage) {
        Inventory inventory = Bukkit.createInventory(new InputChoiceHolder(targetId, category, returnPage), 27,
                ChatColor.GOLD + "How do you want to type it?");
        fillBackground(inventory, viewer);
        inventory.setItem(11, simpleButton(Material.PAPER, ChatColor.YELLOW + "Type In Chat",
                List.of(ChatColor.GRAY + "Type your reason in chat", ChatColor.GRAY + "Then confirm or retry")));
        inventory.setItem(15, simpleButton(Material.ANVIL, ChatColor.YELLOW + "Type In Anvil",
                List.of(ChatColor.GRAY + "Rename the item in an anvil", ChatColor.GRAY + "Then confirm or retry")));
        inventory.setItem(22, simpleButton(Material.ARROW, ChatColor.RED + "Back", List.of()));
        viewer.openInventory(inventory);
    }

    private void beginChatInput(Player player, UUID targetId, RepCategory category, int returnPage) {
        UUID playerId = player.getUniqueId();
        cancelChatTimeout(playerId);
        pendingChatInputs.put(playerId, new PendingTextInput(targetId, category, returnPage));
        player.sendMessage(ChatColor.GOLD + "Type your rep reason in chat.");
        player.sendMessage(ChatColor.GRAY + "Type " + ChatColor.YELLOW + "cancel" + ChatColor.GRAY + " or " + ChatColor.YELLOW + "stop" + ChatColor.GRAY + " to cancel.");
        int taskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pendingChatInputs.remove(playerId) != null) {
                pendingChatTimeoutTasks.remove(playerId);
                Player online = Bukkit.getPlayer(playerId);
                if (online != null) {
                    online.sendMessage(ChatColor.RED + "Rep message entry timed out.");
                    openProfile(online, Bukkit.getOfflinePlayer(targetId), returnPage);
                }
            }
        }, Math.max(20L, plugin.getRepConfig().getInputTimeoutMillis() / 50L)).getTaskId();
        pendingChatTimeoutTasks.put(playerId, taskId);
    }

    private void cancelChatTimeout(UUID playerId) {
        Integer taskId = pendingChatTimeoutTasks.remove(playerId);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    private void openAnvilInput(Player player, UUID targetId, RepCategory category, int returnPage) {
        pendingAnvils.put(player.getUniqueId(), new AnvilSession(targetId, category, returnPage));
        liveAnvilText.put(player.getUniqueId(), "");
        org.bukkit.inventory.InventoryView view = player.openAnvil(null, true);
        if (view instanceof AnvilView anvilView) {
            resetAnvilView(anvilView);
        }
        Inventory inventory = view.getTopInventory();
        inventory.setItem(0, simpleButton(materialFor(category.isPositive()), ChatColor.WHITE + "Type here", List.of()));
        if (inventory instanceof AnvilInventory anvilInventory) {
            resetAnvilCosts(anvilInventory);
        }
    }

    private String resolveAnvilReasonText(Player player, InventoryClickEvent event) {
        String text = resolveAnvilReasonText(player, event.getView());
        if (!text.isEmpty()) {
            return text;
        }
        return resolveAnvilTextFromItem(player, event.getCurrentItem());
    }

    private String resolveAnvilReasonText(Player player, org.bukkit.inventory.InventoryView view) {
        String text = normalizeReason(liveAnvilText.get(player.getUniqueId()));
        if (!text.isEmpty()) {
            return text;
        }
        if (view instanceof AnvilView anvilView) {
            text = normalizeReason(anvilView.getRenameText());
            if (!text.isEmpty()) {
                liveAnvilText.put(player.getUniqueId(), text);
                return text;
            }
        }
        Inventory top = view.getTopInventory();
        if (top != null) {
            text = resolveAnvilTextFromItem(player, top.getItem(2));
            if (!text.isEmpty()) {
                return text;
            }
            return resolveAnvilTextFromItem(player, top.getItem(0));
        }
        return "";
    }

    private String resolveAnvilTextFromItem(Player player, ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return "";
        }
        String text = normalizeReason(ChatColor.stripColor(meta.getDisplayName()));
        if (text.isEmpty() || "Type here".equalsIgnoreCase(text)) {
            return "";
        }
        liveAnvilText.put(player.getUniqueId(), text);
        return text;
    }

    private void openConfirmReason(Player player, UUID targetId, RepCategory category, int returnPage, String reason) {
        DraftReason draft = new DraftReason(targetId, category, returnPage, reason);
        pendingDrafts.put(player.getUniqueId(), draft);
        Inventory inventory = Bukkit.createInventory(new ConfirmReasonHolder(targetId, category, returnPage, reason), 27,
                ChatColor.GOLD + "Confirm Rep Message");
        fillBackground(inventory, player);
        inventory.setItem(11, simpleButton(Material.LIME_CONCRETE, ChatColor.GREEN + "Confirm", List.of(ChatColor.GRAY + "Apply this rep entry")));
        inventory.setItem(13, simpleButton(Material.PAPER, ChatColor.YELLOW + "Retry", wrapLore(reason, 36, ChatColor.WHITE)));
        inventory.setItem(15, simpleButton(Material.RED_CONCRETE, ChatColor.RED + "Cancel", List.of(ChatColor.GRAY + "Do not apply this rep entry")));
        player.openInventory(inventory);
    }

    private void openRemovalConfirm(Player admin, Commendation commendation, int returnPage, boolean applyCooldown, boolean logRemoval) {
        Inventory inventory = Bukkit.createInventory(new ConfirmRemovalHolder(commendation.getTarget(), commendation.getGiver(), returnPage, applyCooldown, logRemoval),
                27, ChatColor.RED + "Confirm removal");
        fillBackground(inventory, admin);
        inventory.setItem(11, simpleButton(Material.LIME_CONCRETE, ChatColor.GREEN + "Confirm removal", List.of(ChatColor.GRAY + "Delete this rep entry")));
        inventory.setItem(13, simpleButton(Material.PAPER, ChatColor.YELLOW + "Rep from " + repService.nameOf(commendation.getGiver()),
                List.of(
                        ChatColor.GRAY + "Target: " + ChatColor.WHITE + repService.nameOf(commendation.getTarget()),
                        ChatColor.GRAY + "Category: " + ChatColor.WHITE + displayName(commendation.getCategory()),
                        ChatColor.GRAY + "Value: " + (commendation.isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1")
                )));
        inventory.setItem(15, simpleButton(Material.RED_CONCRETE, ChatColor.RED + "Cancel", List.of()));
        admin.openInventory(inventory);
    }

    private void openRestoreConfirm(Player admin, String removalId, int returnPage) {
        Inventory inventory = Bukkit.createInventory(new ConfirmRestoreHolder(removalId, returnPage), 27,
                ChatColor.GREEN + "Restore rep?");
        fillBackground(inventory, admin);
        inventory.setItem(11, simpleButton(Material.LIME_CONCRETE, ChatColor.GREEN + "Restore", List.of(ChatColor.GRAY + "Re-add this rep entry")));
        inventory.setItem(15, simpleButton(Material.RED_CONCRETE, ChatColor.RED + "Cancel", List.of(ChatColor.GRAY + "Back to log")));
        admin.openInventory(inventory);
    }

    private void submitReason(Player player, UUID targetId, RepCategory category, String reason, int returnPage) {
        if (!canStartRep(player, targetId)) {
            return;
        }
        String ipHash = repService.hashIp(player.getAddress() != null && player.getAddress().getAddress() != null
                ? player.getAddress().getAddress().getHostAddress()
                : null);
        RepService.CommendationResult result = repService.addOrUpdateCommendation(
                player.getUniqueId(),
                targetId,
                category.isPositive(),
                category,
                reason,
                ipHash
        );

        if (!result.success()) {
            long hoursLeft = (long) Math.ceil(result.cooldownRemainingMillis() / 1000.0D / 3600.0D);
            player.sendMessage(plugin.getMessages().get("rep.cooldown", Map.of("hours", String.valueOf(hoursLeft))));
            openProfile(player, Bukkit.getOfflinePlayer(targetId), returnPage);
            return;
        }

        pendingDrafts.remove(player.getUniqueId());
        Commendation commendation = result.commendation();
        String formattedScore = plugin.getRepConfig().formatColoredScore(repService.getScore(targetId));
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
        player.sendMessage(plugin.getMessages().get("rep.give-success", Map.of(
                "amount", commendation.isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1",
                "target", safeName(target),
                "category", displayName(commendation.getCategory()),
                "rep", formattedScore
        )));

        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null) {
            onlineTarget.sendMessage(plugin.getMessages().get("rep.receive", Map.of(
                    "giver", player.getName(),
                    "amount", commendation.isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1",
                    "category", displayName(commendation.getCategory()),
                    "rep", formattedScore
            )));
        }

        openProfile(player, target, returnPage);
    }

    private boolean canStartRep(Player giver, UUID targetId) {
        if (giver.getUniqueId().equals(targetId)) {
            giver.sendMessage(plugin.getMessages().get("rep.self"));
            return false;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            giver.sendMessage(plugin.getMessages().get("rep.not-found", Map.of("name", targetId.toString())));
            return false;
        }
        if (!plugin.getPlaytimeService().isAvailable()) {
            giver.sendMessage(ChatColor.RED + "Active playtime tracking is unavailable. Rep is temporarily disabled.");
            return false;
        }
        double hours = plugin.getPlaytimeService().getActiveHours(giver);
        if (hours < plugin.getRepConfig().getMinActivePlaytimeHours()) {
            giver.sendMessage(plugin.getMessages().get("rep.playtime-short", Map.of(
                    "hours_required", String.valueOf(plugin.getRepConfig().getMinActivePlaytimeHours()),
                    "hours_have", String.format(Locale.US, "%.1f", hours)
            )));
            return false;
        }
        Commendation existing = repService.getCommendation(giver.getUniqueId(), targetId);
        long remaining = cooldownRemaining(giver.getUniqueId(), targetId, existing);
        if (remaining > 0L || !repService.canEdit(giver.getUniqueId(), targetId)) {
            long hoursLeft = (long) Math.ceil(remaining / 1000.0D / 3600.0D);
            giver.sendMessage(plugin.getMessages().get("rep.cooldown", Map.of("hours", String.valueOf(Math.max(1L, hoursLeft)))));
            return false;
        }
        return true;
    }

    private long cooldownRemaining(UUID giverId, UUID targetId, Commendation existing) {
        long remaining = repService.getRemovalCooldownMillis(giverId, targetId);
        if (existing != null) {
            long editRemaining = Math.max(0L, plugin.getRepConfig().getEditCooldownMillis() - (System.currentTimeMillis() - existing.getLastEditedAt()));
            remaining = Math.max(remaining, editRemaining);
        }
        return remaining;
    }

    private ItemStack reviewItem(Commendation commendation, boolean adminView) {
        ItemStack head = HeadUtil.createPlayerHead(plugin, commendation.getGiver(),
                (commendation.isPositive() ? ChatColor.GREEN : ChatColor.RED) + repService.nameOf(commendation.getGiver()));
        ItemMeta meta = head.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Category: " + ChatColor.YELLOW + displayName(commendation.getCategory()));
            lore.add(ChatColor.GRAY + "Date: " + ChatColor.WHITE + dateFormatter.format(Instant.ofEpochMilli(commendation.getCreatedAt())));
            lore.add(ChatColor.DARK_GRAY + "----------------");
            lore.addAll(wrapLore(commendation.getReasonText(), 34, ChatColor.WHITE));
            lore.add(ChatColor.YELLOW + "Click: view full text");
            if (adminView) {
                lore.add(ChatColor.RED + "Right-click: delete rep (admin)");
            }
            meta.setLore(lore);
            head.setItemMeta(meta);
        }
        return head;
    }

    private ItemStack removedLogItem(RepService.RemovedRep removed) {
        Commendation commendation = removed.commendation();
        ItemStack item = removed.removedBy() != null
                ? HeadUtil.createPlayerHead(plugin, removed.removedBy(), ChatColor.YELLOW + removed.id())
                : new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + removed.id() + ChatColor.GRAY + " - " + repService.nameOf(commendation.getGiver()));
            meta.setLore(List.of(
                    ChatColor.GRAY + "Target: " + ChatColor.WHITE + repService.nameOf(commendation.getTarget()),
                    ChatColor.GRAY + "Value: " + (commendation.isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1"),
                    ChatColor.GRAY + "Category: " + ChatColor.WHITE + displayName(commendation.getCategory()),
                    ChatColor.GRAY + "Removed: " + ChatColor.WHITE + dateFormatter.format(Instant.ofEpochMilli(removed.removedAt())),
                    ChatColor.GRAY + "By: " + ChatColor.WHITE + (removed.removedBy() != null ? repService.nameOf(removed.removedBy()) : "unknown"),
                    ChatColor.YELLOW + "Click to restore this rep."
            ));
            meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "rep-removed-id"), PersistentDataType.STRING, removed.id());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack activeReportItem(RepService.SuspiciousRepCase caseData) {
        ItemStack item = HeadUtil.createPlayerHead(plugin, caseData.getTarget(), ChatColor.YELLOW + repService.nameOf(caseData.getTarget()));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + repService.nameOf(caseData.getTarget()));
            meta.setLore(List.of(
                    ChatColor.GRAY + "IP: " + ChatColor.WHITE + caseData.ipHash(),
                    ChatColor.GRAY + "Accounts: " + ChatColor.WHITE + formatNames(caseData.givers()),
                    ChatColor.GRAY + "Created: " + ChatColor.WHITE + dateFormatter.format(Instant.ofEpochMilli(caseData.getCreatedAt())),
                    ChatColor.YELLOW + "Click to post details in chat."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void sendReportDetails(Player admin, RepService.SuspiciousRepCase caseData) {
        String targetArg = resolveTargetArgument(caseData.getTarget());
        admin.sendMessage(ChatColor.GOLD + "ALT REP REPORT: " + ChatColor.YELLOW + repService.nameOf(caseData.getTarget())
                + ChatColor.GRAY + " (IP " + ChatColor.YELLOW + caseData.ipHash() + ChatColor.GRAY + ")");
        admin.sendMessage(ChatColor.GRAY + "Accounts: " + ChatColor.WHITE + formatNames(caseData.givers()));
        admin.sendMessage(ChatColor.GRAY + "Created: " + ChatColor.WHITE + dateFormatter.format(Instant.ofEpochMilli(caseData.getCreatedAt())));
        admin.spigot().sendMessage(new ComponentBuilder(ChatColor.YELLOW + "Inspect report")
                .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rep admin inspect " + targetArg + " " + caseData.ipHash()))
                .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(ChatColor.GRAY + "Click to inspect this report").create()))
                .append(ChatColor.GRAY + " | ")
                .event((ClickEvent) null)
                .event((HoverEvent) null)
                .append(ChatColor.RED + "Resolve report")
                .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rep admin resolve " + targetArg + " " + caseData.ipHash()))
                .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(ChatColor.GRAY + "Mark this report as resolved").create()))
                .create());
    }

    private void openReviewBook(Player viewer, Commendation commendation) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta != null) {
            meta.setTitle("Rep from " + repService.nameOf(commendation.getGiver()));
            meta.setAuthor(repService.nameOf(commendation.getGiver()));
            meta.setPages(wrapLore(commendation.getReasonText(), 220, ChatColor.BLACK));
            book.setItemMeta(meta);
        }
        viewer.openBook(book);
    }

    private void fillBackground(Inventory inventory, Player viewer) {
        if (viewer.getName().startsWith("*")) {
            return;
        }
        ItemStack filler = simpleButton(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
    }

    private ItemStack simpleButton(Material material, String displayName, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private List<String> buildGiveLore(Commendation existing, boolean positiveButton, long cooldownMillis) {
        List<String> lore = new ArrayList<>();
        if (existing == null) {
            lore.add(ChatColor.GRAY + "Leave a " + (positiveButton ? "positive" : "negative") + " commendation.");
        } else {
            lore.add(ChatColor.GRAY + "You already left " + (existing.isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1") + ChatColor.GRAY + ".");
            lore.add(ChatColor.GRAY + "Category: " + ChatColor.YELLOW + displayName(existing.getCategory()));
        }
        if (cooldownMillis > 0L) {
            long hours = (long) Math.ceil(cooldownMillis / 1000.0D / 3600.0D);
            lore.add(ChatColor.RED + "Edit available in " + hours + "h.");
        } else {
            lore.add(ChatColor.YELLOW + "Click to continue.");
        }
        return lore;
    }

    private List<String> buildCurrentEffectsLore(RepAppliedEffects effects) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Active effects:");
        if (effects.movementSpeedPercent() != 0) lore.add(ChatColor.WHITE + "Movement speed: " + ChatColor.YELLOW + percent(effects.movementSpeedPercent()));
        if (effects.potionDurationPercent() != 0) lore.add(ChatColor.WHITE + "Potion duration: " + ChatColor.YELLOW + percent(effects.potionDurationPercent()));
        if (effects.fireworkDurationPercent() != 0) lore.add(ChatColor.WHITE + "Rocket flight duration: " + ChatColor.YELLOW + percent(effects.fireworkDurationPercent()));
        if (effects.pearlCooldownSeconds() > 0) lore.add(ChatColor.WHITE + "Ender pearl cooldown: " + ChatColor.YELLOW + effects.pearlCooldownSeconds() + "s");
        if (effects.windCooldownSeconds() > 0) lore.add(ChatColor.WHITE + "Wind charge cooldown: " + ChatColor.YELLOW + effects.windCooldownSeconds() + "s");
        if (effects.glow()) lore.add(ChatColor.WHITE + "Glow: " + ChatColor.YELLOW + (effects.glowColor() != null ? effects.glowColor().name() : "WHITE"));
        if (effects.stalkable()) lore.add(ChatColor.WHITE + "Stalkable in warzone");
        if (effects.cashbackPercent() > 0) lore.add(ChatColor.WHITE + "Cashback: " + ChatColor.YELLOW + effects.cashbackPercent() + "%");
        if (lore.size() == 1) lore.add(ChatColor.GRAY + "You currently have no rep-based buffs or penalties.");
        return lore;
    }

    private List<RepCategory> positiveCategories() {
        return List.of(RepCategory.WAS_KIND, RepCategory.HELPED_ME, RepCategory.GAVE_ITEMS, RepCategory.TRUSTWORTHY, RepCategory.GOOD_STALL, RepCategory.OTHER_POSITIVE);
    }

    private List<RepCategory> negativeCategories() {
        return List.of(RepCategory.SCAMMED, RepCategory.SPAWN_KILLED, RepCategory.GRIEFED, RepCategory.TRAPPED, RepCategory.SCAM_STALL, RepCategory.OTHER_NEGATIVE);
    }

    private String displayName(RepCategory category) {
        return switch (category) {
            case WAS_KIND -> "Was Kind";
            case HELPED_ME -> "Helped Me";
            case GAVE_ITEMS -> "Gave Items/Money";
            case TRUSTWORTHY -> "Trustworthy";
            case GOOD_STALL -> "Good Stall";
            case OTHER_POSITIVE -> "Other";
            case SCAMMED -> "Scammed";
            case SPAWN_KILLED -> "Spawn Killed";
            case GRIEFED -> "Griefed";
            case TRAPPED -> "Trapped";
            case SCAM_STALL -> "Scam Stall";
            case OTHER_NEGATIVE -> "Other";
        };
    }

    private List<String> wrapLore(String text, int width, ChatColor color) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return List.of(color + "(no message)");
        }
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (current.length() + word.length() + 1 > width && current.length() > 0) {
                lines.add(color + current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            lines.add(color + current.toString());
        }
        return lines;
    }

    private String percent(int value) {
        return value > 0 ? "+" + value + "%" : value + "%";
    }

    private String normalizeReason(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.length() > plugin.getRepConfig().getMaxReasonLength()) {
            trimmed = trimmed.substring(0, plugin.getRepConfig().getMaxReasonLength());
        }
        return trimmed;
    }

    private void resetAnvilCosts(AnvilInventory inventory) {
        try {
            inventory.setRepairCost(0);
        } catch (Throwable ignored) {
        }
        try {
            inventory.setRepairCostAmount(0);
        } catch (Throwable ignored) {
        }
        try {
            inventory.setMaximumRepairCost(0);
        } catch (Throwable ignored) {
        }
    }

    private void resetAnvilView(AnvilView anvilView) {
        try {
            anvilView.setRepairCost(0);
        } catch (Throwable ignored) {
        }
        try {
            anvilView.setRepairItemCountCost(0);
        } catch (Throwable ignored) {
        }
        try {
            anvilView.setMaximumRepairCost(0);
        } catch (Throwable ignored) {
        }
        try {
            anvilView.bypassEnchantmentLevelRestriction(true);
        } catch (Throwable ignored) {
        }
    }

    private boolean isActiveAnvilSession(Player player, org.bukkit.inventory.InventoryView view) {
        return pendingAnvils.containsKey(player.getUniqueId())
                && view != null
                && view.getTopInventory() != null
                && view.getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.ANVIL;
    }

    private Material materialFor(boolean positive) {
        return positive ? Material.LIME_DYE : Material.RED_DYE;
    }

    private String safeName(OfflinePlayer player) {
        return player.getName() != null ? player.getName() : player.getUniqueId().toString().substring(0, 8);
    }

    private String resolveTargetArgument(UUID targetId) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(targetId);
        return player.getName() != null ? player.getName() : targetId.toString();
    }

    private String formatNames(Collection<UUID> ids) {
        return ids.stream().map(repService::nameOf).collect(Collectors.joining(", "));
    }

    private sealed interface HolderMarker permits ProfileHolder, ReasonHolder, InputChoiceHolder, ConfirmReasonHolder, ConfirmRemovalHolder, RemovedLogHolder, ActiveReportsHolder, ConfirmRestoreHolder {
    }

    private record ProfileHolder(UUID targetId, int page) implements InventoryHolder, HolderMarker {
        @Override public Inventory getInventory() { return null; }
    }

    private record ReasonHolder(UUID targetId, boolean positive, int returnPage) implements InventoryHolder, HolderMarker {
        @Override public Inventory getInventory() { return null; }
    }

    private record InputChoiceHolder(UUID targetId, RepCategory category, int returnPage) implements InventoryHolder, HolderMarker {
        @Override public Inventory getInventory() { return null; }
    }

    private record ConfirmReasonHolder(UUID targetId, RepCategory category, int returnPage, String reason) implements InventoryHolder, HolderMarker {
        @Override public Inventory getInventory() { return null; }
    }

    private record ConfirmRemovalHolder(UUID targetId, UUID giverId, int returnPage, boolean applyCooldown, boolean logRemoval) implements InventoryHolder, HolderMarker {
        @Override public Inventory getInventory() { return null; }
    }

    private record RemovedLogHolder(int page) implements InventoryHolder, HolderMarker {
        @Override public Inventory getInventory() { return null; }
    }

    private record ActiveReportsHolder(int page) implements InventoryHolder, HolderMarker {
        @Override public Inventory getInventory() { return null; }
    }

    private record ConfirmRestoreHolder(String removalId, int returnPage) implements InventoryHolder, HolderMarker {
        @Override public Inventory getInventory() { return null; }
    }

    private record PendingTextInput(UUID targetId, RepCategory category, int returnPage) {
    }

    private record DraftReason(UUID targetId, RepCategory category, int returnPage, String reason) {
    }

    private record ProfileContext(UUID targetId, int page) {
    }

    private record AnvilSession(UUID targetId, RepCategory category, int returnPage) {
    }
}
