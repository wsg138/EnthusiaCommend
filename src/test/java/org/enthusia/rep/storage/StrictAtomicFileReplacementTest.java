package org.enthusia.rep.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX) // Strict directory-channel fsync must be supported.
class StrictAtomicFileReplacementTest {
    private static final String DATA_FILE = "data.yml";
    private static final byte[] ORIGINAL = "original-snapshot".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLACEMENT = "replacement-with-receipt".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path directory;

    @Test
    void replacesOneCompleteFileAndRemovesAllStagingData() throws IOException {
        Path primary = directory.resolve(DATA_FILE);
        Files.write(primary, ORIGINAL);

        StrictAtomicFileReplacement.replace(primary, REPLACEMENT);

        assertArrayEquals(REPLACEMENT, Files.readAllBytes(primary));
        assertNoStagingFiles();
    }

    @Test
    void failedTemporaryWriteOrFsyncCannotModifyPrimary() throws IOException {
        Path primary = directory.resolve(DATA_FILE);
        for (var checkpoint : new StrictAtomicFileReplacement.Checkpoint[] {
                StrictAtomicFileReplacement.Checkpoint.BEFORE_TEMP_WRITE,
                StrictAtomicFileReplacement.Checkpoint.AFTER_TEMP_FSYNC
        }) {
            Files.write(primary, ORIGINAL);
            assertThrows(IOException.class, () ->
                    StrictAtomicFileReplacement.replace(primary, REPLACEMENT, reached -> {
                        if (reached == checkpoint) {
                            throw new IOException("Injected before replacement");
                        }
                    }));
            assertArrayEquals(ORIGINAL, Files.readAllBytes(primary));
            assertNoStagingFiles();
        }
    }

    @Test
    void unsupportedAtomicMoveMustNotFallBackToNonAtomicOverwrite() throws IOException {
        Path primary = directory.resolve(DATA_FILE);
        Files.write(primary, ORIGINAL);

        assertThrows(AtomicMoveNotSupportedException.class, () ->
                StrictAtomicFileReplacement.replace(primary, REPLACEMENT, reached -> {
                    if (reached == StrictAtomicFileReplacement.Checkpoint.AFTER_TEMP_FSYNC) {
                        throw new AtomicMoveNotSupportedException("temporary", DATA_FILE,
                                "Injected unavailable atomic move");
                    }
                }));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(primary));
        assertNoStagingFiles();
    }

    @Test
    void postRenameFailureCannotBeTreatedAsGuaranteedRollback() throws IOException {
        Path primary = directory.resolve(DATA_FILE);
        Files.write(primary, ORIGINAL);

        assertThrows(IOException.class, () ->
                StrictAtomicFileReplacement.replace(primary, REPLACEMENT, reached -> {
                    if (reached == StrictAtomicFileReplacement.Checkpoint.AFTER_ATOMIC_MOVE) {
                        throw new IOException("Directory durability not confirmed");
                    }
                }));

        // Failure after rename can leave the new state persisted. The caller
        // MUST reconcile it and must never retry as though rollback were proven.
        assertArrayEquals(REPLACEMENT, Files.readAllBytes(primary));
        assertNoStagingFiles();
    }

    @Test
    void rejectMissingParentAndSymlinkTargetWithoutWriting() throws IOException {
        Path missingParent = directory.resolve("absent/data.yml");
        assertThrows(IOException.class,
                () -> StrictAtomicFileReplacement.replace(missingParent, REPLACEMENT));
        assertFalse(Files.exists(missingParent));

        Path original = directory.resolve("original.yml");
        Files.write(original, ORIGINAL);
        Path linked = directory.resolve("linked.yml");
        Files.createSymbolicLink(linked, original);
        assertThrows(IOException.class,
                () -> StrictAtomicFileReplacement.replace(linked, REPLACEMENT));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(original));
        assertTrue(Files.isSymbolicLink(linked));
        assertNoStagingFiles();
    }

    private void assertNoStagingFiles() throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().contains("-commit-")));
        }
    }
}
