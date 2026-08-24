package org.enthusia.rep.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.enthusia.rep.api.ReputationBlacklist;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.enthusia.rep.api.ReputationMutationResult;
import org.enthusia.rep.api.ReputationStateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReputationModerationServiceTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID GIVER = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final Instant NOW = Instant.parse("2026-08-23T20:00:00Z");
    private static final String STATE_FILE = "state.yml";
    private static final String CASE_ONE = "case-1";

    @TempDir
    Path temporaryDirectory;

    @Test
    void applyReplayRestartAndRemovePreserveExactReputationState() {
        MutableClock clock = new MutableClock(NOW);
        AtomicReference<ReputationStateSnapshot> state = new AtomicReference<>(snapshot(7));
        Path file = temporaryDirectory.resolve("moderation-state.yml");
        ReputationModerationService service = new ReputationModerationService(clock, ignored -> state.get(), file);
        UUID applyId = UUID.fromString("00000000-0000-0000-0000-000000000101");

        ReputationMutationResult applied = service.applyBlacklist(
                applyId, PLAYER, Optional.empty(), "case-42", 0L, state.get().checksum());

        assertEquals(ReputationMutationResult.Status.APPLIED, applied.status());
        assertEquals(applied.before(), applied.after());
        assertFalse(service.canGiveReputation(PLAYER));
        assertEquals(7, service.snapshot(PLAYER).totalScore());
        ReputationBlacklist blacklist = applied.blacklist().orElseThrow();
        assertEquals(1L, blacklist.revision());

        ReputationMutationResult replay = service.applyBlacklist(
                applyId, PLAYER, Optional.empty(), "case-42", 0L, state.get().checksum());
        assertEquals(ReputationMutationResult.Status.REPLAYED, replay.status());
        assertEquals(1L, service.getBlacklist(PLAYER).orElseThrow().revision());

        ReputationModerationService restarted = new ReputationModerationService(clock, ignored -> state.get(), file);
        assertFalse(restarted.canGiveReputation(PLAYER));
        assertEquals(ReputationMutationResult.Status.REPLAYED, restarted.applyBlacklist(
                applyId, PLAYER, Optional.empty(), "case-42", 0L, state.get().checksum()).status());

        UUID removeId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        ReputationMutationResult removed = restarted.removeBlacklist(
                removeId, PLAYER, "case-43", blacklist.revision(), state.get().checksum());
        assertEquals(ReputationMutationResult.Status.REMOVED, removed.status());
        assertEquals(removed.before(), removed.after());
        assertTrue(restarted.canGiveReputation(PLAYER));
        assertEquals(ReputationBlacklist.Status.REMOVED,
                restarted.getBlacklist(PLAYER).orElseThrow().status());
        assertEquals(7, restarted.snapshot(PLAYER).totalScore());

        ReputationModerationService afterRemovalRestart = new ReputationModerationService(
                clock, ignored -> state.get(), file);
        assertTrue(afterRemovalRestart.canGiveReputation(PLAYER));
        assertEquals(ReputationMutationResult.Status.REPLAYED, afterRemovalRestart.removeBlacklist(
                removeId, PLAYER, "case-43", blacklist.revision(), state.get().checksum()).status());
    }

    @Test
    void pendingAuthoritativeReconciliationFailsClosedWithoutChangingReputation() {
        AtomicReference<ReputationStateSnapshot> state = new AtomicReference<>(snapshot(7));
        ReputationModerationService service = new ReputationModerationService(
                Clock.fixed(NOW, ZoneOffset.UTC), ignored -> state.get(), temporaryDirectory.resolve(STATE_FILE));

        service.markReconciliationPending(PLAYER);
        assertFalse(service.canGiveReputation(PLAYER));
        assertTrue(service.getBlacklist(PLAYER).isEmpty());
        assertEquals(state.get(), service.snapshot(PLAYER));

        service.clearReconciliationPending(PLAYER);
        assertTrue(service.canGiveReputation(PLAYER));

        ReputationMutationResult applied = service.applyBlacklist(
                UUID.randomUUID(), PLAYER, Optional.empty(), CASE_ONE, 0L, state.get().checksum());
        assertEquals(ReputationMutationResult.Status.APPLIED, applied.status());
        service.markReconciliationPending(PLAYER);
        service.clearReconciliationPending(PLAYER);
        assertFalse(service.canGiveReputation(PLAYER));
    }

    @Test
    void staleReputationAndBlacklistRevisionFailClosedWithoutMutation() {
        MutableClock clock = new MutableClock(NOW);
        AtomicReference<ReputationStateSnapshot> state = new AtomicReference<>(snapshot(7));
        ReputationModerationService service = new ReputationModerationService(
                clock, ignored -> state.get(), temporaryDirectory.resolve(STATE_FILE));
        String originalChecksum = state.get().checksum();
        state.set(snapshot(8));

        ReputationMutationResult staleApply = service.applyBlacklist(
                UUID.randomUUID(), PLAYER, Optional.empty(), CASE_ONE, 0L, originalChecksum);
        assertEquals(ReputationMutationResult.Status.STALE_REPUTATION, staleApply.status());
        assertTrue(service.canGiveReputation(PLAYER));

        ReputationMutationResult applied = service.applyBlacklist(
                UUID.randomUUID(), PLAYER, Optional.empty(), CASE_ONE, 0L, state.get().checksum());
        assertEquals(ReputationMutationResult.Status.APPLIED, applied.status());
        long revision = applied.blacklist().orElseThrow().revision();

        ReputationMutationResult staleApplyRevision = service.applyBlacklist(
                UUID.randomUUID(), PLAYER, Optional.empty(), "case-new", 0L, state.get().checksum());
        assertEquals(ReputationMutationResult.Status.STALE_BLACKLIST, staleApplyRevision.status());
        assertEquals(CASE_ONE, service.getBlacklist(PLAYER).orElseThrow().caseId());

        ReputationMutationResult staleRevision = service.removeBlacklist(
                UUID.randomUUID(), PLAYER, "case-2", revision + 1L, state.get().checksum());
        assertEquals(ReputationMutationResult.Status.STALE_BLACKLIST, staleRevision.status());
        assertFalse(service.canGiveReputation(PLAYER));
        assertEquals(revision, service.getBlacklist(PLAYER).orElseThrow().revision());
    }

    @SuppressWarnings("PMD.DoNotUseThreads")
    @Test
    void concurrentApplyWithSameRevisionAllowsOnlyOneCommit() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        AtomicReference<ReputationStateSnapshot> state = new AtomicReference<>(snapshot(7));
        ReputationModerationService service = new ReputationModerationService(
                clock, ignored -> state.get(), temporaryDirectory.resolve("concurrent-state.yml"));
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ReputationMutationResult.Status> first = executor.submit(() -> {
                start.await();
                return service.applyBlacklist(
                        UUID.randomUUID(), PLAYER, Optional.empty(), "case-a", 0L, state.get().checksum()).status();
            });
            Future<ReputationMutationResult.Status> second = executor.submit(() -> {
                start.await();
                return service.applyBlacklist(
                        UUID.randomUUID(), PLAYER, Optional.empty(), "case-b", 0L, state.get().checksum()).status();
            });

            List<ReputationMutationResult.Status> statuses = List.of(first.get(), second.get());
            assertEquals(1L, statuses.stream()
                    .filter(status -> status == ReputationMutationResult.Status.APPLIED)
                    .count());
            assertEquals(1L, statuses.stream()
                    .filter(status -> status == ReputationMutationResult.Status.STALE_BLACKLIST)
                    .count());
            assertEquals(1L, service.getBlacklist(PLAYER).orElseThrow().revision());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedDurableWriteDoesNotPublishInMemoryBlacklist() throws Exception {
        Path blockedParent = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(blockedParent, "blocking file");
        AtomicReference<ReputationStateSnapshot> state = new AtomicReference<>(snapshot(7));
        ReputationModerationService service = new ReputationModerationService(
                Clock.fixed(NOW, ZoneOffset.UTC),
                ignored -> state.get(),
                blockedParent.resolve("moderation-state.yml")
        );

        assertThrows(IllegalStateException.class, () -> service.applyBlacklist(
                UUID.randomUUID(), PLAYER, Optional.empty(), "case-write-failure", 0L, state.get().checksum()));

        assertTrue(service.canGiveReputation(PLAYER));
        assertTrue(service.getBlacklist(PLAYER).isEmpty());
    }

    @Test
    void operationIdReuseWithDifferentInputIsRejected() {
        MutableClock clock = new MutableClock(NOW);
        AtomicReference<ReputationStateSnapshot> state = new AtomicReference<>(snapshot(7));
        ReputationModerationService service = new ReputationModerationService(
                clock, ignored -> state.get(), temporaryDirectory.resolve(STATE_FILE));
        UUID operationId = UUID.randomUUID();
        assertEquals(ReputationMutationResult.Status.APPLIED, service.applyBlacklist(
                operationId, PLAYER, Optional.empty(), "case-a", 0L, state.get().checksum()).status());

        ReputationMutationResult rejected = service.applyBlacklist(
                operationId, PLAYER, Optional.empty(), "case-b", 0L, state.get().checksum());
        assertEquals(ReputationMutationResult.Status.REJECTED, rejected.status());
        assertEquals("case-a", service.getBlacklist(PLAYER).orElseThrow().caseId());
        assertEquals(1L, service.getBlacklist(PLAYER).orElseThrow().revision());
    }

    @Test
    void temporaryRestrictionExpiresWithoutChangingStoredReputation() {
        MutableClock clock = new MutableClock(NOW);
        AtomicReference<ReputationStateSnapshot> state = new AtomicReference<>(snapshot(7));
        ReputationModerationService service = new ReputationModerationService(
                clock, ignored -> state.get(), temporaryDirectory.resolve(STATE_FILE));
        service.applyBlacklist(UUID.randomUUID(), PLAYER, Optional.of(NOW.plus(Duration.ofHours(2))),
                "case-temp", 0L, state.get().checksum());
        assertFalse(service.canGiveReputation(PLAYER));

        clock.advance(Duration.ofHours(3));
        assertTrue(service.canGiveReputation(PLAYER));
        assertEquals(ReputationBlacklist.Status.EXPIRED, service.getBlacklist(PLAYER).orElseThrow().status());
        assertEquals(state.get(), service.snapshot(PLAYER));
    }

    @Test
    void corruptDurableStateBlocksProviderRestart() throws Exception {
        Path file = temporaryDirectory.resolve(STATE_FILE);
        AtomicReference<ReputationStateSnapshot> state = new AtomicReference<>(snapshot(7));
        ReputationModerationService service = new ReputationModerationService(
                Clock.fixed(NOW, ZoneOffset.UTC), ignored -> state.get(), file);
        service.applyBlacklist(UUID.randomUUID(), PLAYER, Optional.empty(), CASE_ONE, 0L, state.get().checksum());
        Files.writeString(file, "blacklists:\n  invalid: [\n");

        assertThrows(IllegalStateException.class, () -> new ReputationModerationService(
                Clock.fixed(NOW, ZoneOffset.UTC), ignored -> state.get(), file));
    }

    private static ReputationStateSnapshot snapshot(int total) {
        List<ReputationEntrySnapshot> entries = List.of(new ReputationEntrySnapshot(
                GIVER, PLAYER, true, "HELPFUL", 2, 1_000L, 2_000L));
        return new ReputationStateSnapshot(
                PLAYER,
                total,
                entries,
                ReputationSnapshotFactory.checksum(PLAYER, total, entries)
        );
    }

    private static final class MutableClock extends Clock {
        private Instant currentInstant;

        private MutableClock(Instant instant) {
            this.currentInstant = instant;
        }

        private void advance(Duration duration) {
            currentInstant = currentInstant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock is UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}
