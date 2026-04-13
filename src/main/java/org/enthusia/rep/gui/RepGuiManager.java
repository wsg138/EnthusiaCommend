package org.enthusia.rep.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.config.RepConfig;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;
import org.enthusia.rep.rep.RepService;
import org.enthusia.rep.effects.RepEffectManager;
import org.enthusia.rep.effects.RepAppliedEffects;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.ClickEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class RepGuiManager implements Listener {

    private final CommendPlugin plugin;
    private final RepService repService;
    private final RepEffectManager effects;
    private final NamespacedKey bookKey;
    private final NamespacedKey bookCategoryKey;
    private final NamespacedKey removedIdKey;
    private static final List<Integer> REVIEW_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    );
    private final Map<UUID, ProfileContext> returnToProfile = new HashMap<>();
    private final Map<UUID, PendingBook> pendingBooks = new HashMap<>();
    private final Map<UUID, PendingChat> pendingChat = new HashMap<>();
    private final Map<UUID, Integer> pendingTimeoutTasks = new HashMap<>();
    private final Map<UUID, ProfileContext> pendingReturnAfterBook = new HashMap<>();
    private final Set<UUID> suppressReopen = new HashSet<>();
    private final DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public RepGuiManager(CommendPlugin plugin, RepService repService, RepEffectManager effects) {
        this.plugin = plugin;
        this.repService = repService;
        this.effects = effects;
        this.bookKey = new NamespacedKey(plugin, "rep-book");
        this.bookCategoryKey = new NamespacedKey(plugin, "rep-book-category");
        this.removedIdKey = new NamespacedKey(plugin, "rep-removed-id");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /* ===================== Inventory holders ===================== */

    private static class ProfileHolder implements InventoryHolder {
        final UUID targetId;
        final int page;

        ProfileHolder(UUID targetId, int page) {
            this.targetId = targetId;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return null; // not used
        }
    }

    private static class ReasonHolder implements InventoryHolder {
        final UUID targetId;
        final boolean positive;

        ReasonHolder(UUID targetId, boolean positive) {
            this.targetId = targetId;
            this.positive = positive;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private record ProfileContext(UUID targetId, int page) {}

    private record PendingBook(UUID targetId, RepCategory category, int returnPage, ItemStack previousItem, int previousSlot) {}
    private record PendingChat(UUID targetId, RepCategory category, int returnPage) {}

    private static class AnvilHolder implements InventoryHolder {
        final UUID targetId;
        final RepCategory category;
        final int returnPage;

        AnvilHolder(UUID targetId, RepCategory category, int returnPage) {
            this.targetId = targetId;
            this.category = category;
            this.returnPage = returnPage;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static class InputChoiceHolder implements InventoryHolder {
        final UUID targetId;
        final RepCategory category;
        final int returnPage;

        InputChoiceHolder(UUID targetId, RepCategory category, int returnPage) {
            this.targetId = targetId;
            this.category = category;
            this.returnPage = returnPage;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static class ConfirmRemovalHolder implements InventoryHolder {
        final UUID targetId;
        final UUID giverId;
        final int returnPage;
        final boolean applyCooldown;
        final boolean logRemoval;

        ConfirmRemovalHolder(UUID targetId, UUID giverId, int returnPage, boolean applyCooldown, boolean logRemoval) {
            this.targetId = targetId;
            this.giverId = giverId;
            this.returnPage = returnPage;
            this.applyCooldown = applyCooldown;
            this.logRemoval = logRemoval;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static class RemovedLogHolder implements InventoryHolder {
        final int page;

        RemovedLogHolder(int page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static class ActiveReportsHolder implements InventoryHolder {
        final int page;

        ActiveReportsHolder(int page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static class ConfirmRestoreHolder implements InventoryHolder {
        final String removalId;
        final int returnPage;

        ConfirmRestoreHolder(String removalId, int returnPage) {
            this.removalId = removalId;
            this.returnPage = returnPage;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    /* ===================== Public open methods ===================== */

    public void openProfile(Player viewer, OfflinePlayer target) {
        openProfile(viewer, target, 0);
    }

    public void openProfile(Player viewer, OfflinePlayer target, int page) {
        UUID targetId = target.getUniqueId();
        int score = repService.getScore(targetId);
        ChatColor color = getRepColor(score);

        List<Commendation> reviews = repService.getCommendationsAbout(targetId)
                .stream()
                .sorted(Comparator.comparingLong(Commendation::getCreatedAt).reversed())
                .toList();
        long positiveCount = reviews.stream().filter(Commendation::isPositive).count();
        long negativeCount = reviews.size() - positiveCount;

        int pageSize = REVIEW_SLOTS.size();
        int maxPage = Math.max(0, (reviews.size() - 1) / pageSize);
        if (page < 0) page = 0;
        if (page > maxPage) page = maxPage;

        Inventory inv = Bukkit.createInventory(
                new ProfileHolder(targetId, page),
                54,
                ChatColor.DARK_GREEN + "Rep: " + ChatColor.RESET + target.getName() + ChatColor.GRAY + " [" + (page + 1) + "/" + (maxPage + 1) + "]"
        );

        ItemStack filler = backgroundFiller(viewer);
        if (filler != null) {
            for (int i = 0; i < inv.getSize(); i++) {
                inv.setItem(i, filler);
            }
        }

        ItemStack head = HeadUtil.createPlayerHead(plugin, targetId, color + target.getName());
        ItemMeta skullMeta = head.getItemMeta();
        if (skullMeta != null) {
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Total Rep: " + color + score);
            lore.add(ChatColor.GRAY + "Positives: " + ChatColor.GREEN + "+" + positiveCount);
            lore.add(ChatColor.GRAY + "Negatives: " + ChatColor.RED + "-" + negativeCount);
            skullMeta.setLore(lore);
            head.setItemMeta(skullMeta);
        }
        inv.setItem(4, head);

        int start = page * pageSize;
        for (int i = 0; i < pageSize; i++) {
            int idx = start + i;
            if (idx >= reviews.size()) break;
            Commendation c = reviews.get(idx);
            ItemStack item = reviewItem(c, viewer.hasPermission("enthusiacommend.admin"));
            inv.setItem(REVIEW_SLOTS.get(i), item);
        }

        if (page > 0) inv.setItem(45, simpleButton(Material.ARROW, ChatColor.YELLOW + "Prev", List.of()));
        if (page < maxPage) inv.setItem(53, simpleButton(Material.ARROW, ChatColor.YELLOW + "Next", List.of()));

        if (!viewer.getUniqueId().equals(targetId)) {
            Commendation existing = repService.getCommendation(viewer.getUniqueId(), targetId);
            long canEditIn = Math.max(0, repService.getRemovalCooldownMillis(viewer.getUniqueId(), targetId));
            if (existing != null) {
                long sinceEdit = System.currentTimeMillis() - existing.getLastEditedAt();
                long editRemaining = Math.max(0, plugin.getRepConfig().getEditCooldownMillis() - sinceEdit);
                canEditIn = Math.max(canEditIn, editRemaining);
            }
            boolean locked = canEditIn > 0;
            if (locked) {
                inv.setItem(48, simpleButton(Material.BARRIER, ChatColor.RED + "On cooldown", buildGiveLore(existing, true, canEditIn)));
                inv.setItem(50, simpleButton(Material.BARRIER, ChatColor.RED + "On cooldown", buildGiveLore(existing, false, canEditIn)));
            } else {
                inv.setItem(48, simpleButton(Material.LIME_WOOL, ChatColor.GREEN + "Leave Positive",
                        buildGiveLore(existing, true, 0)));
                inv.setItem(50, simpleButton(Material.RED_WOOL, ChatColor.RED + "Leave Negative",
                        buildGiveLore(existing, false, 0)));
            }
            if (existing != null) {
                inv.setItem(49, simpleButton(Material.PAPER, ChatColor.YELLOW + "Remove my rep",
                        List.of(ChatColor.GRAY + "Click to remove your commendation",
                                ChatColor.GRAY + "(applies cooldown)")));
            }
        } else {
            inv.setItem(48, simpleButton(Material.BARRIER, ChatColor.GRAY + "You cannot rep yourself", List.of()));
            RepAppliedEffects eff = effects.getCurrentEffects(targetId);
            List<String> lore = buildCurrentEffectsLore(eff);
            inv.setItem(49, simpleButton(Material.BOOK, ChatColor.AQUA + "Your Rep Effects", lore));
            inv.setItem(50, simpleButton(Material.BARRIER, ChatColor.GRAY + "You cannot rep yourself", List.of()));
        }

        viewer.openInventory(inv);
    }

    public void openReasonMenu(Player viewer, OfflinePlayer target, boolean positive) {
        Inventory inv = Bukkit.createInventory(
                new ReasonHolder(target.getUniqueId(), positive),
                27,
                (positive ? ChatColor.GREEN + "Choose Positive Reason" : ChatColor.RED + "Choose Negative Reason")
        );
        ItemStack filler = backgroundFiller(viewer);
        if (filler != null) {
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        }

        List<RepCategory> cats = positive ? positiveCategories() : negativeCategories();
        int[] slots = {10, 11, 12, 14, 15, 16};
        for (int i = 0; i < cats.size() && i < slots.length; i++) {
            RepCategory cat = cats.get(i);
            Material mat = positive ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
            inv.setItem(slots[i], simpleButton(mat,
                    (positive ? ChatColor.GREEN : ChatColor.RED) + displayName(cat),
                    List.of(ChatColor.GRAY + "Click to describe this reason")));
        }
        viewer.openInventory(inv);
    }

    /* ===================== Click handling ===================== */

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getInventory().getHolder() == null) return;

        InventoryHolder holder = event.getInventory().getHolder();

        if (holder instanceof ProfileHolder profileHolder) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;

            int slot = event.getRawSlot();
            if (slot == 45) {
                openProfile(player, Bukkit.getOfflinePlayer(profileHolder.targetId), Math.max(0, profileHolder.page - 1));
                return;
            }
            if (slot == 53) {
                openProfile(player, Bukkit.getOfflinePlayer(profileHolder.targetId), profileHolder.page + 1);
                return;
            }
            if ((slot == 48 || slot == 50) && !player.getUniqueId().equals(profileHolder.targetId)) {
                if (!repService.canEdit(player.getUniqueId(), profileHolder.targetId)) {
                    player.sendMessage(ChatColor.RED + "You must wait before editing your commendation.");
                    return;
                }
                boolean positive = slot == 48;
                returnToProfile.put(player.getUniqueId(), new ProfileContext(profileHolder.targetId, profileHolder.page));
                openReasonMenu(player, Bukkit.getOfflinePlayer(profileHolder.targetId), positive);
                return;
            }
            if (slot == 49 && !player.getUniqueId().equals(profileHolder.targetId)) {
                Commendation existing = repService.getCommendation(player.getUniqueId(), profileHolder.targetId);
                if (existing != null) {
                    openRemovalConfirm(player, existing, profileHolder.page, true, false);
                }
                return;
            }

            int reviewSlotIndex = REVIEW_SLOTS.indexOf(slot);
            if (reviewSlotIndex != -1) {
                List<Commendation> reviews = repService.getCommendationsAbout(profileHolder.targetId).stream()
                        .sorted(Comparator.comparingLong(Commendation::getCreatedAt).reversed())
                        .toList();
                int idx = profileHolder.page * REVIEW_SLOTS.size() + reviewSlotIndex;
                if (idx >= 0 && idx < reviews.size()) {
                    Commendation c = reviews.get(idx);
                    boolean admin = player.hasPermission("enthusiacommend.admin");
                    if (admin && event.isRightClick()) {
                        openRemovalConfirm(player, c, profileHolder.page, false, true);
                        return;
                    }
                    returnToProfile.put(player.getUniqueId(), new ProfileContext(profileHolder.targetId, profileHolder.page));
                    openReviewBook(player, c);
                }
            }
            return;
        }

        if (holder instanceof ReasonHolder reasonHolder) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;
            int slot = event.getRawSlot();
            List<RepCategory> cats = reasonHolder.positive ? positiveCategories() : negativeCategories();
            int[] slots = {10, 11, 12, 14, 15, 16};
            for (int i = 0; i < cats.size() && i < slots.length; i++) {
                if (slot == slots[i]) {
                    if (!repService.canEdit(player.getUniqueId(), reasonHolder.targetId)) {
                        player.sendMessage(ChatColor.RED + "You must wait before editing your commendation.");
                        player.closeInventory();
                        return;
                    }
                    double hours = plugin.getPlaytimeService().getActiveHours(player);
                    if (hours < plugin.getRepConfig().getMinActivePlaytimeHours()) {
                        player.sendMessage(plugin.getMessages().get("rep.playtime-short",
                                Map.of("hours_required", String.valueOf(plugin.getRepConfig().getMinActivePlaytimeHours()),
                                        "hours_have", String.format(Locale.US, "%.1f", hours))));
                        return;
                    }
                    ProfileContext ctx = returnToProfile.get(player.getUniqueId());
                    int returnPage = ctx != null ? ctx.page() : 0;
                    player.closeInventory();
                    RepConfig.InputMode mode = plugin.getRepConfig().getInputMode();
                    switch (mode) {
                        case CHAT -> {
                            suppressReopen.add(player.getUniqueId());
                            startChatFlow(player, reasonHolder.targetId, cats.get(i), returnPage);
                        }
                        case ANVIL -> openInputChoice(player, reasonHolder.targetId, cats.get(i), returnPage);
                        default -> openInputChoice(player, reasonHolder.targetId, cats.get(i), returnPage);
                    }
                    return;
                }
            }
        }

        if (holder instanceof AnvilHolder anvilHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() == 2) {
                String rename = null;
                Inventory inv = event.getInventory();
                if (inv instanceof AnvilInventory anvilInv) {
                    rename = anvilInv.getRenameText();
                }
                if (rename == null || rename.isEmpty()) {
                    ItemStack result = event.getCurrentItem();
                    ItemMeta meta = result != null ? result.getItemMeta() : null;
                    if (meta != null && meta.hasDisplayName()) {
                        rename = meta.getDisplayName();
                    }
                }
                if (rename == null) rename = "";
                player.closeInventory();
                handleTextSubmission(player, anvilHolder.targetId, anvilHolder.category, rename, anvilHolder.returnPage);
            }
            return;
        }

        if (holder instanceof InputChoiceHolder choice) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;
            int slot = event.getRawSlot();
            if (slot == 11) {
                suppressReopen.add(player.getUniqueId());
                player.closeInventory();
                startAnvilFlow(player, choice.targetId, choice.category, choice.returnPage);
            } else if (slot == 15) {
                suppressReopen.add(player.getUniqueId());
                player.closeInventory();
                startChatFlow(player, choice.targetId, choice.category, choice.returnPage);
            }
            return;
        }

        if (holder instanceof ConfirmRemovalHolder confirm) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot == 11) {
                if (confirm.logRemoval) {
                    var removed = repService.removeCommendationLogged(player.getUniqueId(), confirm.giverId, confirm.targetId, confirm.applyCooldown);
                    if (removed != null) {
                        player.sendMessage(ChatColor.RED + "Removed commendation from " + repService.nameOf(confirm.giverId) + ".");
                    } else {
                        player.sendMessage(ChatColor.RED + "That commendation was already removed.");
                    }
                } else {
                    repService.removeCommendationWithCooldown(confirm.giverId, confirm.targetId);
                    player.sendMessage(ChatColor.GOLD + "Removed your commendation for " + repService.nameOf(confirm.targetId) + ".");
                }
                reopenProfile(player, confirm.returnPage, confirm.targetId);
                return;
            }
            if (slot == 15) {
                reopenProfile(player, confirm.returnPage, confirm.targetId);
            }
        }

        if (holder instanceof RemovedLogHolder logHolder) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot == 45) {
                openRemovedLog(player, Math.max(0, logHolder.page - 1));
                return;
            }
            if (slot == 53) {
                openRemovedLog(player, logHolder.page + 1);
                return;
            }
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;
            ItemMeta meta = clicked.getItemMeta();
            if (meta == null) return;
            String id = meta.getPersistentDataContainer().get(removedIdKey, PersistentDataType.STRING);
            if (id != null) {
                openRestoreConfirm(player, id, logHolder.page);
            }
            return;
        }

        if (holder instanceof ActiveReportsHolder reportsHolder) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;
            int slot = event.getRawSlot();
            if (slot == 45) {
                openActiveReports(player, Math.max(0, reportsHolder.page - 1));
                return;
            }
            if (slot == 53) {
                openActiveReports(player, reportsHolder.page + 1);
                return;
            }
            int reviewSlotIndex = REVIEW_SLOTS.indexOf(slot);
            if (reviewSlotIndex != -1) {
                List<RepService.SuspiciousRepCase> cases = activeReports();
                int idx = reportsHolder.page * REVIEW_SLOTS.size() + reviewSlotIndex;
                if (idx >= 0 && idx < cases.size()) {
                    sendReportDetails(player, cases.get(idx));
                }
            }
            return;
        }

        if (holder instanceof ConfirmRestoreHolder confirm) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot == 11) {
                boolean ok = repService.restoreRemoved(confirm.removalId);
                if (ok) {
                    player.sendMessage(ChatColor.GREEN + "Restored rep entry " + confirm.removalId + ".");
                } else {
                    player.sendMessage(ChatColor.RED + "Could not restore entry (not found or already exists).");
                }
                openRemovedLog(player, confirm.returnPage);
                return;
            }
            if (slot == 15) {
                openRemovedLog(player, confirm.returnPage);
            }
        }
    }

    /* ===================== Text input flows ===================== */

    private void startChatFlow(Player player, UUID targetId, RepCategory category, int returnPage) {
        pendingChat.put(player.getUniqueId(), new PendingChat(targetId, category, returnPage));
        player.sendMessage(ChatColor.GOLD + "Type your rep story in chat now. It will be private. Type 'cancel' to abort.");
        player.spigot().sendMessage(new ComponentBuilder(ChatColor.YELLOW + "[Click to cancel]")
                .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rep-cancel"))
                .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ComponentBuilder(ChatColor.RED + "Cancel this rep").create()))
                .create());
    }

    private void startAnvilFlow(Player player, UUID targetId, RepCategory category, int returnPage) {
        Inventory inv = Bukkit.createInventory(new AnvilHolder(targetId, category, returnPage), InventoryType.ANVIL,
                ChatColor.DARK_GREEN + "Rep: " + repService.nameOf(targetId));
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "Type your review here");
            paper.setItemMeta(meta);
        }
        inv.setItem(0, paper);
        player.openInventory(inv);
    }

    private void openInputChoice(Player player, UUID targetId, RepCategory category, int returnPage) {
        Inventory inv = Bukkit.createInventory(new InputChoiceHolder(targetId, category, returnPage), 27, ChatColor.DARK_GREEN + "Choose input");
        ItemStack filler = backgroundFiller(player);
        if (filler != null) {
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        }
        inv.setItem(11, simpleButton(Material.ANVIL, ChatColor.GREEN + "Use Anvil", List.of(ChatColor.GRAY + "Click to type via anvil")));
        inv.setItem(15, simpleButton(Material.PAPER, ChatColor.AQUA + "Use Chat", List.of(ChatColor.GRAY + "Click to type in chat", ChatColor.GRAY + "Type 'cancel' or click cancel link")));
        player.openInventory(inv);
    }

    private void handleTextSubmission(Player player, UUID targetId, RepCategory category, String text, int returnPage) {
        if (text == null) text = "";
        text = text.trim();
        if (text.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "Rep entry cancelled.");
            reopenProfile(player, returnPage, targetId);
            return;
        }
        if (text.length() > 1024) text = text.substring(0, 1024);

        if (!repService.canEdit(player.getUniqueId(), targetId)) {
            player.sendMessage(ChatColor.RED + "You must wait before editing your commendation.");
            reopenProfile(player, returnPage, targetId);
            return;
        }

        double hours = plugin.getPlaytimeService().getActiveHours(player);
        if (hours < plugin.getRepConfig().getMinActivePlaytimeHours()) {
            player.sendMessage(plugin.getMessages().get("rep.playtime-short",
                    Map.of("hours_required", String.valueOf(plugin.getRepConfig().getMinActivePlaytimeHours()),
                            "hours_have", String.format(Locale.US, "%.1f", hours))));
            reopenProfile(player, returnPage, targetId);
            return;
        }

        String ipHash = repService.hashIp(player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : null);
        RepService.CommendationResult res = repService.addOrUpdateCommendation(
                player.getUniqueId(),
                targetId,
                category != null ? category.isPositive() : true,
                category != null ? category : RepCategory.OTHER_POSITIVE,
                text,
                ipHash
        );

        if (!res.success()) {
            long remaining = res.cooldownRemainingMillis();
            long hoursLeft = (long) Math.ceil(remaining / 1000.0 / 3600.0);
            player.sendMessage(plugin.getMessages().get("rep.cooldown",
                    Map.of("hours", String.valueOf(hoursLeft))));
            reopenProfile(player, returnPage, targetId);
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
        String amount = res.commendation().isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1";
        String categoryName = displayName(res.commendation().getCategory());
        String newRep = plugin.getRepConfig().formatColoredScore(repService.getScore(targetId));

        player.sendMessage(plugin.getMessages().get("rep.give-success",
                Map.of("amount", amount, "target", target.getName(), "category", categoryName, "rep", newRep)));

        if (target.isOnline()) {
            Player t = target.getPlayer();
            if (t != null) {
                t.sendMessage(plugin.getMessages().get("rep.receive",
                        Map.of("giver", player.getName(), "amount", amount, "category", categoryName, "rep", newRep)));
            }
        }

        reopenProfile(player, returnPage, targetId);
    }

    /* ===================== Book capture ===================== */

    private void startBookFlow(Player player, UUID targetId, RepCategory category, int returnPage, boolean autoOpen) {
        ItemStack previous = player.getInventory().getItemInMainHand();
        if (isRepBook(previous)) {
            previous = null;
        }
        ItemStack previousCopy = previous == null ? null : previous.clone();
        int slot = player.getInventory().getHeldItemSlot();
        pendingBooks.remove(player.getUniqueId());
        cancelTimeout(player.getUniqueId());

        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Rep for " + repService.nameOf(targetId));
            meta.getPersistentDataContainer().set(bookKey, PersistentDataType.STRING, targetId.toString());
            meta.getPersistentDataContainer().set(bookCategoryKey, PersistentDataType.STRING, category.name());
            meta.setPages(""); // ensure an editable page exists
            book.setItemMeta(meta);
        }

        pendingBooks.put(player.getUniqueId(), new PendingBook(targetId, category, returnPage, previousCopy, slot));
        player.getInventory().setItem(slot, book);
        player.updateInventory();

        if (autoOpen) {
            player.sendMessage(ChatColor.GRAY + "Opening rep book... write your story and sign/save to submit. The book will be removed automatically.");
            Bukkit.getScheduler().runTask(plugin, () -> openFocusedBook(player, slot));
        } else {
            player.sendMessage(ChatColor.GRAY + "You received a rep book. Open it to type your story. Moving slots/closing will clear it.");
        }
        scheduleTimeout(player.getUniqueId());
    }

    private void openFocusedBook(Player player, int slot) {
        ItemStack writable = player.getInventory().getItem(slot);
        if (!isRepBook(writable)) return;

        ItemStack written = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta writtenMeta = (BookMeta) Bukkit.getItemFactory().getItemMeta(Material.WRITTEN_BOOK);
        ItemMeta srcMeta = writable.getItemMeta();
        if (writtenMeta == null || srcMeta == null) return;
        writtenMeta.setDisplayName(srcMeta.getDisplayName());
        writtenMeta.setLore(srcMeta.getLore());
        writtenMeta.setTitle(srcMeta.getDisplayName());
        writtenMeta.setAuthor("Enthusia");
        var pdc = srcMeta.getPersistentDataContainer();
        String tgt = pdc.get(bookKey, PersistentDataType.STRING);
        String cat = pdc.get(bookCategoryKey, PersistentDataType.STRING);
        if (tgt != null) writtenMeta.getPersistentDataContainer().set(bookKey, PersistentDataType.STRING, tgt);
        if (cat != null) writtenMeta.getPersistentDataContainer().set(bookCategoryKey, PersistentDataType.STRING, cat);
        writtenMeta.setPages(""); // blank page to focus
        written.setItemMeta(writtenMeta);

        player.getInventory().setItem(slot, written);
        player.updateInventory();
        sendOpenBookPacket(player);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            PendingBook pending = pendingBooks.get(player.getUniqueId());
            if (pending == null) return;
            player.getInventory().setItem(slot, writable);
            player.updateInventory();
            sendOpenBookPacket(player);
        }, 1L);
    }

    private boolean sendOpenBookPacket(Player player) {
        try {
            Object craft = player.getClass().getMethod("getHandle").invoke(player);
            Object connection = craft.getClass().getField("connection").get(craft);
            Class<?> interactionHand = Class.forName("net.minecraft.world.InteractionHand");
            Object mainHand = Enum.valueOf((Class<Enum>) interactionHand, "MAIN_HAND");
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundOpenBookPacket");
            Object packet = packetClass.getConstructor(interactionHand).newInstance(mainHand);
            for (java.lang.reflect.Method m : connection.getClass().getMethods()) {
                if ((m.getName().equals("send") || m.getName().equals("sendPacket")) && m.getParameterCount() == 1) {
                    if (m.getParameterTypes()[0].isAssignableFrom(packetClass)) {
                        m.invoke(connection, packet);
                        return true;
                    }
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().fine("Failed to open virtual book: " + ex.getMessage());
        }
        return false;
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PendingChat pending = pendingChat.remove(player.getUniqueId());
        if (pending == null) return;
        event.setCancelled(true);
        String msg = event.getMessage();
        if ("cancel".equalsIgnoreCase(msg.trim())) {
            player.sendMessage(ChatColor.YELLOW + "Rep entry cancelled.");
            Bukkit.getScheduler().runTask(plugin, () -> reopenProfile(player, pending.returnPage(), pending.targetId()));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> handleTextSubmission(player, pending.targetId(), pending.category(), msg, pending.returnPage()));
    }

    @EventHandler
    public void onBookEdit(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        PendingBook pending = pendingBooks.remove(player.getUniqueId());

        ItemStack edited = player.getInventory().getItemInMainHand();
        ItemStack check = isRepBook(edited) ? edited : findRepBook(player);
        boolean ours = isRepBook(check);
        if (!ours && pending == null) return;

        event.setSigning(false);
        event.setCancelled(true);
        String text = String.join("\n", event.getNewBookMeta().getPages());
        text = text.trim();
        if (text.length() > 1024) text = text.substring(0, 1024);

        UUID targetId = pending != null ? pending.targetId : null;
        RepCategory category = pending != null ? pending.category : null;
        if (targetId == null && check != null) {
            ItemMeta meta = check.getItemMeta();
            if (meta != null) {
                String raw = meta.getPersistentDataContainer().get(bookKey, PersistentDataType.STRING);
                if (raw != null) targetId = UUID.fromString(raw);
                if (category == null) {
                    String catRaw = meta.getPersistentDataContainer().get(bookCategoryKey, PersistentDataType.STRING);
                    if (catRaw != null) {
                        try {
                            category = RepCategory.valueOf(catRaw);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            }
        }

        restorePreviousItem(player, pending);
        purgeRepBooks(player);
        cancelTimeout(player.getUniqueId());

        if (targetId == null) {
            player.sendMessage(ChatColor.RED + "Could not find target for this commendation.");
            return;
        }

        int returnPage = pending != null ? pending.returnPage : 0;
        handleTextSubmission(player, targetId, category, text, returnPage);
    }

    private void reopenProfile(Player player, PendingBook pending, UUID targetId) {
        int page = pending != null ? pending.returnPage : 0;
        reopenProfile(player, page, targetId);
    }

    private void reopenProfile(Player player, int page, UUID targetId) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            returnToProfile.remove(player.getUniqueId());
            openProfile(player, Bukkit.getOfflinePlayer(targetId), page);
        });
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isRepBook(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Finish your rep book before dropping items.");
        }
        reopenFromReview(event.getPlayer());
        suppressReopen.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (pendingBooks.containsKey(event.getPlayer().getUniqueId()) && (isRepBook(event.getMainHandItem()) || isRepBook(event.getOffHandItem()))) {
            event.setCancelled(true);
            purgeAndRestore(event.getPlayer());
        }
        reopenFromReview(event.getPlayer());
        suppressReopen.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        PendingBook pending = pendingBooks.get(id);
        if (pending != null) {
            // switching away implies they closed the book
            purgeAndRestore(event.getPlayer());
        }
        reopenFromReview(event.getPlayer());
        suppressReopen.remove(id);
    }

    private void purgeAndRestore(Player player) {
        PendingBook pending = pendingBooks.remove(player.getUniqueId());
        if (pending != null) {
            restorePreviousItem(player, pending);
            purgeRepBooks(player);
            cancelTimeout(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PendingBook pending = pendingBooks.remove(event.getPlayer().getUniqueId());
        if (pending != null) {
            restorePreviousItem(event.getPlayer(), pending);
            purgeRepBooks(event.getPlayer());
            cancelTimeout(event.getPlayer().getUniqueId());
        }
        pendingChat.remove(event.getPlayer().getUniqueId());
        pendingReturnAfterBook.remove(event.getPlayer().getUniqueId());
        returnToProfile.remove(event.getPlayer().getUniqueId());
        suppressReopen.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        PendingBook pending = pendingBooks.remove(player.getUniqueId());
        if (pending != null) {
            restorePreviousItem(player, pending);
            purgeRepBooks(player);
            cancelTimeout(player.getUniqueId());
            return;
        }
        if (event.getInventory().getHolder() instanceof AnvilHolder anvilHolder) {
            reopenProfile(player, anvilHolder.returnPage, anvilHolder.targetId);
            return;
        }
        reopenFromReview(player);
        if (suppressReopen.remove(player.getUniqueId())) {
            return;
        }
        if (event.getInventory().getHolder() instanceof ProfileHolder || event.getInventory().getHolder() instanceof ReasonHolder) return;
        ProfileContext ctx = returnToProfile.remove(player.getUniqueId());
        if (ctx != null && !pendingBooks.containsKey(player.getUniqueId())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                OfflinePlayer target = Bukkit.getOfflinePlayer(ctx.targetId());
                openProfile(player, target, ctx.page());
            });
        }
    }

    /* ===================== Helpers ===================== */

    private boolean isRepBook(ItemStack item) {
        if (item == null || item.getType() != Material.WRITABLE_BOOK) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(bookKey, PersistentDataType.STRING);
    }

    private ItemStack findRepBook(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isRepBook(stack)) return stack;
        }
        return null;
    }

    private void restorePreviousItem(Player player, PendingBook pending) {
        if (pending == null) return;
        ItemStack previous = pending.previousItem();
        player.getInventory().setItem(pending.previousSlot(), previous);
        player.updateInventory();
    }

    private void purgeRepBooks(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isRepBook(stack)) {
                player.getInventory().setItem(i, null);
            }
        }
        player.updateInventory();
    }

    private void reopenFromReview(Player player) {
        ProfileContext ctx = pendingReturnAfterBook.remove(player.getUniqueId());
        if (ctx != null) {
            reopenProfile(player, ctx.page(), ctx.targetId());
        }
    }

    private void scheduleTimeout(UUID playerId) {
        cancelTimeout(playerId);
        int taskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null) {
                purgeAndRestore(p);
            }
        }, 20 * 20L).getTaskId(); // 20s timeout
        pendingTimeoutTasks.put(playerId, taskId);
    }

    private void cancelTimeout(UUID playerId) {
        Integer id = pendingTimeoutTasks.remove(playerId);
        if (id != null) {
            Bukkit.getScheduler().cancelTask(id);
        }
    }

    private ChatColor getRepColor(int score) {
        if (score > 0) return ChatColor.GREEN;
        if (score < 0) return ChatColor.RED;
        return ChatColor.YELLOW;
    }

    private ItemStack backgroundFiller(Player viewer) {
        if (isBedrockPlayer(viewer)) return null;
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.setDisplayName(" ");
            filler.setItemMeta(fillerMeta);
        }
        return filler;
    }

    private boolean isBedrockPlayer(Player player) {
        // Floodgate/Geyser prefixes Bedrock player names with '*'
        return player != null && player.getName() != null && player.getName().startsWith("*");
    }

    private ItemStack simpleButton(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) meta.setLore(lore);
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
            if (cooldownMillis > 0) {
                long hours = (long) Math.ceil(cooldownMillis / 1000.0 / 3600.0);
                lore.add(ChatColor.RED + "Edit available in " + hours + "h.");
            }
        }
        if (cooldownMillis <= 0) {
            lore.add(ChatColor.YELLOW + "Click to continue.");
        }
        return lore;
    }

    private List<String> buildCurrentEffectsLore(RepAppliedEffects eff) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Active effects:");

        boolean any = false;
        if (eff.movementSpeedPercent != 0) {
            lore.add(ChatColor.WHITE + "• Movement speed " + ChatColor.GRAY + "(Spawn): " + ChatColor.YELLOW + formatPercent(eff.movementSpeedPercent));
            any = true;
        }
        if (eff.potionDurationPercent != 0) {
            lore.add(ChatColor.WHITE + "• Potion duration " + ChatColor.GRAY + "(Spawn): " + ChatColor.YELLOW + formatPercent(eff.potionDurationPercent));
            any = true;
        }
        if (eff.fireworkDurationPercent != 0) {
            lore.add(ChatColor.WHITE + "• Rocket flight duration " + ChatColor.GRAY + "(Everywhere): " + ChatColor.YELLOW + formatPercent(eff.fireworkDurationPercent));
            any = true;
        }
        if (eff.pearlCooldownSeconds > 0) {
            lore.add(ChatColor.WHITE + "• Ender pearl cooldown " + ChatColor.GRAY + "(Spawn): " + ChatColor.YELLOW + eff.pearlCooldownSeconds + "s");
            any = true;
        }
        if (eff.windCooldownSeconds > 0) {
            lore.add(ChatColor.WHITE + "• Wind charge cooldown " + ChatColor.GRAY + "(Spawn): " + ChatColor.YELLOW + eff.windCooldownSeconds + "s");
            any = true;
        }
        if (eff.glow) {
            lore.add(ChatColor.WHITE + "• Glow effect " + ChatColor.GRAY + "(Spawn): " + ChatColor.YELLOW + (eff.glowColor != null ? eff.glowColor.name() : "White"));
            any = true;
        }
        if (eff.stalkable) {
            lore.add(ChatColor.WHITE + "• Stalkable " + ChatColor.GRAY + "(Everywhere): " + ChatColor.YELLOW + "Others can /rep stalk you and get notified when you enter spawn");
            any = true;
        }
        if (eff.cashbackPercent > 0) {
            lore.add(ChatColor.WHITE + "• Cashback " + ChatColor.GRAY + "(Everywhere): " + ChatColor.YELLOW + eff.cashbackPercent + "%");
            any = true;
        }

        if (!any) {
            lore.add(ChatColor.GRAY + "You currently have no rep-based buffs or penalties.");
        }
        return lore;
    }

    private String formatPercent(int value) {
        return (value > 0 ? "+" : "") + value + "%";
    }

    private List<RepCategory> positiveCategories() {
        return List.of(
                RepCategory.WAS_KIND,
                RepCategory.HELPED_ME,
                RepCategory.GAVE_ITEMS,
                RepCategory.TRUSTWORTHY,
                RepCategory.GOOD_STALL,
                RepCategory.OTHER_POSITIVE
        );
    }

    private List<RepCategory> negativeCategories() {
        return List.of(
                RepCategory.SCAMMED,
                RepCategory.SPAWN_KILLED,
                RepCategory.GRIEFED,
                RepCategory.TRAPPED,
                RepCategory.SCAM_STALL,
                RepCategory.OTHER_NEGATIVE
        );
    }

    private String displayName(RepCategory cat) {
        return switch (cat) {
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

    private ItemStack reviewItem(Commendation c, boolean adminView) {
        ItemStack head = HeadUtil.createPlayerHead(plugin, c.getGiver(),
                (c.isPositive() ? ChatColor.GREEN : ChatColor.RED) + repService.nameOf(c.getGiver()));
        ItemMeta meta = head.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Category: " + ChatColor.YELLOW + displayName(c.getCategory()));
            lore.add(ChatColor.GRAY + "Date: " + ChatColor.WHITE + dateFmt.format(Instant.ofEpochMilli(c.getCreatedAt())));
            lore.add(ChatColor.DARK_GRAY + "----------------");
            lore.add(ChatColor.WHITE + preview(c.getReasonText()));
            lore.add(ChatColor.YELLOW + "Click: view full text");
            if (adminView) {
                lore.add(ChatColor.RED + "Right-click: delete rep (admin)");
            }
            meta.setLore(lore);
            head.setItemMeta(meta);
        }
        return head;
    }

    private void openRemovalConfirm(Player admin, Commendation commendation, int returnPage, boolean applyCooldown, boolean logRemoval) {
        Inventory inv = Bukkit.createInventory(new ConfirmRemovalHolder(commendation.getTarget(), commendation.getGiver(), returnPage, applyCooldown, logRemoval),
                27, ChatColor.RED + "Confirm removal");
        ItemStack filler = backgroundFiller(admin);
        if (filler != null) {
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        }

        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta meta = info.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "Remove rep from " + repService.nameOf(commendation.getGiver()));
            meta.setLore(List.of(
                    ChatColor.GRAY + "Target: " + ChatColor.WHITE + repService.nameOf(commendation.getTarget()),
                    ChatColor.GRAY + "Value: " + (commendation.isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1"),
                    ChatColor.GRAY + "Category: " + ChatColor.WHITE + displayName(commendation.getCategory()),
                    ChatColor.DARK_GRAY + "----------------",
                    ChatColor.RED + "This will delete the rep entry.",
                    applyCooldown ? ChatColor.GRAY + "Cooldown will apply to the giver." : ChatColor.GRAY + "No cooldown will apply."
            ));
            info.setItemMeta(meta);
        }
        inv.setItem(13, info);

        inv.setItem(11, simpleButton(Material.LIME_CONCRETE, ChatColor.GREEN + "Confirm removal",
                List.of(ChatColor.GRAY + "Delete this rep entry.")));
        inv.setItem(15, simpleButton(Material.RED_CONCRETE, ChatColor.RED + "Cancel",
                List.of(ChatColor.GRAY + "Return without deleting.")));
        admin.openInventory(inv);
    }

    public void openRemovedLog(Player admin, int page) {
        List<RepService.RemovedRep> removed = repService.getRemovedLog().stream()
                .sorted(Comparator.comparingLong(RepService.RemovedRep::removedAt).reversed())
                .toList();
        int pageSize = REVIEW_SLOTS.size();
        int maxPage = Math.max(0, (removed.size() - 1) / pageSize);
        if (page < 0) page = 0;
        if (page > maxPage) page = maxPage;

        Inventory inv = Bukkit.createInventory(new RemovedLogHolder(page), 54,
                ChatColor.DARK_RED + "Removed Reps [" + (page + 1) + "/" + (maxPage + 1) + "]");

        ItemStack filler = backgroundFiller(admin);
        if (filler != null) {
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        }

        int start = page * pageSize;
        for (int i = 0; i < pageSize; i++) {
            int idx = start + i;
            if (idx >= removed.size()) break;
            RepService.RemovedRep r = removed.get(idx);
            inv.setItem(REVIEW_SLOTS.get(i), removedLogItem(r));
        }

        if (page > 0) inv.setItem(45, simpleButton(Material.ARROW, ChatColor.YELLOW + "Prev", List.of()));
        if (page < maxPage) inv.setItem(53, simpleButton(Material.ARROW, ChatColor.YELLOW + "Next", List.of()));

        admin.openInventory(inv);
    }

    public void openActiveReports(Player admin, int page) {
        List<RepService.SuspiciousRepCase> cases = activeReports();
        int pageSize = REVIEW_SLOTS.size();
        int maxPage = Math.max(0, (cases.size() - 1) / pageSize);
        if (page < 0) page = 0;
        if (page > maxPage) page = maxPage;

        Inventory inv = Bukkit.createInventory(new ActiveReportsHolder(page), 54,
                ChatColor.DARK_RED + "Active Rep Reports [" + (page + 1) + "/" + (maxPage + 1) + "]");

        ItemStack filler = backgroundFiller(admin);
        if (filler != null) {
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        }

        if (cases.isEmpty()) {
            inv.setItem(22, simpleButton(Material.PAPER, ChatColor.GRAY + "No active reports", List.of()));
            admin.openInventory(inv);
            return;
        }

        int start = page * pageSize;
        for (int i = 0; i < pageSize; i++) {
            int idx = start + i;
            if (idx >= cases.size()) break;
            RepService.SuspiciousRepCase c = cases.get(idx);
            inv.setItem(REVIEW_SLOTS.get(i), activeReportItem(c));
        }

        if (page > 0) inv.setItem(45, simpleButton(Material.ARROW, ChatColor.YELLOW + "Prev", List.of()));
        if (page < maxPage) inv.setItem(53, simpleButton(Material.ARROW, ChatColor.YELLOW + "Next", List.of()));

        admin.openInventory(inv);
    }

    private ItemStack removedLogItem(RepService.RemovedRep r) {
        Commendation c = r.commendation();
        UUID removerId = r.removedBy();
        ItemStack item = removerId != null
                ? HeadUtil.createPlayerHead(plugin, removerId, ChatColor.YELLOW + r.id())
                : new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + r.id() + ChatColor.GRAY + " - " + repService.nameOf(c.getGiver()));
            String value = c.isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1";
            String remover = r.removedBy() != null ? repService.nameOf(r.removedBy()) : "unknown";
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Target: " + ChatColor.WHITE + repService.nameOf(c.getTarget()));
            lore.add(ChatColor.GRAY + "Value: " + value);
            lore.add(ChatColor.GRAY + "Category: " + ChatColor.WHITE + displayName(c.getCategory()));
            lore.add(ChatColor.GRAY + "Removed: " + ChatColor.WHITE + dateFmt.format(Instant.ofEpochMilli(r.removedAt())));
            lore.add(ChatColor.GRAY + "By: " + ChatColor.WHITE + remover);
            lore.add(ChatColor.DARK_GRAY + "----------------");
            lore.add(ChatColor.YELLOW + "Click to restore this rep.");
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(removedIdKey, PersistentDataType.STRING, r.id());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack activeReportItem(RepService.SuspiciousRepCase c) {
        ItemStack item = HeadUtil.createPlayerHead(plugin, c.getTarget(), ChatColor.YELLOW + repService.nameOf(c.getTarget()));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + repService.nameOf(c.getTarget()));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "IP: " + ChatColor.WHITE + c.ipHash());
            lore.add(ChatColor.GRAY + "Accounts: " + ChatColor.WHITE + formatNames(c.givers()));
            lore.add(ChatColor.GRAY + "Created: " + ChatColor.WHITE + dateFmt.format(Instant.ofEpochMilli(c.getCreatedAt())));
            lore.add(ChatColor.DARK_GRAY + "----------------");
            lore.add(ChatColor.YELLOW + "Click to post details in chat.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void openRestoreConfirm(Player admin, String removalId, int returnPage) {
        Inventory inv = Bukkit.createInventory(new ConfirmRestoreHolder(removalId, returnPage), 27, ChatColor.GREEN + "Restore rep?");
        ItemStack filler = backgroundFiller(admin);
        if (filler != null) {
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        }

        inv.setItem(11, simpleButton(Material.LIME_CONCRETE, ChatColor.GREEN + "Restore",
                List.of(ChatColor.GRAY + "Re-add this rep entry")));
        inv.setItem(15, simpleButton(Material.RED_CONCRETE, ChatColor.RED + "Cancel",
                List.of(ChatColor.GRAY + "Back to log")));
        admin.openInventory(inv);
    }

    private List<RepService.SuspiciousRepCase> activeReports() {
        return repService.getSuspiciousCases().stream()
                .filter(c -> !c.isResolved())
                .sorted(Comparator.comparingLong(RepService.SuspiciousRepCase::getCreatedAt).reversed())
                .toList();
    }

    private String resolveTargetArg(UUID target) {
        OfflinePlayer off = Bukkit.getOfflinePlayer(target);
        String name = off != null ? off.getName() : null;
        return name != null ? name : target.toString();
    }

    private String formatNames(Collection<UUID> ids) {
        List<String> names = new ArrayList<>();
        for (UUID id : ids) {
            names.add(repService.nameOf(id));
        }
        return String.join(", ", names);
    }

    private void sendReportDetails(Player admin, RepService.SuspiciousRepCase c) {
        String targetName = repService.nameOf(c.getTarget());
        String targetArg = resolveTargetArg(c.getTarget());
        admin.sendMessage(ChatColor.GOLD + "ALT REP REPORT: " + ChatColor.YELLOW + targetName
                + ChatColor.GRAY + " (IP " + ChatColor.YELLOW + c.ipHash() + ChatColor.GRAY + ")");
        admin.sendMessage(ChatColor.GRAY + "Accounts: " + ChatColor.WHITE + formatNames(c.givers()));
        admin.sendMessage(ChatColor.GRAY + "Created: " + ChatColor.WHITE + dateFmt.format(Instant.ofEpochMilli(c.getCreatedAt())));

        String inspectCmd = "/rep admin inspect " + targetArg + " " + c.ipHash();
        String resolveCmd = "/rep admin resolve " + targetArg + " " + c.ipHash();
        admin.spigot().sendMessage(new ComponentBuilder(ChatColor.YELLOW + "Inspect report")
                .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, inspectCmd))
                .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ComponentBuilder(ChatColor.GRAY + "Click to inspect this report").create()))
                .append(ChatColor.GRAY + " | ")
                .event((ClickEvent) null)
                .event((HoverEvent) null)
                .append(ChatColor.RED + "Resolve report")
                .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, resolveCmd))
                .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ComponentBuilder(ChatColor.GRAY + "Mark this report as resolved").create()))
                .create());
    }

    private String preview(String text) {
        if (text == null) return "";
        if (text.length() <= 60) return text;
        return text.substring(0, 57) + "...";
    }

    private List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        if (text == null) return lines;
        String[] words = text.split("\\s+");
        StringBuilder current = new StringBuilder();
        for (String w : words) {
            if (current.length() + w.length() + 1 > width) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(w);
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    private void openReviewBook(Player viewer, Commendation c) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta != null) {
            meta.setTitle("Rep from " + repService.nameOf(c.getGiver()));
            meta.setAuthor(repService.nameOf(c.getGiver()));
            List<String> pages = wrap(c.getReasonText(), 250).stream().map(s -> ChatColor.BLACK + s).toList();
            meta.setPages(pages);
            book.setItemMeta(meta);
        }
        viewer.openBook(book);
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            reopenFromReview(player);
        }
    }
}
