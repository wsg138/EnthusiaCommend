package org.enthusia.rep.command;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.enthusia.rep.CommendPlugin;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepCategory;
import org.enthusia.rep.util.RepDateFormats;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class RecentReputationCommand {
    private final CommendPlugin plugin;

    RecentReputationCommand(CommendPlugin plugin) { this.plugin = plugin; }

    boolean execute(CommandSender sender, String[] args) {
        try {
            show(sender, args);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(ChatColor.RED + "Usage: /rep recent <player|all> [day|week] [all|positive|negative|category] [page]");
        }
        return true;
    }

    private void show(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 5) throw new IllegalArgumentException("arguments");
        UUID target = null;
        if (!args[1].equalsIgnoreCase("all")) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(args[1]);
            if (!player.isOnline() && !player.hasPlayedBefore()) {
                sender.sendMessage(ChatColor.RED + "That player has not joined this server.");
                return;
            }
            target = player.getUniqueId();
        }
        String window = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "day";
        if (!List.of("day", "week").contains(window)) throw new IllegalArgumentException("window");
        String filter = args.length > 3 ? args[3].toUpperCase(Locale.ROOT) : "ALL";
        validateFilter(filter);
        int page = args.length > 4 ? Integer.parseInt(args[4]) : 1;
        if (page < 1) throw new IllegalArgumentException("page");
        long since = System.currentTimeMillis() - plugin.getRepConfig().getRecentWindowMillis(window);
        List<Commendation> entries = select(plugin.getRepService().recentCommendationSnapshots(Integer.MAX_VALUE), target, since, filter);
        int pageSize = plugin.getRepConfig().getRecentPageSize();
        int pages = Math.max(1, (entries.size() + pageSize - 1) / pageSize);
        int resolvedPage = Math.min(page, pages);
        sender.sendMessage(ChatColor.GOLD + "Recent rep: " + args[1] + " / " + window + " / " + filter
                + " [" + resolvedPage + "/" + pages + "]");
        if (entries.isEmpty()) sender.sendMessage(ChatColor.GRAY + "No matching reputation in this time window.");
        entries.stream().skip((long) (resolvedPage - 1) * pageSize).limit(pageSize).forEach(entry -> showEntry(sender, entry));
    }

    static List<Commendation> select(List<Commendation> entries, UUID target, long since, String filter) {
        validateFilter(filter);
        return entries.stream().filter(entry -> target == null || entry.getTarget().equals(target))
                .filter(entry -> entry.getLastEditedAt() >= since)
                .filter(entry -> matches(entry, filter))
                .sorted(java.util.Comparator.comparingLong(Commendation::getLastEditedAt).reversed())
                .toList();
    }

    private static void validateFilter(String filter) {
        if (!List.of("ALL", "POSITIVE", "NEGATIVE").contains(filter)
                && !RepCategory.valueOf(filter).isSelectable()) throw new IllegalArgumentException("filter");
    }

    private static boolean matches(Commendation entry, String filter) {
        return switch (filter) {
            case "ALL" -> true;
            case "POSITIVE" -> entry.isPositive();
            case "NEGATIVE" -> !entry.isPositive();
            default -> entry.getCategory() == RepCategory.valueOf(filter);
        };
    }

    private void showEntry(CommandSender sender, Commendation entry) {
        String reason = entry.getReasonText().replace('\n', ' ');
        if (reason.length() > 100) reason = reason.substring(0, 100) + "...";
        sender.sendMessage((entry.isPositive() ? ChatColor.GREEN : ChatColor.RED)
                + plugin.getRepService().nameOf(entry.getGiver()) + " -> " + plugin.getRepService().nameOf(entry.getTarget())
                + " [" + entry.getCategory().displayName() + "] " + ChatColor.GRAY
                + RepDateFormats.dateTimeMinute().format(Instant.ofEpochMilli(entry.getLastEditedAt()))
                + ": " + ChatColor.WHITE + reason);
    }
}
