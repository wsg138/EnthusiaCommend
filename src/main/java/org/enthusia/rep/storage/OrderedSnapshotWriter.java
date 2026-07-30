package org.enthusia.rep.storage;

/**
 * Serializes snapshot writes and prevents a delayed older autosave from overwriting
 * a newer autosave or the final shutdown snapshot.
 */
public final class OrderedSnapshotWriter {
    private final PluginDataStore dataStore;
    private long latestSavedSequence = Long.MIN_VALUE;

    public OrderedSnapshotWriter(PluginDataStore dataStore) {
        this.dataStore = dataStore;
    }

    public synchronized SaveResult saveIfNewer(long sequence, PluginDataSnapshot snapshot) {
        if (sequence <= latestSavedSequence) {
            return SaveResult.STALE;
        }
        if (!dataStore.save(snapshot)) {
            return SaveResult.FAILED;
        }
        latestSavedSequence = sequence;
        return SaveResult.SAVED;
    }

    public enum SaveResult {
        SAVED,
        STALE,
        FAILED
    }
}
