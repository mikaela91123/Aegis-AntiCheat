package org.legacy.aegis.violation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.legacy.aegis.Aegis;
import org.legacy.aegis.check.Check;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.profile.PlayerProfile;
import org.legacy.aegis.profile.ProfileManager;
import org.legacy.aegis.scheduler.FoliaScheduler;
import org.legacy.aegis.webhook.DiscordWebhook;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles violation tracking, staff alerts, and punishment execution.
 */
public class ViolationManager {

    private final Aegis plugin;
    private final ConfigManager config;
    private final ProfileManager profileManager;
    private final DiscordWebhook discordWebhook;

    private final Map<String, Long> alertCooldowns = new ConcurrentHashMap<>();

    /**
     * Auto-unban: tracks how many trusted-player bans occurred per check.
     * Instead of disabling checks, we raise their effective VL threshold
     * by a multiplier to reduce false-positive punishment pressure.
     * Key = checkName, Value = confidence multiplier (1.0 = normal, 2.0 = twice as lenient)
     */
    private final Map<String, Double> checkConfidenceMultiplier = new ConcurrentHashMap<>();
    private final Map<String, java.util.Deque<Long>> trustedBanTracker = new ConcurrentHashMap<>();

    private final Map<String, Long> punishmentCooldowns = new ConcurrentHashMap<>();
    private static final long PUNISHMENT_COOLDOWN_MS = 30_000L;

    /**
     * Tracks the last time a setback was performed per player (UUID string).
     * Prevents rapid repeated setbacks caused by false-positive check loops.
     * Without this, a check that keeps returning true will set the player back
     * every ~100ms and flood the alert/setback system.
     */
    private final Map<String, Long> lastSetbackTime = new ConcurrentHashMap<>();
    private static final long SETBACK_COOLDOWN_MS = 1_000L; // 1 second between setbacks

    public ViolationManager(Aegis plugin, ConfigManager config,
                            ProfileManager profileManager, DiscordWebhook discordWebhook) {
        this.plugin = plugin;
        this.config = config;
        this.profileManager = profileManager;
        this.discordWebhook = discordWebhook;

        FoliaScheduler.runAsyncTimer(plugin, this::decayAll, 2400L, 2400L);
    }

    public void flag(Player player, PlayerProfile profile, Check check) {
        int vl = profile.addViolation(check.getName());

        double tf = profile.calculateTrustFactor(
                config.getPlaytimeWeight(),
                config.getViolationPenalty(),
                config.getBaseScore(),
                config.getMaxScore());

        int baseMaxVl = check.getMaxVl();

        // Apply trust-factor multiplier
        int effectiveMaxVl = profile.getEffectiveMaxVl(
                baseMaxVl,
                config.getTrustedThresholdMultiplier(),
                config.getTrustedMinimum());

        // Apply lag multiplier for non-combat checks
        double lagMultiplier = plugin.getExemptionManager().getLagMultiplier();
        if (check.getType() != org.legacy.aegis.check.CheckType.COMBAT) {
            effectiveMaxVl = (int) (effectiveMaxVl * lagMultiplier);
        }

        // Apply auto-unban confidence multiplier (raises threshold without disabling)
        double confidence = checkConfidenceMultiplier.getOrDefault(check.getName(), 1.0);
        effectiveMaxVl = (int) (effectiveMaxVl * confidence);

        // Cap at 3× base to prevent infinite leniency
        effectiveMaxVl = Math.min(effectiveMaxVl, baseMaxVl * 3);

        if (config.isAlertsEnabled() && vl >= config.getMinVlAlert()) {
            sendAlert(player, check, vl, tf);
        }

        String punishment = check.getPunishment().toUpperCase();

        // ── Punishment at max VL (100%) — checked FIRST so it always wins ──
        if (vl >= effectiveMaxVl && config.isPunishmentEnabled()) {
            String cooldownKey = player.getUniqueId() + ":" + check.getName();
            long now = System.currentTimeMillis();
            Long lastPunishment = punishmentCooldowns.get(cooldownKey);
            if (lastPunishment != null && now - lastPunishment < PUNISHMENT_COOLDOWN_MS) {
                profile.resetViolation(check.getName());
                return;
            }
            punishmentCooldowns.put(cooldownKey, now);
            profile.markThresholdReached(check.getName());
            executePunishment(player, profile, check, vl, tf);
            return;
        }

        // ── Early setback at 70-99% — MOVEMENT checks only ──
        // VL continues to accumulate so persistent cheaters reach kick threshold.
        if (punishment.equals("KICK")
                && check.getType() == org.legacy.aegis.check.CheckType.MOVEMENT
                && vl >= (int)(effectiveMaxVl * 0.7)
                && plugin.getRollbackManager() != null) {

            String setbackKey = player.getUniqueId().toString();
            long now = System.currentTimeMillis();
            Long lastSetback = lastSetbackTime.get(setbackKey);

            if (lastSetback != null && now - lastSetback < SETBACK_COOLDOWN_MS) {
                // Too soon — let VL accumulate normally so it reaches max faster
                return;
            }

            lastSetbackTime.put(setbackKey, now);

            String cooldownKey = player.getUniqueId() + ":" + check.getName();
            alertCooldowns.remove(cooldownKey);
            sendAlert(player, check, vl, tf);

            plugin.getLogger().warning("[Aegis-Setback] " + player.getName()
                    + " set back by " + check.getName()
                    + " (VL=" + vl + "/" + effectiveMaxVl + ")");

            plugin.getRollbackManager().setback(player, profile, 0);
        }
    }

