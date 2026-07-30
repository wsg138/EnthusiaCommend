from pathlib import Path
import re


def patch_gui() -> None:
    path = Path("src/main/java/org/enthusia/rep/gui/RepGuiManager.java")
    text = path.read_text(encoding="utf-8")
    original = text

    text = text.replace(
        "return List.of(RepCategory.WAS_KIND, RepCategory.HELPED_ME, RepCategory.GAVE_ITEMS, RepCategory.TRUSTWORTHY, RepCategory.GOOD_STALL, RepCategory.OTHER_POSITIVE);",
        "return List.of(RepCategory.WAS_KIND, RepCategory.HELPED_ME, RepCategory.GAVE_ITEMS, RepCategory.TRUSTWORTHY, RepCategory.GOOD_STALL);"
    )
    text = text.replace(
        "return List.of(RepCategory.SCAMMED, RepCategory.SPAWN_KILLED, RepCategory.GRIEFED, RepCategory.TRAPPED, RepCategory.SCAM_STALL, RepCategory.OTHER_NEGATIVE);",
        "return List.of(RepCategory.SCAMMED, RepCategory.SPAWN_KILLED, RepCategory.GRIEFED, RepCategory.TRAPPED, RepCategory.SCAM_STALL);"
    )

    old_profile = '''            headMeta.setLore(List.of(
                    ChatColor.GRAY + "Total Rep: " + scoreColor + score,
                    ChatColor.GRAY + "Positives: " + ChatColor.GREEN + "+" + positives,
                    ChatColor.GRAY + "Negatives: " + ChatColor.RED + "-" + negatives
            ));'''
    new_profile = '''            List<String> profileLore = new ArrayList<>();
            profileLore.add(ChatColor.GRAY + "Total Rep: " + scoreColor + score);
            profileLore.add(ChatColor.GRAY + "Positive reviews: " + ChatColor.GREEN + positives);
            profileLore.add(ChatColor.GRAY + "Negative reviews: " + ChatColor.RED + negatives);
            Map<RepCategory, Integer> categoryScores = repService.getCategoryScores(targetId);
            if (!categoryScores.isEmpty()) {
                profileLore.add("");
                profileLore.add(ChatColor.GOLD + "Category scores:");
                categoryScores.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> profileLore.add(ChatColor.GRAY + displayName(entry.getKey()) + ": "
                                + coloredValue(entry.getValue())));
            }
            headMeta.setLore(profileLore);'''
    if old_profile not in text:
        raise SystemExit("profile lore block not found")
    text = text.replace(old_profile, new_profile)

    text, replacements = re.subn(
        r'(\w+)\.isPositive\(\) \? ChatColor\.GREEN \+ "\+1" : ChatColor\.RED \+ "-1"',
        r'coloredValue(\1.getScoreValue())',
        text
    )
    if replacements < 3:
        raise SystemExit(f"expected at least 3 weighted-value replacements, found {replacements}")

    old_report_item = '''            meta.setLore(List.of(
                    ChatColor.GRAY + "IP: " + ChatColor.WHITE + caseData.ipHash(),
                    ChatColor.GRAY + "Accounts: " + ChatColor.WHITE + formatNames(caseData.givers()),
                    ChatColor.GRAY + "Created: " + ChatColor.WHITE + dateFormatter.format(Instant.ofEpochMilli(caseData.getCreatedAt())),
                    ChatColor.YELLOW + "Click to post details in chat."
            ));'''
    new_report_item = '''            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Type: " + ChatColor.WHITE + caseData.type());
            lore.add(ChatColor.GRAY + "Key: " + ChatColor.WHITE + caseData.key());
            lore.add(ChatColor.GRAY + "Accounts: " + ChatColor.WHITE + formatNames(caseData.givers()));
            lore.add(ChatColor.GRAY + "Created: " + ChatColor.WHITE
                    + dateFormatter.format(Instant.ofEpochMilli(caseData.getCreatedAt())));
            if (!caseData.detail().isBlank()) {
                lore.add(ChatColor.GRAY + "Details:");
                lore.addAll(wrapLore(caseData.detail(), 34, ChatColor.WHITE));
            }
            lore.add(ChatColor.YELLOW + "Click to post details in chat.");
            meta.setLore(lore);'''
    if old_report_item not in text:
        raise SystemExit("active report item block not found")
    text = text.replace(old_report_item, new_report_item)

    old_report_details = '''        admin.sendMessage(ChatColor.GOLD + "ALT REP REPORT: " + ChatColor.YELLOW + repService.nameOf(caseData.getTarget())
                + ChatColor.GRAY + " (IP " + ChatColor.YELLOW + caseData.ipHash() + ChatColor.GRAY + ")");
        admin.sendMessage(ChatColor.GRAY + "Accounts: " + ChatColor.WHITE + formatNames(caseData.givers()));
        admin.sendMessage(ChatColor.GRAY + "Created: " + ChatColor.WHITE + dateFormatter.format(Instant.ofEpochMilli(caseData.getCreatedAt())));'''
    new_report_details = '''        admin.sendMessage(ChatColor.GOLD + "REP REPORT: " + ChatColor.YELLOW + repService.nameOf(caseData.getTarget()));
        admin.sendMessage(ChatColor.GRAY + "Type: " + ChatColor.WHITE + caseData.type()
                + ChatColor.GRAY + " | Key: " + ChatColor.WHITE + caseData.key());
        admin.sendMessage(ChatColor.GRAY + "Accounts: " + ChatColor.WHITE + formatNames(caseData.givers()));
        admin.sendMessage(ChatColor.GRAY + "Created: " + ChatColor.WHITE
                + dateFormatter.format(Instant.ofEpochMilli(caseData.getCreatedAt())));
        if (!caseData.detail().isBlank()) {
            admin.sendMessage(ChatColor.GRAY + "Details: " + ChatColor.WHITE + caseData.detail());
        }'''
    if old_report_details not in text:
        raise SystemExit("report details block not found")
    text = text.replace(old_report_details, new_report_details)

    helper_anchor = '''    private String percent(int value) {
        return value > 0 ? "+" + value + "%" : value + "%";
    }'''
    helper = '''    private String coloredValue(int value) {
        return (value > 0 ? ChatColor.GREEN : value < 0 ? ChatColor.RED : ChatColor.YELLOW)
                + (value > 0 ? "+" + value : String.valueOf(value));
    }

    private String percent(int value) {
        return value > 0 ? "+" + value + "%" : value + "%";
    }'''
    if helper_anchor not in text:
        raise SystemExit("helper anchor not found")
    text = text.replace(helper_anchor, helper)

    if "RepCategory.OTHER_POSITIVE);" in text or "RepCategory.OTHER_NEGATIVE);" in text:
        raise SystemExit("legacy Other category remains selectable")
    if re.search(r'isPositive\(\).*?"\+1".*?"-1"', text):
        raise SystemExit("hard-coded commendation value remains")
    if text == original:
        raise SystemExit("no GUI changes applied")
    path.write_text(text, encoding="utf-8")


