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

        assertEquals(OrderedSnapshotWriter.SaveResult.SAVED, writer.saveIfNewer(2L, snapshot(2)));
        assertEquals(OrderedSnapshotWriter.SaveResult.STALE, writer.saveIfNewer(1L, snapshot(1)));
        assertEquals(2, store.lastMarker);
    }

    @Test
    void failedNewerAttemptStillBlocksAnOlderSnapshot() {
        FakeStore store = new FakeStore();
        OrderedSnapshotWriter writer = new OrderedSnapshotWriter(store);
        store.failNext = true;

        assertEquals(OrderedSnapshotWriter.SaveResult.FAILED, writer.saveIfNewer(5L, snapshot(5)));
        assertEquals(OrderedSnapshotWriter.SaveResult.STALE, writer.saveIfNewer(4L, snapshot(4)));
        assertEquals(0, store.lastMarker);
        assertEquals(OrderedSnapshotWriter.SaveResult.SAVED, writer.saveIfNewer(6L, snapshot(6)));
        assertEquals(6, store.lastMarker);
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