    private void sendAlert(Player player, Check check, int vl, double trustFactor) {
        String cooldownKey = player.getUniqueId() + ":" + check.getName();
        long now = System.currentTimeMillis();
        Long lastAlert = alertCooldowns.get(cooldownKey);
        if (lastAlert != null && now - lastAlert < (config.getAlertCooldownTicks() * 50L)) {
            return;
        }
        alertCooldowns.put(cooldownKey, now);

        Component alert = Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text("Aegis", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .append(Component.text(player.getName(), NamedTextColor.WHITE))
                .append(Component.text(" failed ", NamedTextColor.GRAY))
                .append(Component.text(check.getName(), NamedTextColor.RED))
                .append(Component.text(" (x" + vl + ")", NamedTextColor.GRAY))
                .hoverEvent(HoverEvent.showText(
                        Component.text("Player: ", NamedTextColor.GRAY).append(Component.text(player.getName(), NamedTextColor.WHITE))
                                .append(Component.newline())
                                .append(Component.text("Check: ", NamedTextColor.GRAY).append(Component.text(check.getName(), NamedTextColor.RED)))
                                .append(Component.newline())
                                .append(Component.text("VL: ", NamedTextColor.GRAY).append(Component.text(String.valueOf(vl), NamedTextColor.YELLOW)))
                                .append(Component.newline())
                                .append(Component.text("TF: ", NamedTextColor.GRAY).append(Component.text(String.format("%.1f", trustFactor), NamedTextColor.AQUA)))
                                .append(Component.newline())
                                .append(Component.text("Ping: ", NamedTextColor.GRAY).append(Component.text(player.getPing() + "ms", NamedTextColor.WHITE)))
                                .append(Component.newline())
                                .append(Component.text("TPS: ", NamedTextColor.GRAY).append(Component.text(String.format("%.1f", Bukkit.getTPS()[0]), NamedTextColor.GREEN)))
                ))
                // Use suggestCommand to let admin decide — prevents accidental teleport
                .clickEvent(ClickEvent.suggestCommand("/tp " + player.getName()));

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("aegis.alerts")) {
                staff.sendMessage(alert);
            }
        }

        if (config.isDebug()) {
            plugin.getLogger().info("[DEBUG] " + player.getName() + " flagged " + check.getName()
                    + " VL=" + vl + " TF=" + String.format("%.1f", trustFactor) + " Ping=" + player.getPing());
        }

