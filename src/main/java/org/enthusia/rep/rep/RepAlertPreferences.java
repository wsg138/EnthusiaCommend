package org.enthusia.rep.rep;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** UUID-keyed explicit alert choices. Missing entries inherit the live config default. */
public final class RepAlertPreferences {
    private final Map<UUID, Boolean> explicitChoices = new ConcurrentHashMap<>();
    private volatile boolean enabledByDefault;

    public RepAlertPreferences(boolean enabledByDefault, Map<UUID, Boolean> persistedChoices) {
        this.enabledByDefault = enabledByDefault;
        if (persistedChoices != null) {
            persistedChoices.forEach((uuid, enabled) -> {
                if (uuid != null && enabled != null) {
                    explicitChoices.put(uuid, enabled);
                }
            });
        }
    }

    public boolean isEnabled(UUID playerId) {
        if (playerId == null) {
            return enabledByDefault;
        }
        return explicitChoices.getOrDefault(playerId, enabledByDefault);
    }

    public boolean toggle(UUID playerId) {
        if (playerId == null) {
            return enabledByDefault;
        }
        boolean updated = !isEnabled(playerId);
        explicitChoices.put(playerId, updated);
        return updated;
    }

    public void reloadDefault(boolean enabledByDefault) {
        this.enabledByDefault = enabledByDefault;
    }

    public Map<UUID, Boolean> snapshot() {
        return Map.copyOf(new LinkedHashMap<>(explicitChoices));
    }
}
