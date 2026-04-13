package org.enthusia.rep.command;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.playtime.PlaytimeService;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;
import org.enthusia.rep.rep.RepService.RemovedRep;
import org.enthusia.rep.stalk.StalkSubscription;
import org.enthusia.rep.rep.RepService;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

public class CommendCommand implements CommandExecutor, TabCompleter {

    private final CommendPlugin plugin;
    private final RepService repService;
    private final PlaytimeService playtime;
    private final DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public CommendCommand(CommendPlugin plugin, RepService repService) {
        this.plugin = plugin;
        this.repService = repService;
        this.playtime = plugin.getPlaytimeService();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!name.equals("rep")) return false;

        // /rep admin ...
        if (args.length >= 1 && args[0].equalsIgnoreCase("admin")) {
            if (!sender.hasPermission("enthusiacommend.admin")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use rep admin commands.");
                return true;
            }
            handleAdmin(sender, args);
            return true;
        }

        // /rep  (self)
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Usage: /rep <player>");
                return true;
            }
            plugin.getRepGuiManager().openProfile(player, player);
            return true;
        }

        // /rep give <player> <category> <reason...>
        if (args.length >= 3 && args[0].equalsIgnoreCase("give")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Players only.");
                return true;
            }
            return handleGive(player, args[1], args[2], joinReason(args, 3));
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("reviews")) {
            String targetName = (args.length >= 2) ? args[1] : sender.getName();
            return handleReviews(sender, targetName);
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("top")) {
            int limit = 10;
            if (args.length >= 2) {
                try { limit = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
            }
            return handleLeaderboard(sender, limit, false);
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("bottom")) {
            int limit = 10;
            if (args.length >= 2) {
                try { limit = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
            }
            return handleLeaderboard(sender, limit, true);
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("stalk")) {
            return handleStalk(sender, args);
        }

        // /rep <player>
        String targetName = args[0];
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

        if (!(sender instanceof Player player)) {
            int score = repService.getScore(target.getUniqueId());
            ChatColor color = score > 0 ? ChatColor.GREEN : (score < 0 ? ChatColor.RED : ChatColor.YELLOW);
            sender.sendMessage(ChatColor.GOLD + "Rep for " + ChatColor.YELLOW + targetName + ChatColor.GOLD + ": " + color + score);
            return true;
        }

        if (!target.isOnline() && !target.hasPlayedBefore()) {
            sender.sendMessage(ChatColor.RED + "That player has never joined the server.");
            return true;
        }

        plugin.getRepGuiManager().openProfile((Player) sender, target);
        return true;
    }

    private boolean handleGive(Player giver, String targetName, String categoryName, String reasonText) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (target == null || target.getUniqueId() == null) {
            giver.sendMessage(ChatColor.RED + "Unknown player: " + targetName);
            return true;
        }
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            giver.sendMessage(ChatColor.RED + "That player has never joined the server.");
            return true;
        }
        if (giver.getUniqueId().equals(target.getUniqueId())) {
            giver.sendMessage(ChatColor.RED + "You cannot give rep to yourself.");
            return true;
        }

        double hours = playtime.getActiveHours(giver);
        if (hours < plugin.getRepConfig().getMinActivePlaytimeHours()) {
            giver.sendMessage(ChatColor.RED + "You need at least " + plugin.getRepConfig().getMinActivePlaytimeHours()
                    + " hours of active playtime to leave reputation. (You have " + String.format(Locale.US, "%.1f", hours) + "h)");
            return true;
        }

        RepCategory category = parseCategory(categoryName);
        if (category == null) {
            giver.sendMessage(ChatColor.RED + "Unknown category. Valid: " + Arrays.stream(RepCategory.values())
                    .map(Enum::name).collect(Collectors.joining(", ")));
            return true;
        }

        String ipHash = repService.hashIp(giver.getAddress() != null ? giver.getAddress().getAddress().getHostAddress() : null);
        String trimmedReason = reasonText == null ? "" : reasonText.trim();
        if (trimmedReason.length() > 256) {
            trimmedReason = trimmedReason.substring(0, 256);
        }

        RepService.CommendationResult result = repService.addOrUpdateCommendation(
                giver.getUniqueId(),
                target.getUniqueId(),
                category.isPositive(),
                category,
                trimmedReason,
                ipHash
        );

        if (!result.success()) {
            long remaining = result.cooldownRemainingMillis();
            long hoursLeft = (long) Math.ceil(remaining / 1000.0 / 3600.0);
            giver.sendMessage(ChatColor.RED + "You can edit your commendation for this player again in " + hoursLeft + "h.");
            return true;
        }

        Commendation c = result.commendation();
        String colored = plugin.getRepConfig().formatColoredScore(repService.getScore(target.getUniqueId()));

        giver.sendMessage(ChatColor.GOLD + "You gave "
                + (c.isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1")
                + ChatColor.GOLD + " rep to " + ChatColor.YELLOW + target.getName()
                + ChatColor.GOLD + " for " + ChatColor.YELLOW + c.getCategory().name()
                + ChatColor.GOLD + ". New rep: " + colored);

        if (target.isOnline()) {
            Player p = target.getPlayer();
            if (p != null) {
                p.sendMessage(ChatColor.YELLOW + giver.getName()
                        + ChatColor.GOLD + " gave you "
                        + (c.isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1")
                        + ChatColor.GOLD + " rep for " + ChatColor.YELLOW + c.getCategory().name()
                        + ChatColor.GOLD + ". New total: " + colored);
            }
        }
        return true;
    }

    private boolean handleReviews(CommandSender sender, String targetName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (target == null || target.getUniqueId() == null) {
            sender.sendMessage(ChatColor.RED + "Unknown player: " + targetName);
            return true;
        }

        List<Commendation> reviews = repService.getCommendationsAbout(target.getUniqueId());
        sender.sendMessage(ChatColor.GOLD + "--- Reviews for " + ChatColor.YELLOW + target.getName() + ChatColor.GOLD + " ---");
        if (reviews.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No reviews yet.");
            return true;
        }
        reviews.stream()
                .sorted(Comparator.comparingLong(Commendation::getCreatedAt).reversed())
                .limit(10)
                .forEach(c -> sender.sendMessage((c.isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1")
                        + ChatColor.GRAY + " from " + ChatColor.YELLOW + repService.nameOf(c.getGiver())
                        + ChatColor.GRAY + " [" + c.getCategory().name() + "]: "
                        + ChatColor.WHITE + trimPreview(c.getReasonText())));
        return true;
    }

    private boolean handleLeaderboard(CommandSender sender, int limit, boolean lowest) {
        sender.sendMessage(ChatColor.GOLD + (lowest ? "--- Lowest Rep ---" : "--- Top Rep ---"));
        int rank = 1;
        for (var entry : repService.top(limit, lowest)) {
            String name = repService.nameOf(entry.getKey());
            String score = plugin.getRepConfig().formatColoredScore(entry.getValue());
            sender.sendMessage(ChatColor.YELLOW + "#" + rank + " " + ChatColor.GOLD + name
                    + ChatColor.GRAY + " - " + score);
            rank++;
        }
        return true;
    }

    private boolean handleStalk(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }
        if (args.length == 1) {
            sender.sendMessage(ChatColor.GOLD + "/rep stalk <player> [days]");
            sender.sendMessage(ChatColor.GOLD + "/rep stalk list");
            sender.sendMessage(ChatColor.GOLD + "/rep stalk cancel <player>");
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            sender.sendMessage(ChatColor.GOLD + "Active stalks:");
            plugin.getStalkManager().getSubscriptionsByStalker(player.getUniqueId()).forEach(sub -> {
                long remainingMs = sub.expiresAt() - System.currentTimeMillis();
                long hours = Math.max(0, remainingMs / 1000 / 3600);
                sender.sendMessage(ChatColor.YELLOW + repService.nameOf(sub.target()) + ChatColor.GRAY
                        + " -> " + hours + "h remaining");
            });
            return true;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("cancel")) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            plugin.getStalkManager().cancelSubscription(player.getUniqueId(), target.getUniqueId());
            sender.sendMessage(ChatColor.GOLD + "Cancelled stalk on " + ChatColor.YELLOW + target.getName());
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (target == null || target.getUniqueId() == null) {
            sender.sendMessage(ChatColor.RED + "Unknown player: " + args[1]);
            return true;
        }
        if (!plugin.getStalkManager().isStalkable(target.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "That player is not stalkable (needs rep <= -12).");
            return true;
        }
        int days = 1;
        if (args.length >= 3) {
            try { days = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) {}
        }
        if (days < 1) days = 1;
        if (days > 7) days = 7;

        double cost = plugin.getRepConfig().getStalkCost() * days;
        if (plugin.getEconomy() == null) {
            sender.sendMessage(ChatColor.RED + "Economy not set up; cannot purchase stalk.");
            return true;
        }
        if (plugin.getEconomy().getBalance(player) < cost) {
            sender.sendMessage(ChatColor.RED + "You need $" + cost + " to stalk for " + days + " day(s).");
            return true;
        }
        plugin.getEconomy().withdrawPlayer(player, cost);
        long duration = days * 24L * 60L * 60L * 1000L;
        plugin.getStalkManager().addSubscription(player.getUniqueId(), target.getUniqueId(), duration);
        sender.sendMessage(ChatColor.GOLD + "You are now stalking " + ChatColor.YELLOW + target.getName()
                + ChatColor.GOLD + " for " + days + " day(s).");
        return true;
    }

    private RepCategory parseCategory(String name) {
        String normalized = name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (RepCategory cat : RepCategory.values()) {
            if (cat.name().equalsIgnoreCase(normalized)) return cat;
        }
        return null;
    }

    private String joinReason(String[] args, int start) {
        if (start >= args.length) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private String trimPreview(String reason) {
        if (reason == null) return "";
        if (reason.length() <= 80) return reason;
        return reason.substring(0, 77) + "...";
    }

    private OfflinePlayer resolveOfflinePlayer(String input) {
        try {
            return Bukkit.getOfflinePlayer(UUID.fromString(input));
        } catch (IllegalArgumentException ignored) {
        }
        return Bukkit.getOfflinePlayer(input);
    }

    private String formatNames(Collection<UUID> ids) {
        List<String> names = new ArrayList<>();
        for (UUID id : ids) {
            names.add(repService.nameOf(id));
        }
        return String.join(", ", names);
    }

    private void sendActiveReportsList(CommandSender sender, int page) {
        List<RepService.SuspiciousRepCase> cases = repService.getSuspiciousCases().stream()
                .filter(c -> !c.isResolved())
                .sorted(Comparator.comparingLong(RepService.SuspiciousRepCase::getCreatedAt).reversed())
                .toList();
        if (cases.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No active rep reports.");
            return;
        }
        int pageSize = 10;
        int maxPage = Math.max(1, (int) Math.ceil(cases.size() / (double) pageSize));
        if (page < 1) page = 1;
        if (page > maxPage) page = maxPage;
        sender.sendMessage(ChatColor.GOLD + "=== Active rep reports (" + page + "/" + maxPage + ") ===");
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, cases.size());
        for (int i = start; i < end; i++) {
            RepService.SuspiciousRepCase c = cases.get(i);
            sender.sendMessage(ChatColor.YELLOW + repService.nameOf(c.getTarget()) + ChatColor.GRAY + " | IP "
                    + ChatColor.YELLOW + c.ipHash() + ChatColor.GRAY + " | Accounts: "
                    + ChatColor.WHITE + formatNames(c.givers())
                    + ChatColor.DARK_GRAY + " " + dateFmt.format(Instant.ofEpochMilli(c.getCreatedAt())));
        }
    }

    private void sendRemovedList(CommandSender sender, int page) {
        List<RemovedRep> removed = repService.getRemovedLog().stream()
                .sorted(Comparator.comparingLong(RemovedRep::removedAt).reversed())
                .toList();
        if (removed.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No removed rep entries logged.");
            return;
        }
        int pageSize = 10;
        int maxPage = Math.max(1, (int) Math.ceil(removed.size() / (double) pageSize));
        if (page < 1) page = 1;
        if (page > maxPage) page = maxPage;
        sender.sendMessage(ChatColor.GOLD + "=== Removed reps (" + page + "/" + maxPage + ") ===");
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, removed.size());
        for (int i = start; i < end; i++) {
            RemovedRep r = removed.get(i);
            Commendation c = r.commendation();
            String value = c.isPositive() ? ChatColor.GREEN + "+1" : ChatColor.RED + "-1";
            String removedBy = r.removedBy() != null ? repService.nameOf(r.removedBy()) : "unknown";
            sender.sendMessage(ChatColor.YELLOW + r.id() + ChatColor.GRAY + " | "
                    + value + ChatColor.GRAY + " " + repService.nameOf(c.getGiver())
                    + ChatColor.GRAY + " -> " + ChatColor.WHITE + repService.nameOf(c.getTarget())
                    + ChatColor.GRAY + " [" + ChatColor.YELLOW + c.getCategory().name() + ChatColor.GRAY + "] "
                    + ChatColor.DARK_GRAY + dateFmt.format(Instant.ofEpochMilli(r.removedAt()))
                    + ChatColor.GRAY + " by " + ChatColor.WHITE + removedBy);
        }
        sender.sendMessage(ChatColor.GRAY + "Use /rep admin restore <id> to undo.");
    }

    /* ===================== ADMIN SUBCOMMANDS ===================== */

    private void handleAdmin(CommandSender sender, String[] args) {
        // /rep admin
        if (args.length == 1) {
            sendAdminHelp(sender);
            return;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);

        // /rep admin help
        if (sub.equals("help")) {
            sendAdminHelp(sender);
            return;
        }

        // /rep admin get <player>
        if (sub.equals("get")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /rep admin get <player>");
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            int score = repService.getScore(target.getUniqueId());
            ChatColor color = score > 0 ? ChatColor.GREEN : (score < 0 ? ChatColor.RED : ChatColor.YELLOW);
            sender.sendMessage(ChatColor.GOLD + "Rep for " + ChatColor.YELLOW + target.getName()
                    + ChatColor.GOLD + ": " + color + score);
            return;
        }

        // /rep admin set <player> <score>
        if (sub.equals("set")) {
            if (args.length < 4) {
                sender.sendMessage(ChatColor.RED + "Usage: /rep admin set <player> <score>");
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            int value;
            try {
                value = Integer.parseInt(args[3]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(ChatColor.RED + "Score must be a whole number.");
                return;
            }

            repService.setScore(target.getUniqueId(), value);
            sender.sendMessage(ChatColor.GOLD + "Set rep of " + ChatColor.YELLOW + target.getName()
                    + ChatColor.GOLD + " to " + formatRep(value) + ChatColor.GOLD + ".");
            if (target.isOnline()) {
                Player p = target.getPlayer();
                if (p != null) {
                    p.sendMessage(ChatColor.YELLOW + "[Rep] " + ChatColor.GRAY +
                            "Your reputation was adjusted by staff. New total: " + formatRep(value));
                }
            }
            return;
        }

        // /rep admin add <player> <delta>
        if (sub.equals("add")) {
            if (args.length < 4) {
                sender.sendMessage(ChatColor.RED + "Usage: /rep admin add <player> <delta>");
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            int delta;
            try {
                delta = Integer.parseInt(args[3]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(ChatColor.RED + "Delta must be a whole number.");
                return;
            }
            repService.adjustScore(target.getUniqueId(), delta);
            sender.sendMessage(ChatColor.GOLD + "Adjusted rep of " + ChatColor.YELLOW + target.getName()
                    + ChatColor.GOLD + " by " + delta + ". New total: "
                    + repService.getScore(target.getUniqueId()));
            return;
        }

        // /rep admin revoke <target> <giver>
        if (sub.equals("revoke")) {
            if (args.length < 4) {
                sender.sendMessage(ChatColor.RED + "Usage: /rep admin revoke <target> <giver>");
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            OfflinePlayer giver = Bukkit.getOfflinePlayer(args[3]);
            Commendation existing = repService.getCommendation(giver.getUniqueId(), target.getUniqueId());
            if (existing == null) {
                sender.sendMessage(ChatColor.RED + "No commendation from that giver to target.");
                return;
            }
            UUID remover = sender instanceof Player p ? p.getUniqueId() : null;
            repService.removeCommendationLogged(remover, giver.getUniqueId(), target.getUniqueId(), false);
            sender.sendMessage(ChatColor.GOLD + "Removed commendation from " + giver.getName()
                    + " to " + target.getName() + " (logged).");
            return;
        }

        // /rep admin reset <player>
        if (sub.equals("reset")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /rep admin reset <player>");
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            repService.resetAll(target.getUniqueId());
            sender.sendMessage(ChatColor.GOLD + "Cleared all commendations for " + target.getName() + ".");
            return;
        }

        if (sub.equals("removeentry")) {
            sender.sendMessage(ChatColor.YELLOW + "[Rep] " + ChatColor.GRAY +
                    "Per-entry removal is not wired yet. Once the story-based system is in, " +
                    "this will let you remove individual rep entries from a player.");
            return;
        }

        // /rep admin inspect <player>
        if (sub.equals("inspect")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /rep admin inspect <player> [ipHash]");
                return;
            }
            OfflinePlayer target = resolveOfflinePlayer(args[2]);
            String ipFilter = args.length >= 4 ? args[3] : null;
            List<RepService.SuspiciousRepCase> cases = repService.getCasesForTarget(target.getUniqueId(), true);
            if (ipFilter != null) {
                cases = cases.stream()
                        .filter(c -> c.ipHash().equalsIgnoreCase(ipFilter))
                        .toList();
            }
            sender.sendMessage(ChatColor.GOLD + "Suspicious rep cases for " + ChatColor.YELLOW + repService.nameOf(target.getUniqueId()));
            if (cases.isEmpty()) {
                sender.sendMessage(ipFilter != null ? ChatColor.GRAY + "No matching case found." : ChatColor.GRAY + "None.");
                return;
            }
            for (var c : cases) {
                String status = c.isResolved() ? ChatColor.GREEN + "resolved" : ChatColor.RED + "open";
                sender.sendMessage(ChatColor.YELLOW + "- IP " + c.ipHash() + ChatColor.GRAY + " (" + status + ChatColor.GRAY + ")");
                sender.sendMessage(ChatColor.GRAY + "  Accounts: " + ChatColor.WHITE +
                        c.givers().stream().map(repService::nameOf).collect(Collectors.joining(", ")));
            }
            return;
        }

        // /rep admin resolve <player> <ipHash>
        if (sub.equals("resolve")) {
            if (args.length < 4) {
                sender.sendMessage(ChatColor.RED + "Usage: /rep admin resolve <player> <ipHash>");
                return;
            }
            OfflinePlayer target = resolveOfflinePlayer(args[2]);
            String ip = args[3];
            boolean ok = repService.resolveCase(target.getUniqueId(), ip);
            if (ok) {
                sender.sendMessage(ChatColor.GOLD + "Marked alt case for " + ChatColor.YELLOW + repService.nameOf(target.getUniqueId())
                        + ChatColor.GOLD + " (" + ip + ") as resolved.");
            } else {
                sender.sendMessage(ChatColor.RED + "No matching case found.");
            }
            return;
        }

        // /rep admin reports [page] (GUI if player)
        if (sub.equals("reports")) {
            int page = 1;
            if (args.length >= 3) {
                try {
                    page = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {
                }
            }
            if (sender instanceof Player p) {
                plugin.getRepGuiManager().openActiveReports(p, page - 1);
            } else {
                sendActiveReportsList(sender, page);
            }
            return;
        }

        // /rep admin removed [page] (GUI if player)
        if (sub.equals("removed")) {
            int page = 1;
            if (args.length >= 3) {
                try {
                    page = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {
                }
            }
            if (sender instanceof Player p) {
                plugin.getRepGuiManager().openRemovedLog(p, page - 1);
            } else {
                sendRemovedList(sender, page);
            }
            return;
        }

        // /rep admin restore <id>
        if (sub.equals("restore") || sub.equals("undo")) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /rep admin restore <id>");
                return;
            }
            String id = args[2];
            boolean ok = repService.restoreRemoved(id);
            if (ok) {
                sender.sendMessage(ChatColor.GREEN + "Restored rep entry " + id + " back to live data.");
            } else {
                sender.sendMessage(ChatColor.RED + "Could not restore entry (id not found or rep already exists).");
            }
            return;
        }

        sender.sendMessage(ChatColor.RED + "Unknown admin subcommand. Use /rep admin help.");
    }

    private void sendAdminHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Rep Admin Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin get <player> " + ChatColor.GRAY + "- view a player's total rep");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin set <player> <score> " + ChatColor.GRAY + "- set a player's total rep");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin add <player> <delta> " + ChatColor.GRAY + "- adjust rep by delta");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin revoke <target> <giver> " + ChatColor.GRAY + "- remove a specific commendation");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin reset <player> " + ChatColor.GRAY + "- clear all commendations for player");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin inspect <player> [ipHash] " + ChatColor.GRAY + "- view alt rep alerts");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin resolve <player> <ipHash> " + ChatColor.GRAY + "- mark alert as resolved");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin reports [page] " + ChatColor.GRAY + "- view active rep reports (GUI)");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin removed [page] " + ChatColor.GRAY + "- view removed reps log (GUI)");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin restore <id> " + ChatColor.GRAY + "- undo a removal");
        sender.sendMessage(ChatColor.YELLOW + "/rep admin removeentry <player> <id> " + ChatColor.GRAY + "- remove a specific rep story");
    }

    private String formatRep(int score) {
        ChatColor color = score > 0 ? ChatColor.GREEN : (score < 0 ? ChatColor.RED : ChatColor.YELLOW);
        return color + String.valueOf(score) + ChatColor.RESET;
    }

    /* ===================== TAB COMPLETE ===================== */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();

        if (!name.equals("rep")) {
            return result;
        }

        // /rep <...>
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);

            if ("admin".startsWith(prefix) && sender.hasPermission("enthusiacommend.admin")) {
                result.add("admin");
            }
            if ("stalk".startsWith(prefix)) {
                result.add("stalk");
            }

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(p.getName());
                }
            }
            return result;
        }

        // /rep admin <...>
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            if (!sender.hasPermission("enthusiacommend.admin")) return result;
            String prefix = args[1].toLowerCase(Locale.ROOT);
            if ("help".startsWith(prefix)) result.add("help");
            if ("get".startsWith(prefix)) result.add("get");
            if ("set".startsWith(prefix)) result.add("set");
            if ("add".startsWith(prefix)) result.add("add");
            if ("revoke".startsWith(prefix)) result.add("revoke");
            if ("reset".startsWith(prefix)) result.add("reset");
            if ("removeentry".startsWith(prefix)) result.add("removeentry");
            if ("inspect".startsWith(prefix)) result.add("inspect");
            if ("resolve".startsWith(prefix)) result.add("resolve");
            if ("reports".startsWith(prefix)) result.add("reports");
            if ("removed".startsWith(prefix)) result.add("removed");
            if ("restore".startsWith(prefix)) result.add("restore");
            if ("undo".startsWith(prefix)) result.add("undo");
            return result;
        }

        // /rep stalk <...>
        if (args[0].equalsIgnoreCase("stalk")) {
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                if ("list".startsWith(prefix)) result.add("list");
                if ("cancel".startsWith(prefix)) result.add("cancel");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        result.add(p.getName());
                    }
                }
                return result;
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("cancel")) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        result.add(p.getName());
                    }
                }
                return result;
            }
            if (args.length == 3) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                for (String day : List.of("1", "2", "3", "4", "5", "6", "7")) {
                    if (day.startsWith(prefix)) result.add(day);
                }
                return result;
            }
        }

        // /rep admin get <player>
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("get")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(p.getName());
                }
            }
            return result;
        }

        // /rep admin set <player> <score>
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("set")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(p.getName());
                }
            }
            return result;
        }

        return result;
    }
}