def patch_history() -> None:
    service_path = Path("src/main/java/org/enthusia/rep/rep/RepService.java")
    service = service_path.read_text(encoding="utf-8")
    old_service = '''        if (delta != 0) {
            recordPlayerChange(targetId, giverId, delta, ReputationChangeAction.UPDATE, category, reasonText, oldScore, newScore);
        }'''
    new_service = '''        recordPlayerChange(targetId, giverId, delta, ReputationChangeAction.UPDATE, category, reasonText, oldScore, newScore);'''
    if old_service not in service:
        raise SystemExit("RepService metadata-update history block not found")
    service_path.write_text(service.replace(old_service, new_service), encoding="utf-8")

    analytics_path = Path("src/main/java/org/enthusia/rep/analytics/ReputationAnalyticsService.java")
    analytics = analytics_path.read_text(encoding="utf-8")
    old_guard = '''        if (targetId == null || amount == 0 || oldTotal == newTotal) {
            return;
        }'''
    new_guard = '''        boolean metadataOnlyUpdate = action == ReputationChangeAction.UPDATE
                && amount == 0
                && oldTotal == newTotal;
        if (targetId == null || (!metadataOnlyUpdate && (amount == 0 || oldTotal == newTotal))) {
            return;
        }'''
    if old_guard not in analytics:
        raise SystemExit("analytics zero-delta guard not found")
    analytics_path.write_text(analytics.replace(old_guard, new_guard), encoding="utf-8")


def patch_command() -> None:
    path = Path("src/main/java/org/enthusia/rep/command/CommendCommand.java")
    text = path.read_text(encoding="utf-8")

    old_parser = '''        try {
            RepCategory category = RepCategory.valueOf(normalized).migratedCategory();
            return category.isSelectable() ? category : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }'''
    new_parser = '''        try {
            RepCategory category = RepCategory.valueOf(normalized);
            return category.isSelectable() ? category : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }'''
    if old_parser not in text:
        raise SystemExit("direct command category parser block not found")
    text = text.replace(old_parser, new_parser)

    old_history = '''            sender.sendMessage(ChatColor.DARK_GRAY + dateFormatter.format(Instant.ofEpochMilli(change.timestamp()))
                    + " " + coloredValue(change.amount()) + ChatColor.GRAY + category
                    + " by " + ChatColor.WHITE + actor + ChatColor.GRAY + " -> "'''
    new_history = '''            String action = change.action().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            sender.sendMessage(ChatColor.DARK_GRAY + dateFormatter.format(Instant.ofEpochMilli(change.timestamp()))
                    + " " + ChatColor.AQUA + action + " " + coloredValue(change.amount()) + ChatColor.GRAY + category
                    + " by " + ChatColor.WHITE + actor + ChatColor.GRAY + " -> "'''
    if old_history not in text:
        raise SystemExit("history display block not found")
    text = text.replace(old_history, new_history)

    path.write_text(text, encoding="utf-8")


patch_gui()
patch_history()
patch_command()
