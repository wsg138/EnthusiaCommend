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
    static final int TARGET_ARGUMENTS = 2;
    static final int WINDOW_ARGUMENTS = 3;
    static final int FILTER_ARGUMENTS = 4;
    static final int PAGE_ARGUMENTS = 5;
    private static final int FIRST_PAGE = 1;
    private static final int PREVIEW_LENGTH = 100;
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
        Query query = parse(args);
        if (query.target().isPresent() && !hasJoined(query.target().get())) {
            sender.sendMessage(ChatColor.RED + "That player has not joined this server.");
            return;
        }
        long since = System.currentTimeMillis() - plugin.getRepConfig().getRecentWindowMillis(query.window());
        List<Commendation> entries = select(plugin.getRepService().recentCommendationSnapshots(Integer.MAX_VALUE),
                query.target().map(OfflinePlayer::getUniqueId).orElse(null), since, query.filter());
        showPage(sender, query, entries);
    }

    private static boolean hasJoined(OfflinePlayer player) {
        return player.isOnline() || player.hasPlayedBefore();
    }

    private static Query parse(String[] args) {
        if (args.length < TARGET_ARGUMENTS || args.length > PAGE_ARGUMENTS) throw new IllegalArgumentException("arguments");
        java.util.Optional<OfflinePlayer> target = args[1].equalsIgnoreCase("all")
                ? java.util.Optional.empty() : java.util.Optional.of(Bukkit.getOfflinePlayer(args[1]));
        String window = argument(args, WINDOW_ARGUMENTS, "day").toLowerCase(Locale.ROOT);
        if (!List.of("day", "week").contains(window)) throw new IllegalArgumentException("window");
        String filter = argument(args, FILTER_ARGUMENTS, "ALL").toUpperCase(Locale.ROOT);
        validateFilter(filter);
        int page = Integer.parseInt(argument(args, PAGE_ARGUMENTS, Integer.toString(FIRST_PAGE)));
        if (page < FIRST_PAGE) throw new IllegalArgumentException("page");
        return new Query(args[1], target, window, filter, page);
    }

    private static String argument(String[] args, int count, String fallback) {
        return args.length >= count ? args[count - 1] : fallback;
    }

    private void showPage(CommandSender sender, Query query, List<Commendation> entries) {
        int pageSize = plugin.getRepConfig().getRecentPageSize();
        int pages = Math.max(FIRST_PAGE, (entries.size() + pageSize - 1) / pageSize);
        int resolvedPage = Math.min(query.page(), pages);
        sender.sendMessage(ChatColor.GOLD + "Recent rep: " + query.targetName() + " / " + query.window() + " / " + query.filter()
                + " [" + resolvedPage + "/" + pages + "]");
        if (entries.isEmpty()) sender.sendMessage(ChatColor.GRAY + "No matching reputation in this time window.");
        entries.stream().skip((long) (resolvedPage - 1) * pageSize).limit(pageSize).forEach(entry -> showEntry(sender, entry));
    }

    private record Query(String targetName, java.util.Optional<OfflinePlayer> target, String window, String filter, int page) { }

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
        if (reason.length() > PREVIEW_LENGTH) reason = reason.substring(0, PREVIEW_LENGTH) + "...";
        sender.sendMessage((entry.isPositive() ? ChatColor.GREEN : ChatColor.RED)
                + plugin.getRepService().nameOf(entry.getGiver()) + " -> " + plugin.getRepService().nameOf(entry.getTarget())
                + " [" + entry.getCategory().displayName() + "] " + ChatColor.GRAY
                + RepDateFormats.dateTimeMinute().format(Instant.ofEpochMilli(entry.getLastEditedAt()))
                + ": " + ChatColor.WHITE + reason);
    }
}
