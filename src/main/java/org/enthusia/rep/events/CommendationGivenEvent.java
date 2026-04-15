package org.enthusia.rep.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public class CommendationGivenEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID giverId;
    private final UUID targetId;
    private final boolean positive;

    public CommendationGivenEvent(UUID giverId, UUID targetId, boolean positive) {
        this.giverId = giverId;
        this.targetId = targetId;
        this.positive = positive;
    }

    public UUID getGiverId() { return giverId; }
    public UUID getTargetId() { return targetId; }
    public boolean isPositive() { return positive; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
