package org.enthusia.rep.storage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure, in-memory projection of a primary-data correction metadata ledger.
 *
 * <p>Does not write, authorize, verify persistence or issue any receipt.
 * The outer primary data state and this ledger MUST be serialized together
 * in one atomic storage generation by a separate, future executor.</p>
 */
public record CorrectionLedgerState(long currentGeneration, List<CorrectionCommitMetadata> entries) {
    private static final int MAX_RECORDS = 4096;
    private static final long INITIAL_GENERATION = 0L;
    private static final String GENERATION = "generation";
    private static final String CORRECTIONS = "corrections";

    public CorrectionLedgerState {
        if (currentGeneration < INITIAL_GENERATION) {
            throw new IllegalArgumentException("Primary generation cannot be negative");
        }
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (entries.size() > MAX_RECORDS) {
            throw new IllegalArgumentException("Correction ledger exceeds its safe capacity");
        }
        Set<UUID> operationIds = new HashSet<>();
        long latest = INITIAL_GENERATION;
        for (CorrectionCommitMetadata entry : entries) {
            if (!operationIds.add(entry.operationId())
                    || entry.dataGeneration() <= latest
                    || entry.dataGeneration() > currentGeneration) {
                throw new IllegalArgumentException("Duplicate or incorrectly ordered correction ledger entry");
            }
            latest = entry.dataGeneration();
        }
    }

    public static CorrectionLedgerState empty() {
        return new CorrectionLedgerState(INITIAL_GENERATION, List.of());
    }

    /**
     * Offline candidate only. Identical operation replay returns the existing
     * projected ledger; changed operation replay fails, never double-applies.
     */
    public CorrectionLedgerState projectAppend(CorrectionCommitMetadata proposed) {
        Objects.requireNonNull(proposed, "proposed");
        for (CorrectionCommitMetadata current : entries) {
            if (current.operationId().equals(proposed.operationId())) {
                if (!current.equals(proposed)) {
                    throw new IllegalStateException("Correction operation ID was reused with different metadata");
                }
                return this;
            }
        }
        if (entries.size() >= MAX_RECORDS) {
            throw new IllegalStateException("Correction ledger is full; operator reconciliation required");
        }
        final long nextGeneration = Math.addExact(currentGeneration, 1L);
        if (proposed.dataGeneration() != nextGeneration) {
            throw new IllegalStateException("New correction generation must advance exactly once");
        }
        List<CorrectionCommitMetadata> copied = new ArrayList<>(entries);
        copied.add(proposed);
        return new CorrectionLedgerState(nextGeneration, copied);
    }

    // Local ordered YAML snapshot assembly, never shared as concurrent mutable state.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(GENERATION, currentGeneration);
        result.put(CORRECTIONS, entries.stream().map(CorrectionCommitMetadata::toMap).toList());
        return result;
    }

    public static CorrectionLedgerState fromMap(Map<?, ?> source) {
        if (source == null || !source.keySet().equals(Set.of(GENERATION, CORRECTIONS))) {
            throw new IllegalArgumentException("Correction ledger has unknown or missing fields");
        }
        Object rawGeneration = source.get(GENERATION);
        if (!(rawGeneration instanceof Long) && !(rawGeneration instanceof Integer)) {
            throw new IllegalArgumentException("Correction ledger generation must be an integer");
        }
        long generation = ((Number) rawGeneration).longValue();
        if (!(source.get(CORRECTIONS) instanceof List<?> serialized)
                || serialized.size() > MAX_RECORDS) {
            throw new IllegalArgumentException("Correction ledger entries are invalid");
        }
        List<CorrectionCommitMetadata> parsed = new ArrayList<>();
        for (Object raw : serialized) {
            if (!(raw instanceof Map<?, ?> item)) {
                throw new IllegalArgumentException("Correction ledger contains a non-map entry");
            }
            parsed.add(CorrectionCommitMetadata.fromMap(item));
        }
        return new CorrectionLedgerState(generation, parsed);
    }
}
