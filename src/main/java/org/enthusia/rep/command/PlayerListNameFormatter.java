package org.enthusia.rep.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Formats literal markup used by clients as player suggestion tooltip text. */
public final class PlayerListNameFormatter implements Runnable {
    private static final MiniMessage FORMATTING = MiniMessage.builder().tags(TagResolver.resolver(
            StandardTags.color(), StandardTags.decorations(), StandardTags.reset(), StandardTags.gradient(), StandardTags.rainbow())).build();

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Component original = player.playerListName();
            Component formatted = translate(original);
            if (!java.util.Objects.equals(original, formatted)) player.playerListName(formatted);
        }
    }

    static Component translate(Component component) {
        if (component == null) return null;
        Component result = component;
        if (component instanceof TextComponent text && !FORMATTING.stripTags(text.content()).equals(text.content())) {
            Component parsed = FORMATTING.deserialize(text.content());
            result = Component.empty().style(text.style()).append(parsed);
        }
        return result.children(java.util.stream.Stream.concat(result == component ? java.util.stream.Stream.empty()
                : result.children().stream(), component.children().stream().map(PlayerListNameFormatter::translate)).toList());
    }
}
