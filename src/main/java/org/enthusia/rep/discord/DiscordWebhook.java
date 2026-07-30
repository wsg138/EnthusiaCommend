package org.enthusia.rep.discord;

import org.bukkit.Bukkit;
import org.enthusia.rep.rep.Commendation;
import org.enthusia.rep.rep.RepService;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends commendation logs to a Discord webhook asynchronously.
 */
public class DiscordWebhook {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("UTC"));

    private final String webhookUrl;
    private final RepService repService;
    private final Logger logger;

    public DiscordWebhook(String webhookUrl, RepService repService, Logger logger) {
        this.webhookUrl = webhookUrl;
        this.repService = repService;
        this.logger = logger;
    }

    public void logCommendation(Commendation c, int newScore) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            try {
                String giverName = repService.nameOf(c.getGiver());
                String targetName = repService.nameOf(c.getTarget());
                String sign = c.isPositive() ? "+" : "";
                int color = c.isPositive() ? 0x57F287 : 0xED4245; // Discord green / red

                String json = String.format(
                        "{\"embeds\":[{" +
                                "\"title\":\"%s Reputation\",\"color\":%d," +
                                "\"fields\":[" +
                                "{\"name\":\"From\",\"value\":\"`%s`\",\"inline\":true}," +
                                "{\"name\":\"To\",\"value\":\"`%s`\",\"inline\":true}," +
                                "{\"name\":\"Score\",\"value\":\"%s%d → **%d**\",\"inline\":true}," +
                                "{\"name\":\"Category\",\"value\":\"%s\",\"inline\":true}," +
                                "{\"name\":\"Reason\",\"value\":\"%s\",\"inline\":false}" +
                                "]," +
                                "\"footer\":{\"text\":\"%s UTC\"}," +
                                "\"timestamp\":\"%s\"" +
                                "}]}",
                        c.isPositive() ? "Positive" : "Negative",
                        color,
                        escapeJson(giverName),
                        escapeJson(targetName),
                        sign, c.isPositive() ? 1 : -2, newScore,
                        escapeJson(formatCategory(c)),
                        escapeJson(truncate(c.getReasonText(), 1024)),
                        DATE_FMT.format(Instant.now()),
                        Instant.now().toString()
                );

                HttpURLConnection conn = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code != 200 && code != 204) {
                    logger.warning("Discord webhook returned HTTP " + code);
                }
                conn.disconnect();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to send Discord webhook: " + e.getMessage());
            }
        });
    }

    private static String formatCategory(Commendation c) {
        String name = c.getCategory().name().replace('_', ' ').toLowerCase();
        // Capitalize words
        StringBuilder sb = new StringBuilder();
        for (String word : name.split(" ")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
