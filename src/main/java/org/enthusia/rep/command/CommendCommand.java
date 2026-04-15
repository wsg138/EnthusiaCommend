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

import java.time.Instant;
import java.time.ZoneId;
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

    private final CommendPlugin plugin;
    private final RepService repService;
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public CommendCommand(CommendPlugin plugin, RepService repService) {
        this.plugin = plugin;
        this.repService = repService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("rep")) {
            return false;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Usage: /rep <player>");
                return true;
            }
            plugin.getRepGuiManager().openProfile(player, player);
            return true;
        }

        if (args[0].equalsIgnoreCase("admin")) {
            if (!sender.hasPermission("enthusiacommend.rep.admin")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use rep admin commands.");
                return true;
            }
            handleAdmin(sender, args);
            return true;
        }

        if (args[0].equalsIgnoreCase("top")) {
            return handleLeaderboard(sender, parseInt(args, 1, 10), false);
        }
        if (args[0].equalsIgnoreCase("bottom")) {
            return handleLeaderboard(sender, parseInt(args, 1, 10), true);
        }
        if (args[0].equalsIgnoreCase("reviews")) {
            return handleReviews(sender, args.length >= 2 ? args[1] : sender.getName());
        }
        if (args[0].equalsIgnoreCase("stalk")) {
            return handleStalk(sender, args);
        }
        if (args[0].equalsIgnoreCase("give") && args.length >= 4 && sender instanceof Player player) {
            return handleDirectGive(player, args[1], args[2], joinReason(args, 3));
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.GOLD + "Rep for " + ChatColor.YELLOW + args[0] + ChatColor.GOLD + ": "
                    + plugin.getRepConfig().formatColoredScore(repService.getScore(target.getUniqueId())));
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
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            giver.sendMessage(plugin.getMessages().get("rep.not-found", Map.of("name", targetName)));
            return true;
        }

        RepCategory category = parseCategory(categoryName);
        if (category == null) {
            giver.sendMessage(plugin.getMessages().get("rep.category-invalid", Map.of(
                    "list", java.util.Arrays.stream(RepCategory.values()).map(Enum::name).collect(Collectors.joining(", "))
            )));
            return true;
        }

        if (giver.getUniqueId().equals(target.getUniqueId())) {
            giver.sendMessage(plugin.getMessages().get("rep.self"));
            return true;
        }
        if (!plugin.getPlaytimeService().isAvailable()) {
            giver.sendMessage(ChatColor.RED + "Active playtime tracking is unavailable. Rep is temporarily disabled.");
            return true;
        }
        double hours = plugin.getPlaytimeService().getActiveHours(giver);
        if (hours < plugin.getRepConfig().getMinActivePlaytimeHours()) {
            giver.sendMessage(plugin.getMessages().get("rep.playtime-short", Map.of(
                    "hours_required", String.valueOf(plugin.getRepConfig().getMinActivePlaytimeHours()),
                    "hours_have", String.format(Locale.US, "%.1f", hours)
            )));
            return true;
        }

        String trimmedReason = reasonText == null ? "" : reasonText.trim();
        if (trimmedReason.length() > plugin.getRepConfig().getMaxReasonLength()) {
            trimmedReason = trimmedReason.substring(0, plugin.getRepConfig().getMaxReasonLength());
        }
        String ipHash = repService.hashIp(giver.getAddress() != null && giver.getAddress().getAddress() != null
                ? giver.getAddress().getAddress().getHostAddress()
                : null);

        RepService.CommendationResult result = repService.addOrUpdateCommendation(
                giver.getUniqueId(), target.getUniqueId(), category.isPositive(), category, trimmedReason, ipHash);

        if (!result.success()) {
            long hoursLeft = (long) Math.ceil(result.cooldownRemainingMillis() / 1000.0D / 3600.0D);
            giver.sendMessage(plugin.getMessages().get("rep.cooldown", Map.of("hours", String.valueOf(hoursLeft))));
            return true;
        }

        Commendation commendation = result.commendation();
        String score = plugin.getRepConfig().formatColoredScore(repService.getScore(target.getUniqueId()));
        giver.sendMessage(plugin.getMessages().get("rep.give-success", Map.of(
                "amount", commendation.isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1",
                "target", safeName(target),
                "category", displayName(commendation.getCategory()),
                "rep", score
        )));
        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null) {
            onlineTarget.sendMessage(plugin.getMessages().get("rep.receive", Map.of(
                    "giver", giver.getName(),
                    "amount", commendation.isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1",
                    "category", displayName(commendation.getCategory()),
                    "rep", score
            )));
        }
        return true;
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
        if (!player.hasPermission("enthusiacommend.rep.stalk")) {
            player.sendMessage(plugin.getMessages().get("rep.no-permission"));
            return true;
        }
        if (args.length == 1) {
            sender.sendMessage(ChatColor.GOLD + "/rep stalk <player> [days]");
            sender.sendMessage(ChatColor.GOLD + "/rep stalk list");
            sender.sendMessage(ChatColor.GOLD + "/rep stalk cancel <player>");
            return true;
        }
        if (args[1].equalsIgnoreCase("list")) {
            List<StalkSubscription> subscriptions = plugin.getStalkManager().getSubscriptionsByStalker(player.getUniqueId());
            if (subscriptions.isEmpty()) {
                sender.sendMessage(plugin.getMessages().get("stalk.list-empty"));
                return true;
            }
            sender.sendMessage(ChatColor.GOLD + "Active stalks:");
            for (StalkSubscription subscription : subscriptions) {
                long hours = Math.max(0L, (subscription.expiresAt() - System.currentTimeMillis()) / 1000L / 3600L);
                sender.sendMessage(ChatColor.YELLOW + repService.nameOf(subscription.target()) + ChatColor.GRAY + " -> " + hours + "h remaining");
            }
            return true;
        }
        if (args[1].equalsIgnoreCase("cancel") && args.length >= 3) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            plugin.getStalkManager().cancelSubscription(player.getUniqueId(), target.getUniqueId());
            sender.sendMessage(plugin.getMessages().get("stalk.cancelled", Map.of("target", safeName(target))));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            sender.sendMessage(plugin.getMessages().get("rep.not-found", Map.of("name", args[1])));
            return true;
        }
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
            sender.sendMessage(plugin.getMessages().get("stalk.not-enough", Map.of("cost", String.format(Locale.US, "%.2f", cost), "days", String.valueOf(days))));
            return true;
        }
        plugin.getEconomy().withdrawPlayer(player, cost);
        plugin.getStalkManager().addSubscription(player.getUniqueId(), target.getUniqueId(), days * 24L * 60L * 60L * 1000L);
        sender.sendMessage(plugin.getMessages().get("stalk.purchased", Map.of("target", safeName(target), "days", String.valueOf(days))));
        return true;
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (args.length == 1 || args[1].equalsIgnoreCase("help")) {
            sendAdminHelp(sender);
            return;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            plugin.reloadPluginConfig();
            sender.sendMessage(ChatColor.GREEN + "EnthusiaCommend reloaded.");
            return;
        }
        if (sub.equals("get") && args.length >= 3) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            sender.sendMessage(ChatColor.GOLD + "Rep for " + ChatColor.YELLOW + safeName(target)
                    + ChatColor.GOLD + ": " + plugin.getRepConfig().formatColoredScore(repService.getScore(target.getUniqueId())));
            return;
        }
        if (sub.equals("set") && args.length >= 4) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            Integer value = tryParseInt(args[3]);
            if (value == null) {
                sender.sendMessage(ChatColor.RED + "Score must be a whole number.");
                return;
            }
            repService.setScore(target.getUniqueId(), value);
            sender.sendMessage(ChatColor.GOLD + "Set rep of " + ChatColor.YELLOW + safeName(target)
                    + ChatColor.GOLD + " to " + plugin.getRepConfig().formatColoredScore(repService.getScore(target.getUniqueId())));
            return;
        }
        if (sub.equals("add") && args.length >= 4) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            Integer value = tryParseInt(args[3]);
            if (value == null) {
                sender.sendMessage(ChatColor.RED + "Delta must be a whole number.");
                return;
            }
            repService.adjustScore(target.getUniqueId(), value);
            sender.sendMessage(ChatColor.GOLD + "Adjusted rep of " + ChatColor.YELLOW + safeName(target)
                    + ChatColor.GOLD + " to " + plugin.getRepConfig().formatColoredScore(repService.getScore(target.getUniqueId())));
            return;
        }
        if (sub.equals("revoke") && args.length >= 4) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            OfflinePlayer giver = Bukkit.getOfflinePlayer(args[3]);
            UUID removerId = sender instanceof Player player ? player.getUniqueId() : null;
            RepService.RemovedRep removed = repService.removeCommendationLogged(removerId, giver.getUniqueId(), target.getUniqueId(), false);
            sender.sendMessage(removed != null
                    ? plugin.getMessages().get("admin.revoked", Map.of("giver", safeName(giver), "target", safeName(target)))
                    : ChatColor.RED + "No commendation from that giver to target.");
            return;
        }
        if (sub.equals("reset") && args.length >= 3) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            repService.resetAll(target.getUniqueId());
            sender.sendMessage(plugin.getMessages().get("admin.reset", Map.of("target", safeName(target))));
            return;
        }
        if (sub.equals("inspect") && args.length >= 3) {
            OfflinePlayer target = resolveOfflinePlayer(args[2]);
            String ipFilter = args.length >= 4 ? args[3] : null;
            List<RepService.SuspiciousRepCase> cases = repService.getCasesForTarget(target.getUniqueId(), true);
            if (ipFilter != null) {
                cases = cases.stream().filter(entry -> entry.ipHash().equalsIgnoreCase(ipFilter)).toList();
            }
            sender.sendMessage(ChatColor.GOLD + "Suspicious rep cases for " + ChatColor.YELLOW + repService.nameOf(target.getUniqueId()));
            if (cases.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "None.");
                return;
            }
            for (RepService.SuspiciousRepCase entry : cases) {
                String status = entry.isResolved() ? ChatColor.GREEN + "resolved" : ChatColor.RED + "open";
                sender.sendMessage(ChatColor.YELLOW + "- IP " + entry.ipHash() + ChatColor.GRAY + " (" + status + ChatColor.GRAY + ")");
                sender.sendMessage(ChatColor.GRAY + "  Accounts: " + ChatColor.WHITE + entry.givers().stream().map(repService::nameOf).collect(Collectors.joining(", ")));
            }
            return;
        }
        if (sub.equals("resolve") && args.length >= 4) {
            OfflinePlayer target = resolveOfflinePlayer(args[2]);
            boolean resolved = repService.resolveCase(target.getUniqueId(), args[3]);
            sender.sendMessage(resolved
                    ? plugin.getMessages().get("admin.resolve", Map.of("target", repService.nameOf(target.getUniqueId())))
                    : ChatColor.RED + "No matching case found.");
            return;
        }
        if (sub.equals("reports")) {
            int page = parseInt(args, 2, 1);
            if (sender instanceof Player player) {
                plugin.getRepGuiManager().openActiveReports(player, page - 1);
            } else {
                sendActiveReportsList(sender, page);
            }
            return;
        }
        if (sub.equals("removed")) {
            int page = parseInt(args, 2, 1);
            if (sender instanceof Player player) {
                plugin.getRepGuiManager().openRemovedLog(player, page - 1);
            } else {
                sendRemovedList(sender, page);
            }
            return;
        }
        if ((sub.equals("restore") || sub.equals("undo")) && args.length >= 3) {
            sender.sendMessage(repService.restoreRemoved(args[2])
                    ? ChatColor.GREEN + "Restored rep entry " + args[2] + "."
                    : ChatColor.RED + "Could not restore entry.");
            return;
        }

        sender.sendMessage(ChatColor.RED + "Unknown admin subcommand. Use /rep admin help.");
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
        int pageSize = 10;
        int maxPage = Math.max(1, (int) Math.ceil(cases.size() / (double) pageSize));
        int resolvedPage = Math.max(1, Math.min(page, maxPage));
        sender.sendMessage(ChatColor.GOLD + "=== Active rep reports (" + resolvedPage + "/" + maxPage + ") ===");
        int start = (resolvedPage - 1) * pageSize;
        int end = Math.min(start + pageSize, cases.size());
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
        int pageSize = 10;
        int maxPage = Math.max(1, (int) Math.ceil(removed.size() / (double) pageSize));
        int resolvedPage = Math.max(1, Math.min(page, maxPage));
        sender.sendMessage(ChatColor.GOLD + "=== Removed reps (" + resolvedPage + "/" + maxPage + ") ===");
        int start = (resolvedPage - 1) * pageSize;
        int end = Math.min(start + pageSize, removed.size());
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

    private String formatNames(Collection<UUID> ids) {
        List<String> names = new ArrayList<>();
        for (UUID id : ids) names.add(repService.nameOf(id));
        return String.join(", ", names);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!command.getName().equalsIgnoreCase("rep")) {
            return result;
        }
        if (args.length == 1) {
            addMatches(result, args[0], sender.hasPermission("enthusiacommend.rep.admin") ? List.of("admin", "top", "bottom", "reviews", "stalk") : List.of("top", "bottom", "reviews", "stalk"));
            addOnlinePlayers(result, args[0]);
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin") && sender.hasPermission("enthusiacommend.rep.admin")) {
            addMatches(result, args[1], List.of("reload", "help", "get", "set", "add", "revoke", "reset", "inspect", "resolve", "reports", "removed", "restore", "undo"));
            return result;
        }
        if (args[0].equalsIgnoreCase("stalk")) {
            if (args.length == 2) {
                addMatches(result, args[1], List.of("list", "cancel"));
                addOnlinePlayers(result, args[1]);
            } else if (args.length == 3 && args[1].equalsIgnoreCase("cancel")) {
                addOnlinePlayers(result, args[2]);
            } else if (args.length == 3) {
                addMatches(result, args[2], List.of("1", "2", "3", "4", "5", "6", "7"));
            }
            return result;
        }
        return result;
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
