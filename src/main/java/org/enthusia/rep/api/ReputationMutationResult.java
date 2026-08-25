package org.enthusia.rep.api;

import java.util.Objects;
import java.util.Optional;

public record ReputationMutationResult(
        Status status,
        Optional<ReputationBlacklist> blacklist,
        ReputationStateSnapshot before,
        ReputationStateSnapshot after,
        String detail
) {
    public ReputationMutationResult {
        Objects.requireNonNull(status, "status");
        blacklist = Objects.requireNonNull(blacklist, "blacklist");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        detail = Objects.requireNonNull(detail, "detail");
    }

    public boolean success() {
        return status == Status.APPLIED || status == Status.REMOVED || status == Status.REPLAYED;
    }

    public enum Status {
        APPLIED,
        REMOVED,
        REPLAYED,
        STALE_REPUTATION,
        STALE_BLACKLIST,
        REJECTED
    }
}
