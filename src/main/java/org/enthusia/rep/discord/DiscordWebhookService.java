package org.enthusia.rep.discord;

import org.enthusia.rep.rep.RepCategory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bounded, single-threaded Discord webhook sender. All Bukkit-dependent values
 * must be resolved before {@link #log(LogEntry)} is called.
 */
public final class DiscordWebhookService implements AutoCloseable {
    private static final int QUEUE_CAPACITY = 256;

    private final URI webhookUri;
    private final Logger logger;
    private final ThreadPoolExecutor executor;
    private final HttpClient client;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public DiscordWebhookService(String webhookUrl, Logger logger) {
        this.logger = logger;
        this.webhookUri = validate(webhookUrl);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "EnthusiaCommend-Discord");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                factory,
                (runnable, ignored) -> logger.warning("Discord commendation log queue is full; dropping one log entry.")
        );
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean isEnabled() {
        return webhookUri != null && !closed.get();
    }

    public void log(LogEntry entry) {
        if (!isEnabled() || entry == null) {
            return;
        }
        executor.execute(() -> send(entry));
    }

    private void send(LogEntry entry) {
        if (closed.get()) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(webhookUri)
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(entry), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                logger.warning("Discord commendation webhook returned HTTP " + status + ".");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Failed to send Discord commendation webhook: " + exception.getMessage());
        }
    }

    private String toJson(LogEntry entry) {
        int color = entry.scoreValue() > 0 ? 0x57F287 : 0xED4245;
        String title = entry.action().displayName + " Reputation";
        return "{\"embeds\":[{"
                + "\"title\":\"" + escape(title) + "\","
                + "\"color\":" + color + ","
                + "\"fields\":["
                + field("Action", entry.action().displayName, true) + ","
                + field("From", entry.giverName(), true) + ","
                + field("To", entry.targetName(), true) + ","
                + field("Value", signed(entry.scoreValue()), true) + ","
                + field("New Total", Integer.toString(entry.newTotal()), true) + ","
                + field("Category", formatCategory(entry.category()), true) + ","
                + field("Reason", truncate(entry.reason(), 1024), false)
                + "],"
                + "\"timestamp\":\"" + entry.timestamp().toString() + "\""
                + "}]}";
    }

    private static String field(String name, String value, boolean inline) {
        return "{\"name\":\"" + escape(name) + "\",\"value\":\"" + escape(value) + "\",\"inline\":" + inline + "}";
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    private static String formatCategory(RepCategory category) {
        String[] words = category.migratedCategory().name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder output = new StringBuilder();
        for (String word : words) {
            if (output.length() > 0) {
                output.append(' ');
            }
            output.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return output.toString();
    }

    private static String truncate(String value, int maxLength) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength - 3) + "...";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static URI validate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(raw.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public record LogEntry(
            Action action,
            String giverName,
            String targetName,
            RepCategory category,
            String reason,
            int scoreValue,
            int newTotal,
            Instant timestamp
    ) {
    }

    public enum Action {
        CREATED("Created"),
        UPDATED("Updated"),
        REMOVED("Removed"),
        RESTORED("Restored");

        private final String displayName;

        Action(String displayName) {
            this.displayName = displayName;
        }
    }
}
