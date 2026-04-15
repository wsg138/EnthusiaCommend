package org.enthusia.rep.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public class CommendationProfileViewedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID viewerId;
    private final UUID targetId;

    public CommendationProfileViewedEvent(UUID viewerId, UUID targetId) {
        this.viewerId = viewerId;
        this.targetId = targetId;
    }

    public UUID getViewerId() { return viewerId; }
    public UUID getTargetId() { return targetId; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
