from pathlib import Path

plugin_path = Path("src/main/java/org/enthusia/rep/CommendPlugin.java")
plugin = plugin_path.read_text(encoding="utf-8")
old_snapshot = '''        return new PluginDataSnapshot(
                repSnapshot.scores(),
                repSnapshot.commendations(),
                repSnapshot.removedEntries(),
                repSnapshot.stalkEntries(),
                analyticsService != null ? analyticsService.snapshot() : java.util.List.of(),
                repSnapshot.suspiciousCases()
        );'''
new_snapshot = '''        return new PluginDataSnapshot(
                repSnapshot.scores(),
                repSnapshot.commendations(),
                repSnapshot.removedEntries(),
                repSnapshot.stalkEntries(),
                analyticsService != null ? analyticsService.snapshot() : java.util.List.of(),
                repSnapshot.suspiciousCases(),
                repSnapshot.removalCooldowns()
        );'''
if old_snapshot not in plugin:
    raise SystemExit("Composite snapshot block not found")
plugin_path.write_text(plugin.replace(old_snapshot, new_snapshot, 1), encoding="utf-8")

Path("src/main/java/org/enthusia/rep/storage/OrderedSnapshotWriter.java").write_text('''package org.enthusia.rep.storage;

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
''', encoding="utf-8")

Path("src/test/java/org/enthusia/rep/storage/OrderedSnapshotWriterTest.java").write_text('''package org.enthusia.rep.storage;

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
''', encoding="utf-8")
