package org.enthusia.rep.moderation;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.enthusia.rep.api.ReputationStateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReputationModerationPersistenceFailureTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID GIVER = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final Instant NOW = Instant.parse("2026-08-23T20:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void failedReconciliationFenceWriteDoesNotPublishUnpersistedState() throws Exception {
        Path blockedParent = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(blockedParent, "blocking file");
        ReputationStateSnapshot snapshot = snapshot();
        ReputationModerationService service = new ReputationModerationService(
                Clock.fixed(NOW, ZoneOffset.UTC),
                ignored -> snapshot,
                blockedParent.resolve("moderation-state.yml")
        );

        assertThrows(IllegalStateException.class, () -> service.markReconciliationPending(PLAYER));
        assertTrue(service.canGiveReputation(PLAYER));
    }

    private static ReputationStateSnapshot snapshot() {
        List<ReputationEntrySnapshot> entries = List.of(new ReputationEntrySnapshot(
                GIVER, PLAYER, true, "HELPFUL", 2, 1_000L, 2_000L));
        return new ReputationStateSnapshot(
                PLAYER,
                7,
                entries,
                ReputationSnapshotFactory.checksum(PLAYER, 7, entries)
        );
    }
}
