package org.legacy.aegis.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.legacy.aegis.Aegis;

/**
 * Manages the plugin's configuration file with Hot-Reload support.
 * All check thresholds and system settings are pulled from config.yml.
 */
public class ConfigManager {

    private final Aegis plugin;
    private FileConfiguration config;

    public ConfigManager(Aegis plugin) {
        this.plugin = plugin;
    }

    /**
     * Load or reload configuration from disk.
     */
    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
    }

    // ─── General ────────────────────────────────────────────────

    public String getPrefix() {
        return config.getString("general.prefix", "&8[&c&lAegis&8] &7");
    }

    public boolean isDebug() {
        return config.getBoolean("general.debug", false);
    }

    // ─── Database ───────────────────────────────────────────────

    public String getDatabaseType() {
        return config.getString("database.type", "SQLITE").toUpperCase();
    }

    public String getSqliteFile() {
        return config.getString("database.sqlite-file", "aegis_data.db");
    }

    public String getMongoUri() {
        return config.getString("database.mongodb.uri", "mongodb://localhost:27017");
    }

    public String getMongoDatabase() {
        return config.getString("database.mongodb.database", "aegis");
    }

    public boolean isCleanupEnabled() {
        return config.getBoolean("database.cleanup.enabled", true);
    }

    public int getCleanupMaxAgeDays() {
        return config.getInt("database.cleanup.max-age-days", 30);
    }

    public int getCleanupIntervalHours() {
        return config.getInt("database.cleanup.interval-hours", 24);
    }

    // ─── Trust Factor ───────────────────────────────────────────

    public boolean isTrustFactorEnabled() {
        return config.getBoolean("trust-factor.enabled", true);
    }

    public double getPlaytimeWeight() {
        return config.getDouble("trust-factor.playtime-weight", 0.5);
    }

    public double getViolationPenalty() {
        return config.getDouble("trust-factor.violation-penalty", 2.0);
    }

    public double getBaseScore() {
        return config.getDouble("trust-factor.base-score", 5.0);
    }

    public double getMaxScore() {
        return config.getDouble("trust-factor.max-score", 100.0);
    }

    public double getTrustedThresholdMultiplier() {
        return config.getDouble("trust-factor.trusted-threshold-multiplier", 1.5);
    }

    public double getTrustedMinimum() {
        return config.getDouble("trust-factor.trusted-minimum", 30.0);
    }

    // ─── Alerts ─────────────────────────────────────────────────

    public boolean isAlertsEnabled() {
        return config.getBoolean("alerts.enabled", true);
    }

    public int getMinVlAlert() {
        return config.getInt("alerts.min-vl-alert", 3);
    }

    public int getAlertCooldownTicks() {
        return config.getInt("alerts.cooldown-ticks", 40);
    }

    public String getDiscordWebhook() {
        return config.getString("alerts.discord-webhook", "");
    }

    // ─── Punishment ─────────────────────────────────────────────

    public boolean isPunishmentEnabled() {
        return config.getBoolean("punishment.enabled", true);
    }

    public String getBanCommand() {
        return config.getString("punishment.ban-command", "ban %player% [Aegis] Unfair Advantage");
    }

    public String getKickCommand() {
        return config.getString("punishment.kick-command", "kick %player% [Aegis] Suspicious Activity");
    }

    public boolean isAutoUnbanEnabled() {
        return config.getBoolean("punishment.auto-unban.enabled", true);
    }

    public int getAutoUnbanMaxBans() {
        return config.getInt("punishment.auto-unban.max-trusted-bans", 3);
    }

    public int getAutoUnbanTimeWindow() {
        return config.getInt("punishment.auto-unban.time-window-minutes", 10);
    }

    public String getUnbanCommand() {
        return config.getString("punishment.unban-command", "unban %player%");
    }

    // ─── Exemptions ─────────────────────────────────────────────

    public boolean isBedrockExempt() {
        return config.getBoolean("exemptions.bedrock-players", true);
    }

    public String getBypassPermission() {
        return config.getString("exemptions.bypass-permission", "aegis.bypass");
    }

    public int getKnockbackGraceTicks() {
        return config.getInt("exemptions.knockback-grace-ticks", 25);
    }

    public int getHighPingThreshold() {
        return config.getInt("exemptions.high-ping-threshold", 300);
    }

    public int getTeleportGraceTicks() {
        return config.getInt("exemptions.teleport-grace-ticks", 10);
    }

    public double getLagTpsThreshold() {
        return config.getDouble("exemptions.lag-tps-threshold", 18.0);
    }

    public double getLagDisableThreshold() {
        return config.getDouble("exemptions.lag-disable-threshold", 14.0);
    }

    // ─── Evidence ───────────────────────────────────────────────

    public boolean isEvidenceEnabled() {
        return config.getBoolean("evidence.enabled", true);
    }

    public int getEvidenceBufferSize() {
        return config.getInt("evidence.buffer-size-ticks", 200);
    }

    public boolean isSaveOnBan() {
        return config.getBoolean("evidence.save-on-ban", true);
    }

    // ─── Check Configurations ───────────────────────────────────

    public boolean isCheckEnabled(String checkPath) {
        return config.getBoolean("checks." + checkPath + ".enabled", true);
    }

    public int getCheckMaxVl(String checkPath) {
        return config.getInt("checks." + checkPath + ".max-vl", 10);
    }

    public String getCheckPunishment(String checkPath) {
        return config.getString("checks." + checkPath + ".punishment", "KICK");
    }

    public double getCheckDouble(String checkPath, String key, double def) {
        return config.getDouble("checks." + checkPath + "." + key, def);
    }

    public int getCheckInt(String checkPath, String key, int def) {
        return config.getInt("checks." + checkPath + "." + key, def);
    }

    public FileConfiguration raw() {
        return config;
    }
}