        discordWebhook.sendAlert(player.getName(), check.getName(), vl, trustFactor,
                player.getPing(), Bukkit.getTPS()[0]);
    }

    private void executePunishment(Player player, PlayerProfile profile, Check check, int vl, double trustFactor) {
        String punishment = check.getPunishment().toUpperCase();
        String checkName  = check.getName();

        // ── Auto-unban guard: raise check confidence multiplier instead of disabling ──
        if (config.isAutoUnbanEnabled() && trustFactor >= config.getTrustedMinimum()) {
            trustedBanTracker.computeIfAbsent(checkName, k -> new java.util.ArrayDeque<>());
            java.util.Deque<Long> bans = trustedBanTracker.get(checkName);
            long now = System.currentTimeMillis();
            long window = config.getAutoUnbanTimeWindow() * 60_000L;

            while (!bans.isEmpty() && now - bans.peekFirst() > window) bans.pollFirst();
            bans.addLast(now);

            if (bans.size() >= config.getAutoUnbanMaxBans()) {
                // Raise threshold multiplier by 0.5x, max 3x — never disable entirely
                double current = checkConfidenceMultiplier.getOrDefault(checkName, 1.0);
                double newMultiplier = Math.min(current + 0.5, 3.0);
                checkConfidenceMultiplier.put(checkName, newMultiplier);

                plugin.getLogger().warning("⚠ Auto-Unban: Check '" + checkName
                        + "' threshold raised to " + newMultiplier + "x (trusted ban count: " + bans.size() + ")");

                Component warning = Component.text("[Aegis] ", NamedTextColor.RED, TextDecoration.BOLD)
                        .append(Component.text("Check ", NamedTextColor.YELLOW))
                        .append(Component.text(checkName, NamedTextColor.RED))
                        .append(Component.text(" threshold raised to " + newMultiplier + "x due to trusted player flags.",
                                NamedTextColor.YELLOW));

                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission("aegis.admin")) staff.sendMessage(warning);
                }

                discordWebhook.sendAutoUnbanWarning(checkName, bans.size(), config.getAutoUnbanTimeWindow());
                // Do NOT return — still execute the punishment for this specific ban
            }
        }

        // ── Freeze evidence before punishment ──
        if (config.isEvidenceEnabled() && config.isSaveOnBan()) {
            profile.freezeEvidence();
            saveEvidence(player, profile, check, vl, trustFactor);
        }

        // ── Execute punishment on the correct thread ──
        // dispatchCommand must run on global region thread; SETBACK must run on entity thread
        switch (punishment) {
            case "BAN", "KICK" -> FoliaScheduler.runTask(plugin, () -> {
                if (!player.isOnline()) return;
                plugin.getLogger().info("[Aegis-Punishment] Executing " + punishment
                        + " on " + player.getName() + " for check " + checkName);
                if (punishment.equals("BAN")) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            config.getBanCommand().replace("%player%", player.getName()));
                } else {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            config.getKickCommand().replace("%player%", player.getName()));
                }
            }, null); // global region thread for dispatchCommand
            case "SETBACK" -> FoliaScheduler.runTask(plugin, () -> {
                if (!player.isOnline()) return;
                plugin.getLogger().info("[Aegis-Punishment] Executing SETBACK"
                        + " on " + player.getName() + " for check " + checkName);
                if (plugin.getRollbackManager() != null) {
                    plugin.getRollbackManager().setback(player, profile, 0);
                } else {
                    player.teleport(player.getLocation());
                }
            }, player); // entity thread for teleport
        }

        discordWebhook.sendPunishment(player.getName(), checkName, vl,
                trustFactor, player.getPing(), punishment,
                player.getUniqueId().toString().substring(0, 8));

        profile.resetViolation(checkName);
    }

    private void saveEvidence(Player player, PlayerProfile profile, Check check, int vl, double trustFactor) {
        var snapshots = profile.getFrozenEvidence();
        if (snapshots.isEmpty()) snapshots = profile.getEvidenceSnapshot();

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < snapshots.size(); i++) {
            var s = snapshots.get(i);
            json.append(String.format(
                    "{\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"yaw\":%.1f,\"pitch\":%.1f,\"ground\":%b,\"t\":%d}",
                    s.x(), s.y(), s.z(), s.yaw(), s.pitch(), s.onGround(), s.timestamp()));
            if (i < snapshots.size() - 1) json.append(",");
        }
        json.append("]");

        plugin.getDatabaseManager().saveEvidence(
                player.getUniqueId().toString(),
                check.getName(),
                vl,
                trustFactor,
                json.toString());
    }

    private void decayAll() {
        for (PlayerProfile profile : profileManager.getAllProfiles().values()) {
            profile.decayViolations();
        }
    }
}
