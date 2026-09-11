package org.enthusia.rep.rep;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Only address hashes are retained; vote history survives removal and restarts. */
public record RepIdentityState(Set<String> ipHashes, Set<UUID> givenTargets, long tarnishedAt) {
    public static final RepIdentityState EMPTY = new RepIdentityState(Set.of(), Set.of(), 0);

    public RepIdentityState {
        ipHashes = Set.copyOf(ipHashes);
        givenTargets = Set.copyOf(givenTargets);
    }

    public RepIdentityState rememberIp(String hash) {
        if (hash == null || hash.isBlank() || ipHashes.contains(hash)) return this;
        Set<String> updated = new HashSet<>(ipHashes);
        updated.add(hash);
        return new RepIdentityState(updated, givenTargets, tarnishedAt);
    }

    public RepIdentityState rememberVote(UUID target) {
        Set<UUID> updated = new HashSet<>(givenTargets);
        updated.add(target);
        return new RepIdentityState(ipHashes, updated, tarnishedAt);
    }

    public RepIdentityState tarnish(long now) {
        return new RepIdentityState(ipHashes, givenTargets, now);
    }
}
