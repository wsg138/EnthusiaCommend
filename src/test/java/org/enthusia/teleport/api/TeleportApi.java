package org.enthusia.teleport.api;

import java.util.UUID;

/** Test fixture for the optional service loaded reflectively by the integration. */
public interface TeleportApi {
    void setWarmupModifier(UUID playerId, double multiplier);
    void setCooldownModifier(UUID playerId, double multiplier);
}
