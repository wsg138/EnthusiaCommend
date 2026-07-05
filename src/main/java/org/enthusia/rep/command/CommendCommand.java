package org.enthusia.rep.command;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;
import org.enthusia.rep.rep.RepService;
import org.enthusia.rep.stalk.StalkSubscription;
import org.enthusia.rep.util.RepDateFormats;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class CommendCommand implements CommandExecutor, TabCompleter {
    private static final String COMMAND_REP = "rep";
    private static final String PERMISSION_ADMIN = "enthusiacommend.rep.admin";
    private static final String PERMISSION_STALK = "enthusiacommend.rep.stalk";
    private static final String SUB_ADMIN = "admin";
    private static final String SUB_TOP = "top";
    private static final String SUB_BOTTOM = "bottom";
    private static final String SUB_REVIEWS = "reviews";
    private static final String SUB_STALK = "stalk";
    private static final String SUB_GIVE = "give";
    private static final String SUB_HELP = "help";
    private static final String SUB_LIST = "list";
    private static final String SUB_CANCEL = "cancel";
    private static final String SUB_RELOAD = "reload";
    private static final String SUB_GET = "get";
    private static final String SUB_SET = "set";
    private static final String SUB_ADD = "add";
    private static final String SUB_REVOKE = "revoke";
    private static final String SUB_RESET = "reset";
    private static final String SUB_INSPECT = "inspect";
    private static final String SUB_RESOLVE = "resolve";
    private static final String SUB_REPORTS = "reports";
    private static final String SUB_REMOVED = "removed";
    private static final String SUB_RESTORE = "restore";
    private static final String SUB_UNDO = "undo";
    private static final String TARGET_PLACEHOLDER = "target";
    private static final String DAYS_PLACEHOLDER = "days";
    private static final String SCORE_SEPARATOR = ": ";
    private static final int ROOT_ARGUMENTS = 1;
    private static final int PLAYER_ARGUMENTS = 2;
    private static final int VALUE_ARGUMENTS = 4;
    private static final int PAGE_SIZE = 10;
    private static final int REVIEW_LIMIT = 10;
    private static final int DEFAULT_LIMIT = 10;
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_STALK_DAYS = 1;
    private static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;
    private static final long MILLIS_PER_HOUR = 1000L * 60L * 60L;
    private static final List<String> PLAYER_ROOT_COMPLETIONS =
            List.of(SUB_TOP, SUB_BOTTOM, SUB_REVIEWS, SUB_STALK);
    private static final List<String> ADMIN_ROOT_COMPLETIONS =
            List.of(SUB_ADMIN, SUB_TOP, SUB_BOTTOM, SUB_REVIEWS, SUB_STALK);
    private static final List<String> ADMIN_SUBCOMMANDS = List.of(
            SUB_RELOAD, SUB_HELP, SUB_GET, SUB_SET, SUB_ADD, SUB_REVOKE, SUB_RESET,
            SUB_INSPECT, SUB_RESOLVE, SUB_REPORTS, SUB_REMOVED, SUB_RESTORE, SUB_UNDO);
    private static final List<String> STALK_SUBCOMMANDS = List.of(SUB_LIST, SUB_CANCEL);
    private static final List<String> STALK_DAY_COMPLETIONS = List.of("1", "2", "3", "4", "5", "6", "7");
    private static final Map<RepCategory, String> CATEGORY_DISPLAY_NAMES = categoryDisplayNames();

    private final CommendPlugin plugin;
    private final RepService repService;
    private final DateTimeFormatter dateFormatter = RepDateFormats.dateTimeMinute();

    public CommendCommand(CommendPlugin plugin, RepService repService) {
        this.plugin = plugin;
        this.repService = repService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase(COMMAND_REP)) {
            return false;
        }

        if (args.length == 0) {
            return openOwnProfile(sender);
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        boolean handled = handleKnownSubcommand(sender, subcommand, args);
        if (handled) {
            return true;
        }

        return handleProfileLookup(sender, args[0]);
    }

    private boolean openOwnProfile(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Usage: /rep <player>");
            return true;
        }
        plugin.getRepGuiManager().openProfile(player, player);
        return true;
    }

    private boolean handleKnownSubcommand(CommandSender sender, String subcommand, String[] args) {
        return switch (subcommand) {
            case SUB_ADMIN -> handleAdminRequest(sender, args);
            case SUB_TOP -> handleLeaderboard(sender, parseInt(args, 1, DEFAULT_LIMIT), false);
            case SUB_BOTTOM -> handleLeaderboard(sender, parseInt(args, 1, DEFAULT_LIMIT), true);
            case SUB_REVIEWS -> handleReviews(sender, args.length >= PLAYER_ARGUMENTS ? args[1] : sender.getName());
            case SUB_STALK -> handleStalk(sender, args);
            case SUB_GIVE -> handleGiveCommand(sender, args);
            default -> false;
        };
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
        if (args.length < VALUE_ARGUMENTS || !(sender instanceof Player player)) {
            return false;
        }
        return handleDirectGive(player, args[1], args[2], joinReason(args, 3));
    }

    private boolean handleProfileLookup(CommandSender sender, String targetName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.GOLD + "Rep for " + ChatColor.YELLOW + targetName + ChatColor.GOLD
                    + SCORE_SEPARATOR + plugin.getRepConfig().formatColoredScore(repService.getScore(target.getUniqueId())));
            return true;
        }
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            sender.sendMessage(ChatColor.RED + "That player has never joined the server.");
            return true;
        }
        plugin.getRepGuiManager().openProfile(player, target);
        return true;
    }

    private boolean handleDirectGive(Player giver, String targetName, String categoryName, String reasonText) {
        OfflinePlayer target = resolveKnownPlayer(giver, targetName);
        RepCategory category = parseCategory(categoryName);
        if (target == null || !validateDirectGive(giver, target, category)) {
            return true;
        }

        RepService.CommendationResult result = repService.addOrUpdateCommendation(
                giver.getUniqueId(), target.getUniqueId(), category.isPositive(), category,
                trimReason(reasonText), giverIpHash(giver));
        if (!result.success()) {
            sendCooldownMessage(giver, result);
            return true;
        }

        sendDirectGiveSuccess(giver, target, result.commendation());
        return true;
    }

    private OfflinePlayer resolveKnownPlayer(CommandSender sender, String targetName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (target.isOnline() || target.hasPlayedBefore()) {
            return target;
        }
        sender.sendMessage(plugin.getMessages().get("rep.not-found", Map.of("name", targetName)));
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
        return hasRequiredPlaytime(giver);
    }

    private void sendInvalidCategory(CommandSender sender) {
        String categories = java.util.Arrays.stream(RepCategory.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
        sender.sendMessage(plugin.getMessages().get("rep.category-invalid", Map.of("list", categories)));
    }

    private boolean hasRequiredPlaytime(Player giver) {
        if (!plugin.getPlaytimeService().isAvailable()) {
            giver.sendMessage(ChatColor.RED + "Active playtime tracking is unavailable. Rep is temporarily disabled.");
            return false;
        }
        double hours = plugin.getPlaytimeService().getActiveHours(giver);
        double minimumHours = plugin.getRepConfig().getMinActivePlaytimeHours();
        if (hours >= minimumHours) {
            return true;
        }
        giver.sendMessage(plugin.getMessages().get("rep.playtime-short", Map.of(
                "hours_required", String.valueOf(minimumHours),
                "hours_have", String.format(Locale.US, "%.1f", hours)
        )));
        return false;
    }

    private String trimReason(String reasonText) {
        String trimmedReason = reasonText == null ? "" : reasonText.trim();
        int maxLength = plugin.getRepConfig().getMaxReasonLength();
        return trimmedReason.length() > maxLength ? trimmedReason.substring(0, maxLength) : trimmedReason;
    }

    private String giverIpHash(Player giver) {
        String address = giver.getAddress() != null && giver.getAddress().getAddress() != null
                ? giver.getAddress().getAddress().getHostAddress()
                : null;
        return repService.hashIp(address);
    }

    private void sendCooldownMessage(Player giver, RepService.CommendationResult result) {
        long hoursLeft = (long) Math.ceil(result.cooldownRemainingMillis() / (double) MILLIS_PER_HOUR);
        giver.sendMessage(plugin.getMessages().get("rep.cooldown", Map.of("hours", String.valueOf(hoursLeft))));
    }

    private void sendDirectGiveSuccess(Player giver, OfflinePlayer target, Commendation commendation) {
        String score = plugin.getRepConfig().formatColoredScore(repService.getScore(target.getUniqueId()));
        giver.sendMessage(plugin.getMessages().get("rep.give-success", Map.of(
                "amount", commendation.isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1",
                TARGET_PLACEHOLDER, safeName(target),
                "category", displayName(commendation.getCategory()),
                COMMAND_REP, score
        )));
        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null) {
            onlineTarget.sendMessage(plugin.getMessages().get("rep.receive", Map.of(
                    "giver", giver.getName(),
                    "amount", commendation.isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1",
                    "category", displayName(commendation.getCategory()),
                    COMMAND_REP, score
            )));
        }
    }

    private boolean handleReviews(CommandSender sender, String targetName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            sender.sendMessage(plugin.getMessages().get("rep.not-found", Map.of("name", targetName)));
            return true;
        }
        List<Commendation> reviews = repService.getCommendationsAbout(target.getUniqueId());
        sender.sendMessage(ChatColor.GOLD + "--- Reviews for " + ChatColor.YELLOW + safeName(target) + ChatColor.GOLD + " ---");
        if (reviews.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No reviews yet.");
            return true;
        }
        reviews.stream()
                .sorted(Comparator.comparingLong(Commendation::getCreatedAt).reversed())
                .limit(10)
                .forEach(entry -> sender.sendMessage(
                        (entry.isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1")
                                + ChatColor.GRAY + " from " + ChatColor.YELLOW + repService.nameOf(entry.getGiver())
                                + ChatColor.GRAY + " [" + displayName(entry.getCategory()) + "]: "
                                + ChatColor.WHITE + trimPreview(entry.getReasonText())
                ));
        return true;
    }

    private boolean handleLeaderboard(CommandSender sender, int limit, boolean lowest) {
        if (sender instanceof Player player) {
            Bukkit.getPluginManager().callEvent(new org.enthusia.rep.events.CommendationLeaderboardViewedEvent(player.getUniqueId()));
        }
        sender.sendMessage(ChatColor.GOLD + (lowest ? "--- Lowest Rep ---" : "--- Top Rep ---"));
        int rank = 1;
        for (Map.Entry<UUID, Integer> entry : repService.top(limit, lowest)) {
            sender.sendMessage(ChatColor.YELLOW + "#" + rank++ + " " + ChatColor.GOLD + repService.nameOf(entry.getKey())
                    + ChatColor.GRAY + " - " + plugin.getRepConfig().formatColoredScore(entry.getValue()));
        }
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
        if (args.length == ROOT_ARGUMENTS) {
            sendStalkUsage(sender);
            return true;
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case SUB_LIST -> handleStalkList(sender, player);
            case SUB_CANCEL -> handleStalkCancel(sender, player, args);
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
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        plugin.getStalkManager().cancelSubscription(player.getUniqueId(), target.getUniqueId());
        sender.sendMessage(plugin.getMessages().get("stalk.cancelled", Map.of(TARGET_PLACEHOLDER, safeName(target))));
        return true;
    }

    private boolean handleStalkPurchase(CommandSender sender, Player player, String[] args) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            sender.sendMessage(plugin.getMessages().get("rep.not-found", Map.of("name", args[1])));
            return true;
        }
        if (!plugin.getStalkManager().isStalkable(target.getUniqueId())) {
            sender.sendMessage(plugin.getMessages().get("stalk.not-stalkable"));
            return true;
        }

        int days = Math.max(DEFAULT_STALK_DAYS,
                Math.min(plugin.getRepConfig().getStalkMaxDays(), parseInt(args, 2, DEFAULT_STALK_DAYS)));
        double cost = plugin.getRepConfig().getStalkCostPerDay() * days;
        if (plugin.getEconomy() == null) {
            sender.sendMessage(plugin.getMessages().get("stalk.no-economy"));
            return true;
        }
        if (plugin.getEconomy().getBalance(player) < cost) {
            sender.sendMessage(plugin.getMessages().get("stalk.not-enough", Map.of(
                    "cost", String.format(Locale.US, "%.2f", cost),
                    DAYS_PLACEHOLDER, String.valueOf(days))));
            return true;
        }
        plugin.getEconomy().withdrawPlayer(player, cost);
        plugin.getStalkManager().addSubscription(player.getUniqueId(), target.getUniqueId(), days * MILLIS_PER_DAY);
        sender.sendMessage(plugin.getMessages().get("stalk.purchased", Map.of(
                TARGET_PLACEHOLDER, safeName(target),
                DAYS_PLACEHOLDER, String.valueOf(days))));
        return true;
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (args.length == ROOT_ARGUMENTS || args[1].equalsIgnoreCase(SUB_HELP)) {
            sendAdminHelp(sender);
            return;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case SUB_RELOAD -> handleAdminReload(sender);
            case SUB_GET -> handleAdminGet(sender, args);
            case SUB_SET -> handleAdminSet(sender, args);
            case SUB_ADD -> handleAdminAdd(sender, args);
            case SUB_REVOKE -> handleAdminRevoke(sender, args);
            case SUB_RESET -> handleAdminReset(sender, args);
            case SUB_INSPECT -> handleAdminInspect(sender, args);
            case SUB_RESOLVE -> handleAdminResolve(sender, args);
            case SUB_REPORTS -> handleAdminReports(sender, args);
            case SUB_REMOVED -> handleAdminRemoved(sender, args);
            case SUB_RESTORE, SUB_UNDO -> handleAdminRestore(sender, args);
            default -> sender.sendMessage(ChatColor.RED + "Unknown admin subcommand. Use /rep admin help.");
        }
    }

    private void handleAdminReload(CommandSender sender) {
        plugin.reloadPluginConfig();
        sender.sendMessage(ChatColor.GREEN + "EnthusiaCommend reloaded.");
    }

    private void handleAdminGet(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendAdminHelp(sender);
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        sender.sendMessage(ChatColor.GOLD + "Rep for " + ChatColor.YELLOW + safeName(target)
                + ChatColor.GOLD + SCORE_SEPARATOR + plugin.getRepConfig().formatColoredScore(repService.getScore(target.getUniqueId())));
    }

    private void handleAdminSet(CommandSender sender, String[] args) {
        handleAdminScoreUpdate(sender, args, "Score", true);
    }

    private void handleAdminAdd(CommandSender sender, String[] args) {
        handleAdminScoreUpdate(sender, args, "Delta", false);
    }

    private void handleAdminScoreUpdate(CommandSender sender, String[] args, String valueName, boolean absolute) {
        if (args.length < VALUE_ARGUMENTS) {
            sendAdminHelp(sender);
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        Integer value = tryParseInt(args[3]);
        if (value == null) {
            sender.sendMessage(ChatColor.RED + valueName + " must be a whole number.");
            return;
        }
        if (absolute) {
            repService.setScoreByStaff(target.getUniqueId(), value, sender);
        } else {
            repService.adjustScoreByStaff(target.getUniqueId(), value, sender);
        }
        String verb = absolute ? "Set rep of " : "Adjusted rep of ";
        sender.sendMessage(ChatColor.GOLD + verb + ChatColor.YELLOW + safeName(target)
                + ChatColor.GOLD + " to " + plugin.getRepConfig().formatColoredScore(repService.getScore(target.getUniqueId())));
    }

    private void handleAdminRevoke(CommandSender sender, String[] args) {
        if (args.length < VALUE_ARGUMENTS) {
            sendAdminHelp(sender);
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        OfflinePlayer giver = Bukkit.getOfflinePlayer(args[3]);
        UUID removerId = sender instanceof Player player ? player.getUniqueId() : null;
        RepService.RemovedRep removed = repService.removeCommendationLogged(
                removerId, giver.getUniqueId(), target.getUniqueId(), false);
        sender.sendMessage(removed != null
                ? plugin.getMessages().get("admin.revoked", Map.of("giver", safeName(giver), TARGET_PLACEHOLDER, safeName(target)))
                : ChatColor.RED + "No commendation from that giver to target.");
    }

    private void handleAdminReset(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendAdminHelp(sender);
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        repService.resetAllByStaff(target.getUniqueId(), sender);
        sender.sendMessage(plugin.getMessages().get("admin.reset", Map.of(TARGET_PLACEHOLDER, safeName(target))));
    }

    private void handleAdminInspect(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendAdminHelp(sender);
            return;
        }
        OfflinePlayer target = resolveOfflinePlayer(args[2]);
        List<RepService.SuspiciousRepCase> cases = filteredCases(target, args);
        sender.sendMessage(ChatColor.GOLD + "Suspicious rep cases for " + ChatColor.YELLOW + repService.nameOf(target.getUniqueId()));
        if (cases.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "None.");
            return;
        }
        for (RepService.SuspiciousRepCase entry : cases) {
            sendCaseSummary(sender, entry);
        }
    }

    private List<RepService.SuspiciousRepCase> filteredCases(OfflinePlayer target, String[] args) {
        List<RepService.SuspiciousRepCase> cases = repService.getCasesForTarget(target.getUniqueId(), true);
        if (args.length < VALUE_ARGUMENTS) {
            return cases;
        }
        String ipFilter = args[3];
        return cases.stream().filter(entry -> entry.ipHash().equalsIgnoreCase(ipFilter)).toList();
    }

    private void sendCaseSummary(CommandSender sender, RepService.SuspiciousRepCase entry) {
        String status = entry.isResolved() ? ChatColor.GREEN + "resolved" : ChatColor.RED + "open";
        sender.sendMessage(ChatColor.YELLOW + "- IP " + entry.ipHash() + ChatColor.GRAY
                + " (" + status + ChatColor.GRAY + ")");
        sender.sendMessage(ChatColor.GRAY + "  Accounts: " + ChatColor.WHITE
                + entry.givers().stream().map(repService::nameOf).collect(Collectors.joining(", ")));
    }

    private void handleAdminResolve(CommandSender sender, String[] args) {
        if (args.length < VALUE_ARGUMENTS) {
            sendAdminHelp(sender);
            return;
        }
        OfflinePlayer target = resolveOfflinePlayer(args[2]);
        boolean resolved = repService.resolveCase(target.getUniqueId(), args[3]);
        sender.sendMessage(resolved
                ? plugin.getMessages().get("admin.resolve", Map.of(TARGET_PLACEHOLDER, repService.nameOf(target.getUniqueId())))
                : ChatColor.RED + "No matching case found.");
    }

    private void handleAdminReports(CommandSender sender, String[] args) {
        int page = parseInt(args, 2, DEFAULT_PAGE);
        if (sender instanceof Player player) {
            plugin.getRepGuiManager().openActiveReports(player, page - 1);
            return;
        }
        sendActiveReportsList(sender, page);
    }

    private void handleAdminRemoved(CommandSender sender, String[] args) {
        int page = parseInt(args, 2, DEFAULT_PAGE);
        if (sender instanceof Player player) {
            plugin.getRepGuiManager().openRemovedLog(player, page - 1);
            return;
        }
        sendRemovedList(sender, page);
    }

    private void handleAdminRestore(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendAdminHelp(sender);
            return;
        }
        sender.sendMessage(repService.restoreRemoved(args[2], sender)
                ? ChatColor.GREEN + "Restored rep entry " + args[2] + "."
                : ChatColor.RED + "Could not restore entry.");
    }

    private void sendActiveReportsList(CommandSender sender, int page) {
        List<RepService.SuspiciousRepCase> cases = repService.getSuspiciousCases().stream()
                .filter(caseData -> !caseData.isResolved())
                .sorted(Comparator.comparingLong(RepService.SuspiciousRepCase::getCreatedAt).reversed())
                .toList();
        if (cases.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No active rep reports.");
            return;
        }
        int maxPage = Math.max(1, (int) Math.ceil(cases.size() / (double) PAGE_SIZE));
        int resolvedPage = Math.max(1, Math.min(page, maxPage));
        sender.sendMessage(ChatColor.GOLD + "=== Active rep reports (" + resolvedPage + "/" + maxPage + ") ===");
        int start = (resolvedPage - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, cases.size());
        for (int i = start; i < end; i++) {
            RepService.SuspiciousRepCase entry = cases.get(i);
            sender.sendMessage(ChatColor.YELLOW + repService.nameOf(entry.getTarget()) + ChatColor.GRAY + " | IP "
                    + ChatColor.YELLOW + entry.ipHash() + ChatColor.GRAY + " | Accounts: "
                    + ChatColor.WHITE + formatNames(entry.givers())
                    + ChatColor.DARK_GRAY + " " + dateFormatter.format(Instant.ofEpochMilli(entry.getCreatedAt())));
        }
    }

    private void sendRemovedList(CommandSender sender, int page) {
        List<RepService.RemovedRep> removed = repService.getRemovedLog().stream()
                .sorted(Comparator.comparingLong(RepService.RemovedRep::removedAt).reversed())
                .toList();
        if (removed.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No removed rep entries logged.");
            return;
        }
        int maxPage = Math.max(1, (int) Math.ceil(removed.size() / (double) PAGE_SIZE));
        int resolvedPage = Math.max(1, Math.min(page, maxPage));
        sender.sendMessage(ChatColor.GOLD + "=== Removed reps (" + resolvedPage + "/" + maxPage + ") ===");
        int start = (resolvedPage - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, removed.size());
        for (int i = start; i < end; i++) {
            RepService.RemovedRep removedRep = removed.get(i);
            Commendation commendation = removedRep.commendation();
            String removedBy = removedRep.removedBy() != null ? repService.nameOf(removedRep.removedBy()) : "unknown";
            sender.sendMessage(ChatColor.YELLOW + removedRep.id() + ChatColor.GRAY + " | "
                    + (commendation.isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1")
                    + ChatColor.GRAY + " " + repService.nameOf(commendation.getGiver())
                    + ChatColor.GRAY + " -> " + ChatColor.WHITE + repService.nameOf(commendation.getTarget())
                    + ChatColor.GRAY + " [" + ChatColor.YELLOW + displayName(commendation.getCategory()) + ChatColor.GRAY + "] "
                    + ChatColor.DARK_GRAY + dateFormatter.format(Instant.ofEpochMilli(removedRep.removedAt()))
                    + ChatColor.GRAY + " by " + ChatColor.WHITE + removedBy);
        }
    }

    private void sendAdminHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Rep Admin Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin reload");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin get <player>");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin set <player> <score>");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin add <player> <delta>");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin revoke <target> <giver>");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin reset <player>");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin inspect <player> [ipHash]");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin resolve <player> <ipHash>");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin reports [page]");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin removed [page]");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin restore <id>");
    }

    private RepCategory parseCategory(String name) {
        String normalized = name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (RepCategory category : RepCategory.values()) {
            if (category.name().equalsIgnoreCase(normalized)) {
                return category;
            }
        }
        return null;
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
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Integer tryParseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
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
        return CATEGORY_DISPLAY_NAMES.get(category);
    }

    private static Map<RepCategory, String> categoryDisplayNames() {
        Map<RepCategory, String> names = new EnumMap<>(RepCategory.class);
        names.put(RepCategory.WAS_KIND, "Was Kind");
        names.put(RepCategory.HELPED_ME, "Helped Me");
        names.put(RepCategory.GAVE_ITEMS, "Gave Items/Money");
        names.put(RepCategory.TRUSTWORTHY, "Trustworthy");
        names.put(RepCategory.GOOD_STALL, "Good Stall");
        names.put(RepCategory.OTHER_POSITIVE, "Other");
        names.put(RepCategory.SCAMMED, "Scammed");
        names.put(RepCategory.SPAWN_KILLED, "Spawn Killed");
        names.put(RepCategory.GRIEFED, "Griefed");
        names.put(RepCategory.TRAPPED, "Trapped");
        names.put(RepCategory.SCAM_STALL, "Scam Stall");
        names.put(RepCategory.OTHER_NEGATIVE, "Other");
        return names;
    }

    private String formatNames(Collection<UUID> ids) {
        List<String> names = new ArrayList<>();
        for (UUID id : ids) names.add(repService.nameOf(id));
        return String.join(", ", names);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!command.getName().equalsIgnoreCase(COMMAND_REP)) {
            return result;
        }
        if (args.length == ROOT_ARGUMENTS) {
            addRootCompletions(sender, result, args[0]);
            return result;
        }
        if (isAdminCompletion(sender, args)) {
            addMatches(result, args[1], ADMIN_SUBCOMMANDS);
            return result;
        }
        if (args[0].equalsIgnoreCase(SUB_STALK)) {
            addStalkCompletions(result, args);
        }
        return result;
    }

    private void addRootCompletions(CommandSender sender, List<String> result, String prefix) {
        addMatches(result, prefix, sender.hasPermission(PERMISSION_ADMIN)
                ? ADMIN_ROOT_COMPLETIONS
                : PLAYER_ROOT_COMPLETIONS);
        addOnlinePlayers(result, prefix);
    }

    private boolean isAdminCompletion(CommandSender sender, String[] args) {
        return args.length == PLAYER_ARGUMENTS
                && args[0].equalsIgnoreCase(SUB_ADMIN)
                && sender.hasPermission(PERMISSION_ADMIN);
    }

    private void addStalkCompletions(List<String> result, String[] args) {
        if (args.length == PLAYER_ARGUMENTS) {
            addMatches(result, args[1], STALK_SUBCOMMANDS);
            addOnlinePlayers(result, args[1]);
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase(SUB_CANCEL)) {
            addOnlinePlayers(result, args[2]);
            return;
        }
        if (args.length == 3) {
            addMatches(result, args[2], STALK_DAY_COMPLETIONS);
        }
    }

    private void addMatches(List<String> result, String prefix, List<String> values) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lowered)) {
                result.add(value);
            }
        }
    }

    private void addOnlinePlayers(List<String> result, String prefix) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(lowered)) {
                result.add(player.getName());
            }
        }
    }
}
