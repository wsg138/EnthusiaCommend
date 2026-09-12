package org.enthusia.rep.rep;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Only address hashes are retained; vote history survives removal and restarts. */
public record RepIdentityState(Set<String> ipHashes, Set<UUID> givenTargets, long tarnishedAt,
                               java.util.Map<String, Long> tarnishSources) {
    public static final RepIdentityState EMPTY = new RepIdentityState(Set.of(), Set.of(), 0);

    public RepIdentityState {
        ipHashes = Set.copyOf(ipHashes);
        givenTargets = Set.copyOf(givenTargets);
        tarnishSources = java.util.Map.copyOf(tarnishSources);
    }

    public RepIdentityState(Set<String> ipHashes, Set<UUID> givenTargets, long tarnishedAt) {
        this(ipHashes, givenTargets, tarnishedAt, java.util.Map.of());
    }

    public RepIdentityState rememberIp(String hash) {
        if (hash == null || hash.isBlank() || ipHashes.contains(hash)) return this;
        Set<String> updated = new HashSet<>(ipHashes);
        updated.add(hash);
        return new RepIdentityState(updated, givenTargets, tarnishedAt, tarnishSources);
    }

    public RepIdentityState rememberVote(UUID target) {
        Set<UUID> updated = new HashSet<>(givenTargets);
        updated.add(target);
        return new RepIdentityState(ipHashes, updated, tarnishedAt, tarnishSources);
    }

    public RepIdentityState tarnish(long now) {
        return new RepIdentityState(ipHashes, givenTargets, now, tarnishSources);
    }

    public RepIdentityState tarnish(UUID giver, long now) {
        var updated = new java.util.concurrent.ConcurrentHashMap<>(tarnishSources);
        updated.put(giver.toString(), now);
        return new RepIdentityState(ipHashes, givenTargets, now, updated);
    }

    public RepIdentityState forgive(UUID giver) {
        var updated = new java.util.concurrent.ConcurrentHashMap<>(tarnishSources);
        updated.remove(giver.toString());
        long latest = updated.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        return new RepIdentityState(ipHashes, givenTargets, latest, updated);
    }

    public RepIdentityState migrateSources(java.util.List<Commendation> entries) {
        if (tarnishedAt <= 0 || !tarnishSources.isEmpty()) return this;
        var sources = entries.stream().filter(entry -> !entry.isPositive())
                .collect(java.util.stream.Collectors.toUnmodifiableMap(entry -> entry.getGiver().toString(),
                        entry -> Math.min(tarnishedAt, entry.getLastEditedAt()), Math::max));
        return new RepIdentityState(ipHashes, givenTargets, tarnishedAt, sources);
    }
}
