package org.enthusia.rep.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public class CommendationReceivedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID targetId;
    private final UUID giverId;
    private final boolean positive;
    private final int newScore;

    public CommendationReceivedEvent(UUID targetId, UUID giverId, boolean positive, int newScore) {
        this.targetId = targetId;
        this.giverId = giverId;
        this.positive = positive;
        this.newScore = newScore;
    }

    public UUID getTargetId() { return targetId; }
    public UUID getGiverId() { return giverId; }
    public boolean isPositive() { return positive; }
    public int getNewScore() { return newScore; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
