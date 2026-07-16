package org.legacy.aegis.appeal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.legacy.aegis.Aegis;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AppealManager implements CommandExecutor, TabCompleter {

    private final Aegis plugin;
    private final Map<Integer, Appeal> pendingAppeals;
    private int nextAppealId = 1;

    public record Appeal(int id, UUID playerUuid, String playerName, int evidenceId,
                         String reason, long submittedAt, String status) {
    }

    public AppealManager(Aegis plugin) {
        this.plugin = plugin;
        this.pendingAppeals = plugin.getDatabaseManager().loadAppeals();

        // Find max ID to set nextAppealId
        for (int id : pendingAppeals.keySet()) {
            if (id >= nextAppealId) nextAppealId = id + 1;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("appeal")) {
            return handlePlayerAppeal(sender, args);
        }
        return false;
    }

    private boolean handlePlayerAppeal(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can submit appeals.", NamedTextColor.RED));
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(Component.text("[Aegis] ", NamedTextColor.RED, TextDecoration.BOLD)
                    .append(Component.text("Usage: /appeal <evidenceID> <reason>", NamedTextColor.GRAY)));
            return true;
        }

        int evidenceId;
        try {
            evidenceId = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Invalid evidence ID.", NamedTextColor.RED));
            return true;
        }

        StringBuilder reason = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            reason.append(args[i]);
            if (i < args.length - 1) reason.append(" ");
        }

        String[] evidence = plugin.getDatabaseManager().getEvidenceById(evidenceId);
        if (evidence == null) {
            player.sendMessage(Component.text("[Aegis] Evidence #" + evidenceId + " not found.", NamedTextColor.RED));
            return true;
        }

        for (Appeal appeal : pendingAppeals.values()) {
            if (appeal.playerUuid().equals(player.getUniqueId()) && appeal.status().equals("PENDING")) {
                player.sendMessage(Component.text("[Aegis] ", NamedTextColor.RED, TextDecoration.BOLD)
                        .append(Component.text("You already have a pending appeal (#" + appeal.id() + ").", NamedTextColor.YELLOW)));
                return true;
            }
        }

        int appealId = nextAppealId++;
        Appeal appeal = new Appeal(appealId, player.getUniqueId(), player.getName(),
                evidenceId, reason.toString(), System.currentTimeMillis(), "PENDING");

        pendingAppeals.put(appealId, appeal);
        plugin.getDatabaseManager().saveAppeal(appeal);

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("[Aegis] ", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text("Appeal #" + appealId + " submitted successfully!", NamedTextColor.GREEN)));
        player.sendMessage(Component.text("  Your appeal will be reviewed by staff.", NamedTextColor.GRAY));
        player.sendMessage(Component.empty());

        Component staffNotification = Component.text("[Aegis] ", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text("New appeal ", NamedTextColor.YELLOW))
                .append(Component.text("#" + appealId, NamedTextColor.WHITE))
                .append(Component.text(" from ", NamedTextColor.YELLOW))
                .append(Component.text(player.getName(), NamedTextColor.WHITE))
                .append(Component.text(" (Evidence #" + evidenceId + ")", NamedTextColor.GRAY));

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("aegis.admin")) {
                staff.sendMessage(staffNotification);
            }
        }

        plugin.getDiscordWebhook().sendAppeal(player.getName(), appealId, evidenceId, reason.toString());
        return true;
    }

    public void listAppeals(CommandSender sender) {
        if (!sender.hasPermission("aegis.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return;
        }

        List<Appeal> pending = pendingAppeals.values().stream()
                .filter(a -> a.status().equals("PENDING"))
                .toList();

        if (pending.isEmpty()) {
            sender.sendMessage(Component.text("[Aegis] ", NamedTextColor.RED, TextDecoration.BOLD)
                    .append(Component.text("No pending appeals.", NamedTextColor.GRAY)));
            return;
        }

        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("══════ Pending Appeals ══════", NamedTextColor.RED, TextDecoration.BOLD));

        for (Appeal appeal : pending) {
            String date = new SimpleDateFormat("MM/dd HH:mm").format(new Date(appeal.submittedAt()));

            sender.sendMessage(
                    Component.text(" #" + appeal.id(), NamedTextColor.YELLOW)
                            .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                            .append(Component.text(appeal.playerName(), NamedTextColor.WHITE))
                            .append(Component.text(" | Evidence #" + appeal.evidenceId(), NamedTextColor.GRAY))
                            .append(Component.text(" | " + date, NamedTextColor.DARK_GRAY))
            );
            sender.sendMessage(
                    Component.text("   Reason: ", NamedTextColor.GRAY)
                            .append(Component.text(appeal.reason(), NamedTextColor.WHITE))
            );
            sender.sendMessage(
                    Component.text("   [APPROVE]", NamedTextColor.GREEN, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/ac approve " + appeal.id()))
                            .append(Component.text("  "))
                            .append(Component.text("[DENY]", NamedTextColor.RED, TextDecoration.BOLD)
                                    .clickEvent(ClickEvent.runCommand("/ac deny " + appeal.id())))
                            .append(Component.text("  "))
                            .append(Component.text("[REPLAY]", NamedTextColor.AQUA, TextDecoration.BOLD)
                                    .clickEvent(ClickEvent.runCommand("/ac replay " + appeal.evidenceId())))
            );
        }

        sender.sendMessage(Component.text("═════════════════════════════", NamedTextColor.RED, TextDecoration.BOLD));
        sender.sendMessage(Component.empty());
    }

    public void approveAppeal(CommandSender sender, int appealId) {
        if (!sender.hasPermission("aegis.admin")) return;

        Appeal appeal = pendingAppeals.get(appealId);
        if (appeal == null || !appeal.status().equals("PENDING")) {
            sender.sendMessage(Component.text("Appeal #" + appealId + " not found or already processed.", NamedTextColor.RED));
            return;
        }

        Appeal updated = new Appeal(appeal.id(), appeal.playerUuid(), appeal.playerName(),
                appeal.evidenceId(), appeal.reason(), appeal.submittedAt(), "APPROVED");
        pendingAppeals.put(appealId, updated);
        plugin.getDatabaseManager().updateAppealStatus(appealId, "APPROVED");

        String unbanCmd = plugin.getConfigManager().getUnbanCommand().replace("%player%", appeal.playerName());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), unbanCmd);

        sender.sendMessage(Component.text("[Aegis] ", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text("Appeal #" + appealId + " APPROVED. ", NamedTextColor.GREEN))
                .append(Component.text(appeal.playerName() + " has been unbanned.", NamedTextColor.WHITE)));

        plugin.getLogger().info("Appeal #" + appealId + " approved by " + sender.getName() + " — " + appeal.playerName() + " unbanned.");
    }

    public void denyAppeal(CommandSender sender, int appealId) {
        if (!sender.hasPermission("aegis.admin")) return;

        Appeal appeal = pendingAppeals.get(appealId);
        if (appeal == null || !appeal.status().equals("PENDING")) {
            sender.sendMessage(Component.text("Appeal #" + appealId + " not found or already processed.", NamedTextColor.RED));
            return;
        }

        Appeal updated = new Appeal(appeal.id(), appeal.playerUuid(), appeal.playerName(),
                appeal.evidenceId(), appeal.reason(), appeal.submittedAt(), "DENIED");
        pendingAppeals.put(appealId, updated);
        plugin.getDatabaseManager().updateAppealStatus(appealId, "DENIED");

        sender.sendMessage(Component.text("[Aegis] ", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text("Appeal #" + appealId + " DENIED.", NamedTextColor.RED)));

        plugin.getLogger().info("Appeal #" + appealId + " denied by " + sender.getName() + " — " + appeal.playerName() + " remains banned.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
