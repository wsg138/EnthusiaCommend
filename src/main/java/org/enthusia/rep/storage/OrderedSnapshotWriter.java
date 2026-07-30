package org.enthusia.rep.storage;

/**
 * Serializes snapshot writes and prevents a delayed older autosave from overwriting
 * a newer autosave or the final shutdown snapshot.
 */
public final class OrderedSnapshotWriter {
    private final PluginDataStore dataStore;
    private long latestAcceptedSequence = Long.MIN_VALUE;

    public OrderedSnapshotWriter(PluginDataStore dataStore) {
        this.dataStore = dataStore;
    }

    public synchronized SaveResult saveIfNewer(long sequence, PluginDataSnapshot snapshot) {
        if (sequence <= latestAcceptedSequence) {
            return SaveResult.STALE;
        }
        latestAcceptedSequence = sequence;
        return dataStore.save(snapshot) ? SaveResult.SAVED : SaveResult.FAILED;
    }

    public enum SaveResult {
        SAVED,
        STALE,
        FAILED
    }
}
