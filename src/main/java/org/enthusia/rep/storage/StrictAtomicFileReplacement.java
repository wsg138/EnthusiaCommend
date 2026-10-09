package org.enthusia.rep.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Strict storage primitive for a future atomic correction-state + receipt file.
 *
 * <p>NOT wired into production. It only writes already-serialized bytes. The
 * caller must acquire a provider-wide lock and build a complete, validated
 * state with a durable receipt in those same bytes. Unlike ordinary autosaves,
 * an unsupported atomic move or directory fsync must fail; there is no fallback.
 *
 * <p>If a failure occurs after rename, the new bytes MAY already be present:
 * an exception must trigger readback/reconciliation and never be interpreted
 * as proof of rollback.</p>
 */
final class StrictAtomicFileReplacement {
    private StrictAtomicFileReplacement() {
    }

    static void replace(Path target, byte[] bytes) throws IOException {
        replace(target, bytes, checkpoint -> { });
    }

    static void replace(Path target, byte[] bytes, FailureProbe failureProbe) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(failureProbe, "failureProbe");
        Path primary = target.toAbsolutePath();
        Path parent = primary.getParent();
        if (parent == null || !Files.isDirectory(parent)
                || Files.isSymbolicLink(primary) || Files.isSymbolicLink(parent)) {
            throw new IOException("Atomic replacement requires an existing, non-symlink data directory");
        }

        Path temporary = Files.createTempFile(parent, "." + primary.getFileName() + "-commit-", ".tmp");
        try {
            failureProbe.hit(Checkpoint.BEFORE_TEMP_WRITE);
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            failureProbe.hit(Checkpoint.AFTER_TEMP_FSYNC);
            Files.move(temporary, primary,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            failureProbe.hit(Checkpoint.AFTER_ATOMIC_MOVE);
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
                directory.force(true);
            }
            failureProbe.hit(Checkpoint.AFTER_DIRECTORY_FSYNC);
        } finally {
            // After a successful rename this no longer exists. If an earlier
            // step fails, only the uniquely created staging file is removed.
            Files.deleteIfExists(temporary);
        }
    }

    enum Checkpoint {
        BEFORE_TEMP_WRITE,
        AFTER_TEMP_FSYNC,
        AFTER_ATOMIC_MOVE,
        AFTER_DIRECTORY_FSYNC
    }

    @FunctionalInterface
    interface FailureProbe {
        void hit(Checkpoint checkpoint) throws IOException;
    }
}
