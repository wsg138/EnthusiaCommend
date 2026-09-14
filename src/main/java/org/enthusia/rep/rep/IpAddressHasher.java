package org.enthusia.rep.rep;

import org.enthusia.rep.CommendPlugin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Generates non-reversible, server-keyed identifiers for player IP addresses. */
public final class IpAddressHasher {
    public static final String PREFIX = "h1:";
    private static final String ALGORITHM = "HmacSHA256";
    private static final String KEY_FILE_NAME = "ip-hmac.key";
    private static final int KEY_BYTES = 32;
    private static final int IDENTIFIER_BYTES = 16;
    private static final byte[] TEST_KEY = "EnthusiaCommend-test-HMAC-key-only".getBytes(StandardCharsets.UTF_8);

    private final byte[] key;

    private IpAddressHasher(byte[] key) {
        this.key = key.clone();
    }

    static IpAddressHasher forPlugin(CommendPlugin plugin) {
        Path dataFolder = plugin.getDataFolder() == null ? null : plugin.getDataFolder().toPath();
        if (dataFolder == null) {
            return new IpAddressHasher(TEST_KEY);
        }
        return loadOrCreate(dataFolder, plugin.getLogger());
    }

    static IpAddressHasher loadOrCreate(Path dataFolder, Logger logger) {
        Path keyFile = dataFolder.resolve(KEY_FILE_NAME);
        try {
            Files.createDirectories(dataFolder);
            byte[] loaded = Files.exists(keyFile) ? readKey(keyFile) : createKey(keyFile);
            protectKeyFile(keyFile, logger);
            return new IpAddressHasher(loaded);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to initialize the private IP HMAC key.", exception);
        }
    }

    private static byte[] createKey(Path keyFile) throws IOException {
        byte[] generated = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(generated);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(generated);
        try {
            Files.writeString(keyFile, encoded + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            return generated;
        } catch (FileAlreadyExistsException ignored) {
            return readKey(keyFile);
        }
    }

    private static byte[] readKey(Path keyFile) throws IOException {
        byte[] decoded = Base64.getUrlDecoder().decode(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
        if (decoded.length < KEY_BYTES) {
            throw new IllegalArgumentException("IP HMAC key must contain at least 256 bits.");
        }
        return decoded;
    }

    private static void protectKeyFile(Path keyFile, Logger logger) {
        try {
            Files.setPosixFilePermissions(keyFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows and some filesystems do not expose POSIX permissions.
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Could not restrict ip-hmac.key permissions; protect this file manually.", exception);
        }
    }

    public String hash(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            byte[] digest = mac.doFinal(ipAddress.getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest, 0, IDENTIFIER_BYTES);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Required HMAC-SHA256 support is unavailable.", exception);
        }
    }

    public static boolean isProtectedIdentifier(String value) {
        if (value == null || value.length() != PREFIX.length() + IDENTIFIER_BYTES * 2 || !value.startsWith(PREFIX)) {
            return false;
        }
        try {
            HexFormat.of().parseHex(value.substring(PREFIX.length()));
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
