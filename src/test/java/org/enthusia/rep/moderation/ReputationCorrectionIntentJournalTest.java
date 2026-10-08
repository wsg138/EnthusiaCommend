package org.enthusia.rep.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.rep.api.ReputationEntrySnapshot;
import org.enthusia.rep.api.ReputationStateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReputationCorrectionIntentJournalTest {
    private static final String JOURNAL_FILE = "corrections.yml";
    private static final String CASE_ID = "CASE-42";
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000221");
    private static final UUID REVIEWER = UUID.fromString("00000000-0000-0000-0000-000000000222");
    private static final UUID OPERATION = UUID.fromString("00000000-0000-0000-0000-000000000223");
    private static final UUID GIVER = UUID.fromString("00000000-0000-0000-0000-000000000224");
    private static final Instant TIME = Instant.parse("2026-10-08T18:00:00Z");
    private static final ReputationEntrySnapshot ENTRY = new ReputationEntrySnapshot(
            GIVER, SUBJECT, true, "HELPFUL", 2, 100L, 200L);

    @TempDir
    Path folder;

    @Test
    void preparedIntentSurvivesRestartAndReplaysOriginalTimestamp() {
        Path file = folder.resolve(JOURNAL_FILE);
        ReputationStateSnapshot before = snapshot(9, List.of(ENTRY));
        ReputationCorrectionIntentJournal store = new ReputationCorrectionIntentJournal(file);
        var first = store.prepare(OPERATION, REVIEWER, CASE_ID, before,
                SUBJECT, before.checksum(), List.of(ENTRY), TIME);

        assertEquals(7, first.expectedTotalAfterRemoval());
        assertEquals(List.of(ENTRY), first.exactEntries());
        assertEquals(before, first.before());
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(folder.resolve("corrections.yml.tmp")));

        ReputationCorrectionIntentJournal rebooted = new ReputationCorrectionIntentJournal(file);
        assertEquals(first, rebooted.findOperation(OPERATION).orElseThrow());
        assertEquals(first, rebooted.prepare(OPERATION, REVIEWER, CASE_ID, before,
                SUBJECT, before.checksum(), List.of(ENTRY), TIME.plusSeconds(30)));
        assertEquals(TIME, rebooted.findOperation(OPERATION).orElseThrow().preparedAt());
        // Preparing is not a mutation: the provider's original score and entry remain untouched.
        assertEquals(9, before.totalScore());
        assertEquals(List.of(ENTRY), before.entries());
    }

    @Test
    void sameOperationIdCannotBeRetargetedAcrossCasePlayerOrEntry() {
        Path file = folder.resolve(JOURNAL_FILE);
        var before = snapshot(9, List.of(ENTRY));
        var store = new ReputationCorrectionIntentJournal(file);
        store.prepare(OPERATION, REVIEWER, CASE_ID, before,
                SUBJECT, before.checksum(), List.of(ENTRY), TIME);

        assertThrows(IllegalStateException.class, () -> store.prepare(
                OPERATION, REVIEWER, "OTHER-CASE", before, SUBJECT,
                before.checksum(), List.of(ENTRY), TIME));
        assertThrows(IllegalStateException.class, () -> store.prepare(
                OPERATION, GIVER, CASE_ID, before, SUBJECT,
                before.checksum(), List.of(ENTRY), TIME));
        assertThrows(IllegalStateException.class, () -> store.prepare(
                OPERATION, REVIEWER, CASE_ID, snapshot(10, List.of(ENTRY)), SUBJECT,
                before.checksum(), List.of(ENTRY), TIME));
        assertThrows(IllegalStateException.class, () -> store.prepare(
                OPERATION, REVIEWER, CASE_ID, before, SUBJECT,
                before.checksum(), List.of(), TIME));
        assertEquals(7, new ReputationCorrectionIntentJournal(file)
                .findOperation(OPERATION).orElseThrow().expectedTotalAfterRemoval());
    }

    @Test
    void rejectsUnverifiedForeignAndStaleIntentBeforeDiskWrite() {
        var store = new ReputationCorrectionIntentJournal(folder.resolve(JOURNAL_FILE));
        var before = snapshot(9, List.of(ENTRY));
        assertThrows(IllegalStateException.class, () -> store.prepare(
                OPERATION, REVIEWER, CASE_ID, before, SUBJECT,
                "0".repeat(64), List.of(ENTRY), TIME));
        assertThrows(IllegalArgumentException.class, () -> store.prepare(
                OPERATION, REVIEWER, "CASE WITH SPACE", before, SUBJECT,
                before.checksum(), List.of(ENTRY), TIME));
        assertThrows(IllegalArgumentException.class, () -> store.prepare(
                OPERATION, REVIEWER, CASE_ID, before, GIVER,
                before.checksum(), List.of(ENTRY), TIME));
        assertTrue(store.findOperation(OPERATION).isEmpty());
        assertFalse(Files.exists(folder.resolve(JOURNAL_FILE)));
    }

    @Test
    void malformedOrTamperedDurableIntentFailsClosedOnRestart() throws Exception {
        Path file = folder.resolve(JOURNAL_FILE);
        var before = snapshot(9, List.of(ENTRY));
        new ReputationCorrectionIntentJournal(file).prepare(
                OPERATION, REVIEWER, CASE_ID, before, SUBJECT,
                before.checksum(), List.of(ENTRY), TIME);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        String root = "prepared-intents." + OPERATION;
        yaml.set(root + ".expected-total", 400);
        yaml.save(file.toFile());
        assertThrows(IllegalStateException.class, () -> new ReputationCorrectionIntentJournal(file));

        yaml.set(root + ".expected-total", 7);
        yaml.set(root + ".before.total-score", 99);
        yaml.save(file.toFile());
        assertThrows(IllegalStateException.class, () -> new ReputationCorrectionIntentJournal(file));
    }

    @Test
    void existingRootlessJournalCannotBeSilentlyReinitialized() throws Exception {
        Path file = folder.resolve(JOURNAL_FILE);
        Files.writeString(file, "# journal was truncated; original operations are unknown\n");
        assertThrows(IllegalStateException.class, () -> new ReputationCorrectionIntentJournal(file));
        assertEquals("# journal was truncated; original operations are unknown\n", Files.readString(file));
    }

    @Test
    void missingPreparedMetadataFailsClosedEvenWhenExpectedScoreWouldBeZero() throws Exception {
        Path file = folder.resolve(JOURNAL_FILE);
        var before = snapshot(2, List.of(ENTRY)); // expected total after selecting ENTRY is exactly zero.
        new ReputationCorrectionIntentJournal(file).prepare(
                OPERATION, REVIEWER, CASE_ID, before, SUBJECT,
                before.checksum(), List.of(ENTRY), TIME);
        String original = Files.readString(file);
        String root = "prepared-intents." + OPERATION;
        for (String key : List.of("reviewer-id", "expected-total", "prepared-at")) {
            Files.writeString(file, original);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file.toFile());
            yaml.set(root + "." + key, null);
            yaml.save(file.toFile());
            assertThrows(IllegalStateException.class, () -> new ReputationCorrectionIntentJournal(file),
                    "Missing " + key + " must not be silently defaulted");
        }
    }

    @Test
    void storageFailureDoesNotAdvertisePreparedIntent() {
        Path blockedParent = folder.resolve("file-not-directory");
        try {
            Files.writeString(blockedParent, "cannot create a directory beneath a normal file");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
        Path file = blockedParent.resolve("corrections.yml");
        var before = snapshot(9, List.of(ENTRY));
        var journal = new ReputationCorrectionIntentJournal(file);
        assertThrows(IllegalStateException.class, () -> journal.prepare(
                OPERATION, REVIEWER, CASE_ID, before, SUBJECT,
                before.checksum(), List.of(ENTRY), TIME));
        assertTrue(journal.findOperation(OPERATION).isEmpty());
    }

    private static ReputationStateSnapshot snapshot(int totalScore, List<ReputationEntrySnapshot> entries) {
        return new ReputationStateSnapshot(SUBJECT, totalScore, entries,
                ReputationSnapshotFactory.checksum(SUBJECT, totalScore, entries));
    }
}
