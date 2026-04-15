package org.enthusia.rep.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public class CommendationLeaderboardViewedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID viewerId;

    public CommendationLeaderboardViewedEvent(UUID viewerId) {
        this.viewerId = viewerId;
    }

    public UUID getViewerId() { return viewerId; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
