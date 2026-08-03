package org.enthusia.rep.discord;

import org.enthusia.rep.rep.RepCategory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Bounded asynchronous Discord webhook sender with no Bukkit or skin-network work. */
public final class DiscordWebhookService implements AutoCloseable {
    private static final int QUEUE_CAPACITY = 256;
    private static final long FAILURE_LOG_INTERVAL_MILLIS = 60_000L;
    private static final int DESCRIPTION_LIMIT = 4096;

    private final URI webhookUri;
    private final Logger logger;
    private final ThreadPoolExecutor executor;
    private final HttpClient client;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong lastFailureLogAt = new AtomicLong(0L);

    public DiscordWebhookService(String webhookUrl, Logger logger) {
        this.logger = logger;
        this.webhookUri = validate(webhookUrl);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "EnthusiaCommend-Discord");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), factory,
                (runnable, ignored) -> warnRateLimited("Discord reputation log queue is full; dropping one log entry.", null));
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public boolean isEnabled() { return webhookUri != null && !closed.get(); }

    public void log(LogEntry entry) {
        if (!isEnabled() || entry == null) return;
        executor.execute(() -> send(entry));
    }

    private void send(LogEntry entry) {
        try {
            HttpRequest request = HttpRequest.newBuilder(webhookUri)
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(entry), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                warnRateLimited("Discord reputation webhook returned HTTP " + response.statusCode() + ".", null);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            warnRateLimited("Failed to send Discord reputation webhook: " + exception.getMessage(), exception);
        }
    }

    private void warnRateLimited(String message, Exception exception) {
        long now = System.currentTimeMillis();
        long previous = lastFailureLogAt.get();
        if (now - previous < FAILURE_LOG_INTERVAL_MILLIS || !lastFailureLogAt.compareAndSet(previous, now)) return;
        if (exception == null) logger.warning(message); else logger.log(Level.WARNING, message, exception);
    }

    static String toJson(LogEntry entry) {
        Action action = entry.action() == null ? Action.CREATED : entry.action();
        String actor = singleLine(entry.actorName(), 64, "Administrator");
        String giver = singleLine(entry.giverName(), 64, "Unknown player");
        String target = singleLine(entry.targetName(), 64, "Unknown player");
        String category = singleLine(entry.category() == null ? "Reputation" : entry.category().displayName(), 128, "Reputation");
        String reason = singleLine(entry.reason(), 3500, "");
        String firstLine = switch (action) {
            case CREATED, UPDATED -> giver + " repped " + target;
            case REMOVED -> actor + " removed reputation from " + target;
            case RESTORED -> actor + " restored reputation for " + target;
        };
        String secondLine = reason.isBlank() ? category : category + " • " + reason;
        String description = truncate(firstLine + "\n" + secondLine, DESCRIPTION_LIMIT);
        int color = entry.category() != null && !entry.category().isPositive() ? 0xED4245 : 0x57F287;
        String thumbnail = entry.thumbnailUrl() == null || entry.thumbnailUrl().isBlank()
                ? "" : ",\"thumbnail\":{\"url\":\"" + escape(entry.thumbnailUrl()) + "\"}";
        return "{\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{"
                + "\"description\":\"" + escape(description) + "\","
                + "\"color\":" + color + ","
                + "\"timestamp\":\"" + (entry.timestamp() == null ? Instant.now() : entry.timestamp()) + "\""
                + thumbnail + "}]}";
    }

    private static String singleLine(String value, int maxLength, String fallback) {
        if (value == null) return fallback;
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        if (cleaned.isBlank()) return fallback;
        return truncate(cleaned, maxLength);
    }

    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) return value;
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    static String escape(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\r' -> escaped.append("\\r");
                case '\n' -> escaped.append("\\n");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.toString();
    }

    private static URI validate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = URI.create(raw.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null ? uri : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public record LogEntry(Action action, String actorName, String giverName, String targetName,
                           RepCategory category, String reason, Instant timestamp, String thumbnailUrl) {
        public LogEntry(String giverName, String targetName, RepCategory category,
                        String reason, Instant timestamp, String thumbnailUrl) {
            this(Action.CREATED, giverName, giverName, targetName, category, reason, timestamp, thumbnailUrl);
        }
    }

    public enum Action {
        CREATED,
        UPDATED,
        REMOVED,
        RESTORED
    }
}
