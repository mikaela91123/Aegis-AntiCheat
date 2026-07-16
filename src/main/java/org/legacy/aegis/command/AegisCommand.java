package org.legacy.aegis.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.legacy.aegis.Aegis;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.evidence.ReplayManager;
import org.legacy.aegis.profile.PlayerProfile;
import org.legacy.aegis.profile.ProfileManager;
import org.legacy.aegis.violation.ViolationManager;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Main command handler for /ac (alias: /aegis, /anticheat).
 * Subcommands: reload, alerts, info, replay, evidence, version
 */
public class AegisCommand implements CommandExecutor, TabCompleter {

    private final Aegis plugin;
    private final ConfigManager config;
    private final ViolationManager violationManager;
    private final ProfileManager profileManager;
    private final ReplayManager replayManager;

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "reload", "alerts", "info", "replay", "evidence", "appeals", "approve", "deny", "version"
    );

    public AegisCommand(Aegis plugin, ConfigManager config,
                        ViolationManager violationManager, ProfileManager profileManager) {
        this.plugin = plugin;
        this.config = config;
        this.violationManager = violationManager;
        this.profileManager = profileManager;
        this.replayManager = new ReplayManager(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("aegis.command")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "alerts" -> handleAlerts(sender);
            case "info" -> handleInfo(sender, args);
            case "replay" -> handleReplay(sender, args);
            case "evidence" -> handleEvidence(sender, args);
            case "appeals" -> plugin.getAppealManager().listAppeals(sender);
            case "approve" -> handleApprove(sender, args);
            case "deny" -> handleDeny(sender, args);
            case "version" -> handleVersion(sender);
            default -> sendHelp(sender);
        }

        return true;
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("aegis.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return;
        }
        plugin.reload();
        sender.sendMessage(Component.text("[Aegis] ", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text("Configuration reloaded successfully!", NamedTextColor.GREEN)));
    }

    private void handleAlerts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text("[Aegis] ", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text("Alerts are managed by the 'aegis.alerts' permission.", NamedTextColor.GRAY)));
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /ac info <player>", NamedTextColor.RED));
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
            return;
        }

        PlayerProfile profile = profileManager.getProfile(target.getUniqueId());
        if (profile == null) {
            sender.sendMessage(Component.text("Profile not loaded.", NamedTextColor.RED));
            return;
        }

        // Calculate Trust Factor
        double tf = profile.calculateTrustFactor(
                config.getPlaytimeWeight(),
                config.getViolationPenalty(),
                config.getBaseScore(),
                config.getMaxScore()
        );

        long playtimeHours = profile.getTotalPlaytimeMillis() / 3_600_000L;

        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("══════ Aegis Player Info ══════", NamedTextColor.RED, TextDecoration.BOLD));
        sender.sendMessage(Component.text(" Player: ", NamedTextColor.GRAY)
                .append(Component.text(target.getName(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text(" Ping: ", NamedTextColor.GRAY)
                .append(Component.text(target.getPing() + "ms", NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text(" Trust Factor: ", NamedTextColor.GRAY)
                .append(Component.text(String.format("%.1f", tf), getTfColor(tf))));
        sender.sendMessage(Component.text(" Total Playtime: ", NamedTextColor.GRAY)
                .append(Component.text(playtimeHours + " hours", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text(" Total Violations: ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(profile.getTotalViolations()), NamedTextColor.RED)));
        sender.sendMessage(Component.text(" Session Violations: ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(profile.getRecentViolations()), NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text(" Bedrock: ", NamedTextColor.GRAY)
                .append(Component.text(profile.isBedrockPlayer() ? "Yes" : "No",
                        profile.isBedrockPlayer() ? NamedTextColor.AQUA : NamedTextColor.WHITE)));
        sender.sendMessage(Component.text(" CPS: ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(profile.getCurrentCPS()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("════════════════════════════", NamedTextColor.RED, TextDecoration.BOLD));
        sender.sendMessage(Component.empty());
    }

    /**
     * Handle /ac replay <evidenceID> - Watch evidence replay via ArmorStand NPC.
     */
    private void handleReplay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
            return;
        }
        if (!player.hasPermission("aegis.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /ac replay <evidenceID>", NamedTextColor.RED));
            return;
        }

        try {
            int evidenceId = Integer.parseInt(args[1]);
            replayManager.startReplay(player, evidenceId);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Invalid evidence ID. Must be a number.", NamedTextColor.RED));
        }
    }

    /**
     * Handle /ac evidence <player> - List recent evidence for a player.
     */
    private void handleEvidence(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
            return;
        }
        if (!player.hasPermission("aegis.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /ac evidence <player>", NamedTextColor.RED));
            return;
        }

        // Try online player first, then offline
        Player target = Bukkit.getPlayer(args[1]);
        String uuid;
        if (target != null) {
            uuid = target.getUniqueId().toString();
        } else {
            var offlinePlayer = Bukkit.getOfflinePlayer(args[1]);
            if (offlinePlayer.hasPlayedBefore()) {
                uuid = offlinePlayer.getUniqueId().toString();
            } else {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return;
            }
        }

        replayManager.listEvidence(player, uuid);
    }

    /**
     * Handle /ac approve <appealID>
     */
    private void handleApprove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /ac approve <appealID>", NamedTextColor.RED));
            return;
        }
        try {
            int appealId = Integer.parseInt(args[1]);
            plugin.getAppealManager().approveAppeal(sender, appealId);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Invalid appeal ID.", NamedTextColor.RED));
        }
    }

    /**
     * Handle /ac deny <appealID>
     */
    private void handleDeny(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /ac deny <appealID>", NamedTextColor.RED));
            return;
        }
        try {
            int appealId = Integer.parseInt(args[1]);
            plugin.getAppealManager().denyAppeal(sender, appealId);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Invalid appeal ID.", NamedTextColor.RED));
        }
    }

    private void handleVersion(CommandSender sender) {
        sender.sendMessage(Component.text("[Aegis] ", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text("v" + plugin.getDescription().getVersion(), NamedTextColor.WHITE))
                .append(Component.text(" | Hybrid Detection Engine", NamedTextColor.GRAY)));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("══════ Aegis Commands ══════", NamedTextColor.RED, TextDecoration.BOLD));
        sender.sendMessage(Component.text(" /ac reload", NamedTextColor.RED)
                .append(Component.text(" - Reload configuration", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text(" /ac alerts", NamedTextColor.RED)
                .append(Component.text(" - Toggle cheat alerts", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text(" /ac info <player>", NamedTextColor.RED)
                .append(Component.text(" - View player info & Trust Factor", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text(" /ac evidence <player>", NamedTextColor.RED)
                .append(Component.text(" - List evidence records", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text(" /ac replay <ID>", NamedTextColor.RED)
                .append(Component.text(" - Watch evidence replay (NPC)", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text(" /ac appeals", NamedTextColor.RED)
                .append(Component.text(" - List pending appeals", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text(" /ac approve <ID>", NamedTextColor.RED)
                .append(Component.text(" - Approve appeal & unban", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text(" /ac deny <ID>", NamedTextColor.RED)
                .append(Component.text(" - Deny appeal", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text(" /ac version", NamedTextColor.RED)
                .append(Component.text(" - Show plugin version", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("════════════════════════════", NamedTextColor.RED, TextDecoration.BOLD));
        sender.sendMessage(Component.empty());
    }

    private NamedTextColor getTfColor(double tf) {
        if (tf >= 50) return NamedTextColor.GREEN;
        if (tf >= 30) return NamedTextColor.YELLOW;
        if (tf >= 10) return NamedTextColor.GOLD;
        return NamedTextColor.RED;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("evidence"))) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
