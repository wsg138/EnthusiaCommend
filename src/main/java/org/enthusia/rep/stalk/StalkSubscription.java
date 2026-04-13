package org.enthusia.rep.stalk;

import java.util.UUID;

public record StalkSubscription(UUID target, long expiresAt) {
    public boolean isActive(long now) {
        return expiresAt > now;
    }
}
