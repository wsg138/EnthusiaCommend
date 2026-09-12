package org.enthusia.rep.command;

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Locale;
import java.util.Set;

/** Strip display-name metadata only from this plugin's outgoing completions. */
public final class RepSuggestionListener implements Listener {
    private static final Set<String> LABELS = Set.of("rep", "reputation", "enthusiacommend:rep", "enthusiacommend:reputation");

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSuggestions(AsyncPlayerSendSuggestionsEvent event) {
        if (isRepCommand(event.getBuffer())) event.setSuggestions(withoutTooltips(event.getSuggestions()));
    }

    static boolean isRepCommand(String buffer) {
        String command = buffer.stripLeading().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (command.startsWith("/")) command = command.substring(1);
        return LABELS.contains(command);
    }

    static Suggestions withoutTooltips(Suggestions suggestions) {
        return new Suggestions(suggestions.getRange(), suggestions.getList().stream()
                .map(suggestion -> new Suggestion(suggestion.getRange(), suggestion.getText())).toList());
    }
}
