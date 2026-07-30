package org.enthusia.rep.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderedSnapshotWriterTest {
    @Test
    void olderDelayedSnapshotCannotOverwriteNewerSnapshot() {
        FakeStore store = new FakeStore();
        OrderedSnapshotWriter writer = new OrderedSnapshotWriter(store);
        PluginDataSnapshot newer = snapshot(2);
        PluginDataSnapshot older = snapshot(1);

        assertEquals(OrderedSnapshotWriter.SaveResult.SAVED, writer.saveIfNewer(2L, newer));
        assertEquals(OrderedSnapshotWriter.SaveResult.STALE, writer.saveIfNewer(1L, older));
        assertEquals(2, store.lastMarker);
    }

    @Test
    void failedSaveDoesNotAdvanceSequenceAndCanBeRetried() {
        FakeStore store = new FakeStore();
        OrderedSnapshotWriter writer = new OrderedSnapshotWriter(store);
        store.failNext = true;

        assertEquals(OrderedSnapshotWriter.SaveResult.FAILED, writer.saveIfNewer(5L, snapshot(5)));
        assertEquals(OrderedSnapshotWriter.SaveResult.SAVED, writer.saveIfNewer(5L, snapshot(5)));
        assertEquals(5, store.lastMarker);
    }

    private static PluginDataSnapshot snapshot(int marker) {
        return new PluginDataSnapshot(Map.of(new UUID(0L, marker), marker),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static final class FakeStore implements PluginDataStore {
        private boolean failNext;
        private int lastMarker;

        @Override
        public PluginDataSnapshot load() {
            return PluginDataSnapshot.EMPTY;
        }

        @Override
        public boolean save(PluginDataSnapshot snapshot) {
            if (failNext) {
                failNext = false;
                return false;
            }
            lastMarker = snapshot.scores().values().stream().findFirst().orElse(0);
            return true;
        }
    }
}
