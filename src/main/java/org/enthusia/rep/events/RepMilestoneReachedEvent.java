package org.enthusia.rep.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public class RepMilestoneReachedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final int oldScore;
    private final int newScore;

    public RepMilestoneReachedEvent(UUID playerId, int oldScore, int newScore) {
        this.playerId = playerId;
        this.oldScore = oldScore;
        this.newScore = newScore;
    }

    public UUID getPlayerId() { return playerId; }
    public int getOldScore() { return oldScore; }
    public int getNewScore() { return newScore; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
