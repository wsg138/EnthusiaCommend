package org.enthusia.rep.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public class CommendationEditedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID giverId;
    private final UUID targetId;
    private final boolean newPositive;

    public CommendationEditedEvent(UUID giverId, UUID targetId, boolean newPositive) {
        this.giverId = giverId;
        this.targetId = targetId;
        this.newPositive = newPositive;
    }

    public UUID getGiverId() { return giverId; }
    public UUID getTargetId() { return targetId; }
    public boolean isNewPositive() { return newPositive; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
