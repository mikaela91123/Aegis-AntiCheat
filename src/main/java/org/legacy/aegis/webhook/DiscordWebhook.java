package org.legacy.aegis.webhook;

import org.bukkit.Bukkit;
import org.legacy.aegis.Aegis;
import org.legacy.aegis.scheduler.FoliaScheduler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public class DiscordWebhook {

    private final Aegis plugin;

    public DiscordWebhook(Aegis plugin) {
        this.plugin = plugin;
    }

    public void sendAlert(String playerName, String checkName, int vl, double trustFactor, int ping, double tps) {
        String webhookUrl = plugin.getConfigManager().getDiscordWebhook();
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        String json = buildEmbed("⚠️ Aegis Detection Alert", 0xFF6600, new String[][]{
                {"Player", playerName, "true"},
                {"Check", checkName, "true"},
                {"VL", String.valueOf(vl), "true"},
                {"Trust Factor", String.format("%.1f", trustFactor), "true"},
                {"Ping", ping + "ms", "true"},
                {"TPS", String.format("%.1f", tps), "true"}
        });
        sendAsync(webhookUrl, json, 1);
    }

    public void sendPunishment(String playerName, String checkName, int vl, double trustFactor, int ping, String action, String evidenceId) {
        String webhookUrl = plugin.getConfigManager().getDiscordWebhook();
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        int color = action.equals("BAN") ? 0xFF0000 : 0xFFAA00;
        String emoji = action.equals("BAN") ? "🔨" : "👢";

        String json = buildEmbed(emoji + " Aegis Punishment — " + action, color, new String[][]{
                {"Player", playerName, "true"},
                {"Check", checkName, "true"},
                {"Action", action, "true"},
                {"VL", String.valueOf(vl), "true"},
                {"Trust Factor", String.format("%.1f", trustFactor), "true"},
                {"Ping", ping + "ms", "true"},
                {"Evidence ID", evidenceId != null ? evidenceId : "N/A", "false"}
        });
        sendAsync(webhookUrl, json, 1);
    }

    public void sendAutoUnbanWarning(String checkName, int trustedBanCount, int timeWindowMinutes) {
        String webhookUrl = plugin.getConfigManager().getDiscordWebhook();
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        String json = buildEmbed("🚨 Auto-Unban Triggered!", 0xFF0000, new String[][]{
                {"Check Disabled", checkName, "true"},
                {"Trusted Bans", String.valueOf(trustedBanCount), "true"},
                {"Time Window", timeWindowMinutes + " minutes", "true"},
                {"Action", "Check threshold raised to prevent false bans.", "false"}
        });
        sendAsync(webhookUrl, json, 1);
    }

    public void sendAppeal(String playerName, int appealId, int evidenceId, String reason) {
        String webhookUrl = plugin.getConfigManager().getDiscordWebhook();
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        String json = buildEmbed("📋 New Appeal Submitted", 0x3498DB, new String[][]{
                {"Player", playerName, "true"},
                {"Appeal ID", "#" + appealId, "true"},
                {"Evidence ID", "#" + evidenceId, "true"},
                {"Reason", reason.length() > 200 ? reason.substring(0, 200) + "..." : reason, "false"}
        });
        sendAsync(webhookUrl, json, 1);
    }

    private String buildEmbed(String title, int color, String[][] fields) {
        StringBuilder fieldsJson = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            fieldsJson.append(String.format(
                    "{\"name\":\"%s\",\"value\":\"%s\",\"inline\":%s}",
                    escapeJson(fields[i][0]), escapeJson(fields[i][1]), fields[i][2]));
            if (i < fields.length - 1) fieldsJson.append(",");
        }
        return String.format(
                "{\"embeds\":[{\"title\":\"%s\",\"color\":%d,\"fields\":[%s],\"footer\":{\"text\":\"Aegis Anti-Cheat v%s\"},\"timestamp\":\"%s\"}]}",
                escapeJson(title), color, fieldsJson, plugin.getDescription().getVersion(), java.time.Instant.now().toString());
    }

    private void sendAsync(String webhookUrl, String jsonPayload, int attemptsLeft) {
        if (attemptsLeft <= 0) return;
        FoliaScheduler.runAsync(plugin, () -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = connection.getResponseCode();
                if (responseCode == 429) {
                    String retryAfter = connection.getHeaderField("Retry-After");
                    long waitMs = retryAfter != null ? (long)(Double.parseDouble(retryAfter) * 1000) : 1000;
                    connection.disconnect();
                    try { Thread.sleep(Math.min(waitMs, 5000)); } catch (InterruptedException ignored) {}
                    sendAsync(webhookUrl, jsonPayload, attemptsLeft - 1);
                    return;
                }

                if (responseCode != 200 && responseCode != 204) {
                    plugin.getLogger().warning("Discord webhook returned code: " + responseCode);
                }
                connection.disconnect();
            } catch (IOException e) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().warning("Failed to send Discord webhook: " + e.getMessage());
                }
            }
        });
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
