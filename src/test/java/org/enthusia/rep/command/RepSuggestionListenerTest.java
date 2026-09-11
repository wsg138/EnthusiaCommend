package org.enthusia.rep.command;

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import org.junit.jupiter.api.Test;
import java.util.List;
import org.bukkit.command.CommandSender;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepSuggestionListenerTest {
    @Test
    void stripsMetadataWithoutChangingCompletionTextOrRange() {
        var range = StringRange.between(5, 9);
        var original = new Suggestions(range, List.of(new Suggestion(range, "FainNeito", new LiteralMessage("<gold><bold>name"))));
        var result = RepSuggestionListener.withoutTooltips(original);
        assertEquals(range, result.getRange());
        assertEquals(range, result.getList().get(0).getRange());
        assertEquals("FainNeito", result.getList().get(0).getText());
        assertNull(result.getList().get(0).getTooltip());
        assertNotNull(original.getList().get(0).getTooltip());
    }

    @Test
    void commandScopeIncludesAliasesAndLeavesOtherCommandsAlone() {
        assertTrue(RepSuggestionListener.isRepCommand("/rep Fain"));
        assertTrue(RepSuggestionListener.isRepCommand("reputation Fain"));
        assertTrue(RepSuggestionListener.isRepCommand("/enthusiacommend:rep Fain"));
        assertFalse(RepSuggestionListener.isRepCommand("/report Fain"));
        var event = mock(AsyncPlayerSendSuggestionsEvent.class);
        when(event.getBuffer()).thenReturn("/msg Fain");
        new RepSuggestionListener().onSuggestions(event);
        verify(event, never()).setSuggestions(any());
    }

    @Test
    void removedCommandsAreNotAdvertised() {
        var sender = mock(CommandSender.class);
        assertTrue(CommendCommand.rootSubcommands(sender).stream().noneMatch(List.of("recent", "reviews", "positive", "negative")::contains));
        when(sender.hasPermission("enthusiacommend.rep.admin")).thenReturn(true);
        assertTrue(CommendCommand.rootSubcommands(sender).stream().noneMatch(List.of("recent", "reviews", "positive", "negative")::contains));
    }
}
