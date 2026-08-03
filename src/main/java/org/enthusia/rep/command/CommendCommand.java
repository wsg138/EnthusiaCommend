package org.enthusia.rep.command;

import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.analytics.ReputationChangeRecord;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;
import org.enthusia.rep.rep.RepService;
import org.enthusia.rep.rep.RepTradingAlertAccess;
import org.enthusia.rep.stalk.StalkSubscription;
import org.enthusia.rep.util.RepDateFormats;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class CommendCommand implements CommandExecutor, TabCompleter {
    private static final String PERMISSION_ADMIN = "enthusiacommend.rep.admin";
    private static final String PERMISSION_STALK = "enthusiacommend.rep.stalk";
    private static final int PAGE_SIZE = 10;
    private static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;
    private static final long MILLIS_PER_HOUR = 60L * 60L * 1000L;
    private static final List<String> PLAYER_ROOTS = List.of("top", "bottom", "reviews", "stalk", "give");
    private static final List<String> ADMIN_ROOTS = List.of("admin", "top", "bottom", "reviews", "stalk", "give");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of(
            "reload", "help", "get", "set", "add", "revoke", "remove", "reset", "history",
            "inspect", "resolve", "reports", "removed", "restore", "undo");
    private static final List<String> SELECTABLE_CATEGORIES = RepCategory.selectableValues().stream()
            .map(Enum::name)
            .toList();

    private final CommendPlugin plugin;
    private final RepService repService;
    private final DateTimeFormatter dateFormatter = RepDateFormats.dateTimeMinute();

    public CommendCommand(CommendPlugin plugin, RepService repService) {
        this.plugin = plugin;
        this.repService = repService;
    }

    static boolean canUseTradingAlerts(CommandSender sender) {
        return RepTradingAlertAccess.isAuthorized(sender);
    }

    static List<String> rootSubcommands(CommandSender sender) {
        List<String> roots = new ArrayList<>(sender.hasPermission(PERMISSION_ADMIN) ? ADMIN_ROOTS : PLAYER_ROOTS);
        if (canUseTradingAlerts(sender)) {
            roots.add("alerts");
        }
        return List.copyOf(roots);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("rep")) return false;
        if (args.length == 0) return openOwnProfile(sender);
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "admin" -> handleAdminRequest(sender, args);
            case "top" -> handleLeaderboard(sender, parseInt(args, 1, 10), false);
            case "bottom" -> handleLeaderboard(sender, parseInt(args, 1, 10), true);
            case "reviews" -> handleReviews(sender, args.length >= 2 ? args[1] : sender.getName());
            case "stalk" -> handleStalk(sender, args);
            case "give" -> handleGiveCommand(sender, args);
            case "alerts" -> handleAlerts(sender);
            default -> handleProfileLookup(sender, args[0]);
        };
    }

    private boolean openOwnProfile(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Usage: /rep <player>");
            return true;
        }
        plugin.getRepGuiManager().openProfile(player, player);
        return true;
    }

    private boolean handleAdminRequest(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use rep admin commands.");
            return true;
        }
        handleAdmin(sender, args);
        return true;
    }

    private boolean handleGiveCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 4) {
            sender.sendMessage(ChatColor.RED + "Usage: /rep give <player> <category> <reason>");
            return true;
        }
        OfflinePlayer target = resolveKnownPlayer(sender, args[1]);
        RepCategory category = parseCategory(args[2]);
        if (target == null || !validateDirectGive(player, target, category)) return true;
        RepService.CommendationResult result = repService.addOrUpdateCommendation(
                player.getUniqueId(), target.getUniqueId(), category.isPositive(), category,
                trimReason(joinReason(args, 3)), giverIpHash(player));
        if (!result.success()) {
            sendCooldownMessage(player, result);
            return true;
        }
        sendDirectGiveSuccess(player, target, result.commendation());
        return true;
    }

    private boolean handleProfileLookup(CommandSender sender, String targetName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.GOLD + "Rep for " + ChatColor.YELLOW + targetName + ChatColor.GOLD
                    + ": " + plugin.getRepConfig().formatColoredScore(repService.getScore(target.getUniqueId())));
            return true;
        }
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            sender.sendMessage(ChatColor.RED + "That player has never joined the server.");
            return true;
        }
        plugin.getRepGuiManager().openProfile(player, target);
        return true;
    }

    private OfflinePlayer resolveKnownPlayer(CommandSender sender, String input) {
        OfflinePlayer target = resolveOfflinePlayer(input);
        if (target.isOnline() || target.hasPlayedBefore()) return target;
        sender.sendMessage(plugin.getMessages().get("rep.not-found", Map.of("name", input)));
        return null;
    }

    private boolean validateDirectGive(Player giver, OfflinePlayer target, RepCategory category) {
        if (category == null) {
            sendInvalidCategory(giver);
            return false;
        }
        if (giver.getUniqueId().equals(target.getUniqueId())) {
            giver.sendMessage(plugin.getMessages().get("rep.self"));
            return false;
        }
        if (!plugin.getPlaytimeService().isAvailable()) {
            giver.sendMessage(ChatColor.RED + "Active playtime tracking is unavailable. Rep is temporarily disabled.");
            return false;
        }
        double hours = plugin.getPlaytimeService().getActiveHours(giver);
        double required = plugin.getRepConfig().getMinActivePlaytimeHours();
        if (hours >= required) return true;
        giver.sendMessage(plugin.getMessages().get("rep.playtime-short", Map.of(
                "hours_required", String.valueOf(required),
                "hours_have", String.format(Locale.US, "%.1f", hours))));
        return false;
    }

    private void sendInvalidCategory(CommandSender sender) {
        sender.sendMessage(plugin.getMessages().get("rep.category-invalid",
                Map.of("list", String.join(", ", SELECTABLE_CATEGORIES))));
    }

    private String trimReason(String reason) {
        String value = reason == null ? "" : reason.trim();
        int max = plugin.getRepConfig().getMaxReasonLength();
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String giverIpHash(Player giver) {
        String address = giver.getAddress() != null && giver.getAddress().getAddress() != null
                ? giver.getAddress().getAddress().getHostAddress() : null;
        return repService.hashIp(address);
    }

    private void sendCooldownMessage(Player giver, RepService.CommendationResult result) {
        if (result.cooldownRemainingMillis() <= 0L) {
            giver.sendMessage(ChatColor.RED + "That reputation category is not available.");
            return;
        }
        long hours = (long) Math.ceil(result.cooldownRemainingMillis() / (double) MILLIS_PER_HOUR);
        giver.sendMessage(plugin.getMessages().get("rep.cooldown", Map.of("hours", String.valueOf(hours))));
    }

    private void sendDirectGiveSuccess(Player giver, OfflinePlayer target, Commendation commendation) {
        String amount = coloredValue(commendation.getScoreValue());
        String score = plugin.getRepConfig().formatColoredScore(repService.getScore(target.getUniqueId()));
        giver.sendMessage(plugin.getMessages().get("rep.give-success", Map.of(
                "amount", amount, "target", safeName(target),
                "category", displayName(commendation.getCategory()), "rep", score)));
        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null) {
            onlineTarget.sendMessage(plugin.getMessages().get("rep.receive", Map.of(
                    "giver", giver.getName(), "amount", amount,
                    "category", displayName(commendation.getCategory()), "rep", score)));
        }
    }

    private boolean handleReviews(CommandSender sender, String targetName) {
        OfflinePlayer target = resolveKnownPlayer(sender, targetName);
        if (target == null) return true;
        List<Commendation> reviews = repService.getReceivedCommendations(target.getUniqueId());
        sender.sendMessage(ChatColor.GOLD + "--- Reviews for " + ChatColor.YELLOW + safeName(target) + ChatColor.GOLD + " ---");
        if (reviews.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No reviews yet.");
            return true;
        }
        reviews.stream().limit(10).forEach(entry -> sender.sendMessage(
                coloredValue(entry.getScoreValue()) + ChatColor.GRAY + " from " + ChatColor.YELLOW
                        + repService.nameOf(entry.getGiver()) + ChatColor.GRAY + " ["
                        + displayName(entry.getCategory()) + "]: " + ChatColor.WHITE + trimPreview(entry.getReasonText())));
        return true;
    }

    private boolean handleLeaderboard(CommandSender sender, int limit, boolean lowest) {
        if (sender instanceof Player player) {
            plugin.getRepLeaderboardGui().open(player, lowest);
            return true;
        }
        sender.sendMessage(ChatColor.GOLD + (lowest ? "--- Lowest Rep ---" : "--- Top Rep ---"));
        int rank = 1;
        for (Map.Entry<UUID, Integer> entry : repService.top(Math.max(1, Math.min(limit, 100)), lowest)) {
            sender.sendMessage(ChatColor.YELLOW + "#" + rank++ + " " + ChatColor.GOLD + repService.nameOf(entry.getKey())
                    + ChatColor.GRAY + " - " + plugin.getRepConfig().formatColoredScore(entry.getValue()));
        }
        return true;
    }

    private boolean handleAlerts(CommandSender sender) {
        if (!canUseTradingAlerts(sender)) {
            sender.sendMessage(plugin.getMessages().get("rep.no-permission"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }
        boolean enabled = repService.toggleTradingAlerts(player.getUniqueId());
        player.sendMessage((enabled ? ChatColor.GREEN : ChatColor.YELLOW)
                + "Rep-trading alerts are now " + (enabled ? "enabled" : "disabled") + ".");
        return true;
    }

    private boolean handleStalk(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }
        if (!player.hasPermission(PERMISSION_STALK)) {
            player.sendMessage(plugin.getMessages().get("rep.no-permission"));
            return true;
        }
        if (args.length < 2) {
            sendStalkUsage(sender);
            return true;
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> handleStalkList(sender, player);
            case "cancel" -> handleStalkCancel(sender, player, args);
            default -> handleStalkPurchase(sender, player, args);
        };
    }

    private void sendStalkUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "/rep stalk <player> [days]");
        sender.sendMessage(ChatColor.GOLD + "/rep stalk list");
        sender.sendMessage(ChatColor.GOLD + "/rep stalk cancel <player>");
    }

    private boolean handleStalkList(CommandSender sender, Player player) {
        List<StalkSubscription> subscriptions = plugin.getStalkManager().getSubscriptionsByStalker(player.getUniqueId());
        if (subscriptions.isEmpty()) {
            sender.sendMessage(plugin.getMessages().get("stalk.list-empty"));
            return true;
        }
        sender.sendMessage(ChatColor.GOLD + "Active stalks:");
        for (StalkSubscription subscription : subscriptions) {
            long hours = Math.max(0L, (subscription.expiresAt() - System.currentTimeMillis()) / MILLIS_PER_HOUR);
            sender.sendMessage(ChatColor.YELLOW + repService.nameOf(subscription.target()) + ChatColor.GRAY
                    + " -> " + hours + "h remaining");
        }
        return true;
    }

    private boolean handleStalkCancel(CommandSender sender, Player player, String[] args) {
        if (args.length < 3) {
            sendStalkUsage(sender);
            return true;
        }
        OfflinePlayer target = resolveOfflinePlayer(args[2]);
        plugin.getStalkManager().cancelSubscription(player.getUniqueId(), target.getUniqueId());
        sender.sendMessage(plugin.getMessages().get("stalk.cancelled", Map.of("target", safeName(target))));
        return true;
    }

    private boolean handleStalkPurchase(CommandSender sender, Player player, String[] args) {
        OfflinePlayer target = resolveKnownPlayer(sender, args[1]);
        if (target == null) return true;
        if (!plugin.getStalkManager().isStalkable(target.getUniqueId())) {
            sender.sendMessage(plugin.getMessages().get("stalk.not-stalkable"));
            return true;
        }
        int days = Math.max(1, Math.min(plugin.getRepConfig().getStalkMaxDays(), parseInt(args, 2, 1)));
        double cost = plugin.getRepConfig().getStalkCostPerDay() * days;
        if (plugin.getEconomy() == null) {
            sender.sendMessage(plugin.getMessages().get("stalk.no-economy"));
            return true;
        }
        if (plugin.getEconomy().getBalance(player) < cost) {
            sender.sendMessage(plugin.getMessages().get("stalk.not-enough", Map.of(
                    "cost", String.format(Locale.US, "%.2f", cost), "days", String.valueOf(days))));
            return true;
        }
        EconomyResponse response = plugin.getEconomy().withdrawPlayer(player, cost);
        if (!response.transactionSuccess()) {
            sender.sendMessage(ChatColor.RED + "The payment failed; no stalking subscription was created."
                    + (response.errorMessage == null || response.errorMessage.isBlank() ? "" : " " + response.errorMessage));
            return true;
        }
        plugin.getStalkManager().addSubscription(player.getUniqueId(), target.getUniqueId(), days * MILLIS_PER_DAY);
        sender.sendMessage(plugin.getMessages().get("stalk.purchased", Map.of(
                "target", safeName(target), "days", String.valueOf(days))));
        return true;
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("help")) {
            sendAdminHelp(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "reload" -> { plugin.reloadPluginConfig(); sender.sendMessage(ChatColor.GREEN + "EnthusiaCommend reloaded."); }
            case "get" -> handleAdminGet(sender, args);
            case "set" -> handleAdminScoreUpdate(sender, args, true);
            case "add" -> handleAdminScoreUpdate(sender, args, false);
            case "revoke" -> handleAdminRevoke(sender, args, false);
            case "remove" -> handleAdminRevoke(sender, args, true);
            case "reset" -> handleAdminReset(sender, args);
            case "history" -> handleAdminHistory(sender, args);
            case "inspect" -> handleAdminInspect(sender, args);
            case "resolve" -> handleAdminResolve(sender, args);
            case "reports" -> handleAdminReports(sender, args);
            case "removed" -> handleAdminRemoved(sender, args);
            case "restore", "undo" -> handleAdminRestore(sender, args);
            default -> sender.sendMessage(ChatColor.RED + "Unknown admin subcommand. Use /rep admin help.");
        }
    }

    private void handleAdminGet(CommandSender sender, String[] args) {
        if (args.length < 3) { sendAdminHelp(sender); return; }
        OfflinePlayer target = resolveOfflinePlayer(args[2]);
        sender.sendMessage(ChatColor.GOLD + "Rep for " + ChatColor.YELLOW + safeName(target) + ChatColor.GOLD + ": "
                + plugin.getRepConfig().formatColoredScore(repService.getScore(target.getUniqueId())));
        Map<RepCategory, Integer> categories = repService.getCategoryScores(target.getUniqueId());
        if (!categories.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Category totals:");
            categories.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sender.sendMessage(ChatColor.YELLOW + "- " + displayName(entry.getKey())
                            + ChatColor.GRAY + ": " + coloredValue(entry.getValue())));
        }
    }

    private void handleAdminScoreUpdate(CommandSender sender, String[] args, boolean absolute) {
        if (args.length < 4) { sendAdminHelp(sender); return; }
        OfflinePlayer target = resolveOfflinePlayer(args[2]);
        Integer value = tryParseInt(args[3]);
        if (value == null) {
            sender.sendMessage(ChatColor.RED + "Value must be a whole number.");
            return;
        }
        if (absolute) repService.setScoreByStaff(target.getUniqueId(), value, sender);
        else repService.adjustScoreByStaff(target.getUniqueId(), value, sender);
        sender.sendMessage(ChatColor.GOLD + (absolute ? "Set rep of " : "Adjusted rep of ")
                + ChatColor.YELLOW + safeName(target) + ChatColor.GOLD + " to "
                + plugin.getRepConfig().formatColoredScore(repService.getScore(target.getUniqueId())));
    }

    private void handleAdminRevoke(CommandSender sender, String[] args, boolean requireCategory) {
        if (args.length < (requireCategory ? 5 : 4)) { sendAdminHelp(sender); return; }
        OfflinePlayer target = resolveOfflinePlayer(args[2]);
        OfflinePlayer giver = resolveOfflinePlayer(args[3]);
        Commendation existing = repService.getCommendation(giver.getUniqueId(), target.getUniqueId());
        if (existing == null) {
            sender.sendMessage(ChatColor.RED + "No commendation from that giver to target.");
            return;
        }
        if (requireCategory) {
            RepCategory category = parseCategory(args[4]);
            if (category == null || existing.getCategory() != category) {
                sender.sendMessage(ChatColor.RED + "The current entry does not use that category.");
                return;
            }
        }
        UUID removerId = sender instanceof Player player ? player.getUniqueId() : null;
        RepService.RemovedRep removed = repService.removeCommendationLogged(
                removerId, giver.getUniqueId(), target.getUniqueId(), false);
        if (removed == null) {
            sender.sendMessage(ChatColor.RED + "The entry could not be removed.");
            return;
        }
        sender.sendMessage(ChatColor.GREEN + "Removed " + coloredValue(existing.getScoreValue()) + ChatColor.GREEN
                + " rep from " + safeName(giver) + " to " + safeName(target)
                + ". Restore ID: " + ChatColor.YELLOW + removed.id());
    }

    private void handleAdminReset(CommandSender sender, String[] args) {
        if (args.length < 3) { sendAdminHelp(sender); return; }
        OfflinePlayer target = resolveOfflinePlayer(args[2]);
        repService.resetAllByStaff(target.getUniqueId(), sender);
        sender.sendMessage(plugin.getMessages().get("admin.reset", Map.of("target", safeName(target))));
    }

    private void handleAdminHistory(CommandSender sender, String[] args) {
        if (args.length < 3) { sendAdminHelp(sender); return; }
        OfflinePlayer target = resolveOfflinePlayer(args[2]);
        int page = Math.max(1, parseInt(args, 3, 1));
        List<ReputationChangeRecord> history = plugin.getAnalyticsService()
                .playerHistory(target.getUniqueId(), Integer.MAX_VALUE);
        if (history.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No reputation history for " + safeName(target) + ".");
            return;
        }
        int maxPage = Math.max(1, (history.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int resolvedPage = Math.min(page, maxPage);
        sender.sendMessage(ChatColor.GOLD + "=== Rep history: " + ChatColor.YELLOW + safeName(target)
                + ChatColor.GOLD + " (" + resolvedPage + "/" + maxPage + ") ===");
        int start = (resolvedPage - 1) * PAGE_SIZE;
        for (int i = start; i < Math.min(start + PAGE_SIZE, history.size()); i++) {
            ReputationChangeRecord change = history.get(i);
            String actor = plugin.getAnalyticsService().actorName(change);
            String category = change.category() == null ? "" : " [" + displayName(change.category()) + "]";
            String action = change.action().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            sender.sendMessage(ChatColor.DARK_GRAY + dateFormatter.format(Instant.ofEpochMilli(change.timestamp()))
                    + " " + ChatColor.AQUA + action + " " + coloredValue(change.amount()) + ChatColor.GRAY + category
                    + " by " + ChatColor.WHITE + actor + ChatColor.GRAY + " -> "
                    + plugin.getRepConfig().formatColoredScore(change.newTotal())
                    + ChatColor.DARK_GRAY + " (" + trimPreview(change.reason()) + ")");
        }
    }

    private void handleAdminInspect(CommandSender sender, String[] args) {
        if (args.length < 3) { sendAdminHelp(sender); return; }
        OfflinePlayer target = resolveOfflinePlayer(args[2]);
        List<RepService.SuspiciousRepCase> cases = repService.getCasesForTarget(target.getUniqueId(), true);
        if (args.length >= 4) cases = cases.stream().filter(entry -> entry.key().equalsIgnoreCase(args[3])).toList();
        sender.sendMessage(ChatColor.GOLD + "Suspicious rep cases for " + ChatColor.YELLOW + safeName(target));
        if (cases.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "None.");
            return;
        }
        for (RepService.SuspiciousRepCase entry : cases) {
            sender.sendMessage(ChatColor.YELLOW + "- " + entry.type() + ChatColor.GRAY + " key=" + entry.key()
                    + " status=" + (entry.isResolved() ? ChatColor.GREEN + "resolved" : ChatColor.RED + "open"));
            sender.sendMessage(ChatColor.GRAY + "  Accounts: " + ChatColor.WHITE + formatNames(entry.givers()));
            if (!entry.detail().isBlank()) sender.sendMessage(ChatColor.GRAY + "  " + entry.detail());
        }
    }

    private void handleAdminResolve(CommandSender sender, String[] args) {
        if (args.length < 4) { sendAdminHelp(sender); return; }
        OfflinePlayer target = resolveOfflinePlayer(args[2]);
        sender.sendMessage(repService.resolveCase(target.getUniqueId(), args[3])
                ? ChatColor.GREEN + "Resolved matching rep case(s)."
                : ChatColor.RED + "No matching open case found.");
    }

    private void handleAdminReports(CommandSender sender, String[] args) {
        int page = parseInt(args, 2, 1);
        if (sender instanceof Player player) {
            plugin.getRepGuiManager().openActiveReports(player, page - 1);
        } else {
            sendActiveReportsList(sender, page);
        }
    }

    private void handleAdminRemoved(CommandSender sender, String[] args) {
        int page = parseInt(args, 2, 1);
        if (sender instanceof Player player) {
            plugin.getRepGuiManager().openRemovedLog(player, page - 1);
        } else {
            sendRemovedList(sender, page);
        }
    }

    private void handleAdminRestore(CommandSender sender, String[] args) {
        if (args.length < 3) { sendAdminHelp(sender); return; }
        sender.sendMessage(repService.restoreRemoved(args[2], sender)
                ? ChatColor.GREEN + "Restored rep entry " + args[2] + "."
                : ChatColor.RED + "Could not restore entry.");
    }

    private void sendActiveReportsList(CommandSender sender, int page) {
        List<RepService.SuspiciousRepCase> cases = repService.getSuspiciousCases().stream()
                .filter(entry -> !entry.isResolved())
                .sorted(Comparator.comparingLong(RepService.SuspiciousRepCase::getCreatedAt).reversed())
                .toList();
        if (cases.isEmpty()) { sender.sendMessage(ChatColor.GRAY + "No active rep reports."); return; }
        int maxPage = Math.max(1, (cases.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int resolvedPage = Math.max(1, Math.min(page, maxPage));
        sender.sendMessage(ChatColor.GOLD + "=== Active rep reports (" + resolvedPage + "/" + maxPage + ") ===");
        int start = (resolvedPage - 1) * PAGE_SIZE;
        for (int i = start; i < Math.min(start + PAGE_SIZE, cases.size()); i++) {
            RepService.SuspiciousRepCase entry = cases.get(i);
            sender.sendMessage(ChatColor.YELLOW + repService.nameOf(entry.getTarget()) + ChatColor.GRAY + " | "
                    + entry.type() + " | key " + entry.key() + " | " + ChatColor.WHITE + formatNames(entry.givers()));
        }
    }

    private void sendRemovedList(CommandSender sender, int page) {
        List<RepService.RemovedRep> removed = repService.getRemovedLog().stream()
                .sorted(Comparator.comparingLong(RepService.RemovedRep::removedAt).reversed()).toList();
        if (removed.isEmpty()) { sender.sendMessage(ChatColor.GRAY + "No removed rep entries logged."); return; }
        int maxPage = Math.max(1, (removed.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int resolvedPage = Math.max(1, Math.min(page, maxPage));
        sender.sendMessage(ChatColor.GOLD + "=== Removed reps (" + resolvedPage + "/" + maxPage + ") ===");
        int start = (resolvedPage - 1) * PAGE_SIZE;
        for (int i = start; i < Math.min(start + PAGE_SIZE, removed.size()); i++) {
            RepService.RemovedRep entry = removed.get(i);
            Commendation c = entry.commendation();
            sender.sendMessage(ChatColor.YELLOW + entry.id() + ChatColor.GRAY + " | " + coloredValue(c.getScoreValue())
                    + ChatColor.GRAY + " " + repService.nameOf(c.getGiver()) + " -> " + repService.nameOf(c.getTarget())
                    + " [" + displayName(c.getCategory()) + "] "
                    + dateFormatter.format(Instant.ofEpochMilli(entry.removedAt())));
        }
    }

    private void sendAdminHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Rep Admin Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/rep alerts (toggle your suspicious rep-trading alerts)");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin reload");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin get <player>");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin set <player> <score>");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin add <player> <delta>");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin history <player> [page]");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin remove <target> <giver> <category>");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin revoke <target> <giver> (legacy alias)");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin reset <player>");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin inspect <player> [caseKey]");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin resolve <player> <caseKey>");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin reports [page]");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin removed [page]");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin restore <id>");
    }

    private RepCategory parseCategory(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            RepCategory category = RepCategory.valueOf(normalized);
            return category.isSelectable() ? category : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private OfflinePlayer resolveOfflinePlayer(String input) {
        try {
            return Bukkit.getOfflinePlayer(UUID.fromString(input));
        } catch (IllegalArgumentException ignored) {
            return Bukkit.getOfflinePlayer(input);
        }
    }

    private int parseInt(String[] args, int index, int fallback) {
        if (index >= args.length) return fallback;
        try { return Integer.parseInt(args[index]); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private Integer tryParseInt(String raw) {
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException ignored) { return null; }
    }

    private String joinReason(String[] args, int start) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(args[i]);
        }
        return builder.toString();
    }

    private String trimPreview(String reason) {
        if (reason == null) return "";
        return reason.length() <= 80 ? reason : reason.substring(0, 77) + "...";
    }

    private String safeName(OfflinePlayer player) {
        return player.getName() != null ? player.getName() : player.getUniqueId().toString().substring(0, 8);
    }

    private String displayName(RepCategory category) {
        return category == null ? "Reputation" : category.migratedCategory().displayName();
    }

    private String coloredValue(int value) {
        return (value > 0 ? ChatColor.GREEN : value < 0 ? ChatColor.RED : ChatColor.YELLOW)
                + (value > 0 ? "+" + value : String.valueOf(value));
    }

    private String formatNames(Collection<UUID> ids) {
        return ids.stream().map(repService::nameOf).collect(Collectors.joining(", "));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!command.getName().equalsIgnoreCase("rep")) return result;
        if (args.length == 1) {
            addMatches(result, args[0], rootSubcommands(sender));
            addOnlinePlayers(result, args[0]);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("admin") && sender.hasPermission(PERMISSION_ADMIN)) {
            addMatches(result, args[1], ADMIN_SUBCOMMANDS);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            addOnlinePlayers(result, args[1]);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            addMatches(result, args[2], SELECTABLE_CATEGORIES);
        } else if (args.length == 5 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("remove")) {
            addMatches(result, args[4], SELECTABLE_CATEGORIES);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("stalk")) {
            addMatches(result, args[1], List.of("list", "cancel"));
            addOnlinePlayers(result, args[1]);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("stalk") && args[1].equalsIgnoreCase("cancel")) {
            addOnlinePlayers(result, args[2]);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("stalk")) {
            addMatches(result, args[2], List.of("1", "2", "3", "4", "5", "6", "7"));
        }
        return result;
    }

    private void addMatches(List<String> result, String prefix, List<String> values) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).forEach(result::add);
    }

    private void addOnlinePlayers(List<String> result, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) result.add(player.getName());
        }
    }
}
