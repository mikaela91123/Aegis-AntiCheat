package org.legacy.aegis.evidence;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.legacy.aegis.Aegis;
import org.legacy.aegis.scheduler.FoliaScheduler;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Replays stored evidence by spawning an ArmorStand that follows
 * the recorded path of the flagged player.
 */
public class ReplayManager {

    private final Aegis plugin;
    private static final Gson GSON = new Gson();

    public ReplayManager(Aegis plugin) {
        this.plugin = plugin;
    }

    public void startReplay(Player viewer, int evidenceId) {
        String[] evidence = plugin.getDatabaseManager().getEvidenceById(evidenceId);

        if (evidence == null) {
            viewer.sendMessage(Component.text("[Aegis] ", NamedTextColor.RED, TextDecoration.BOLD)
                    .append(Component.text("Evidence #" + evidenceId + " not found.", NamedTextColor.GRAY)));
            return;
        }

        String playerUuid   = evidence[0];
        String checkName    = evidence[1];
        String vlStr        = evidence[2];
        String tfStr        = evidence[3];
        String snapshotJson = evidence[4];
        String createdAtStr = evidence[5];

        List<double[]> positions = parseSnapshots(snapshotJson);
        if (positions.isEmpty()) {
            viewer.sendMessage(Component.text("[Aegis] ", NamedTextColor.RED, TextDecoration.BOLD)
                    .append(Component.text("No replay data available for this evidence.", NamedTextColor.GRAY)));
            return;
        }

        String playerName = Bukkit.getOfflinePlayer(UUID.fromString(playerUuid)).getName();
        if (playerName == null) playerName = "Unknown";

        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date(Long.parseLong(createdAtStr)));

        viewer.sendMessage(Component.empty());
        viewer.sendMessage(Component.text("══════ Aegis Evidence Replay ══════", NamedTextColor.RED, TextDecoration.BOLD));
        viewer.sendMessage(Component.text(" ID: ", NamedTextColor.GRAY).append(Component.text("#" + evidenceId, NamedTextColor.WHITE)));
        viewer.sendMessage(Component.text(" Player: ", NamedTextColor.GRAY).append(Component.text(playerName, NamedTextColor.WHITE)));
        viewer.sendMessage(Component.text(" Check: ", NamedTextColor.GRAY).append(Component.text(checkName, NamedTextColor.RED)));
        viewer.sendMessage(Component.text(" VL: ", NamedTextColor.GRAY).append(Component.text(vlStr, NamedTextColor.YELLOW)));
        viewer.sendMessage(Component.text(" Trust Factor: ", NamedTextColor.GRAY).append(Component.text(tfStr, NamedTextColor.AQUA)));
        viewer.sendMessage(Component.text(" Date: ", NamedTextColor.GRAY).append(Component.text(date, NamedTextColor.WHITE)));
        viewer.sendMessage(Component.text(" Ticks: ", NamedTextColor.GRAY).append(Component.text(String.valueOf(positions.size()), NamedTextColor.WHITE)));
        viewer.sendMessage(Component.text("═══════════════════════════════════", NamedTextColor.RED, TextDecoration.BOLD));
        viewer.sendMessage(Component.text(" ▶ Replay starting...", NamedTextColor.GREEN));
        viewer.sendMessage(Component.empty());

        double[] start = positions.get(0);
        Location startLoc = new Location(viewer.getWorld(), start[0], start[1], start[2]);

        String finalPlayerName = playerName;
        FoliaScheduler.runTask(plugin, () -> {
            ArmorStand replayEntity = (ArmorStand) viewer.getWorld().spawnEntity(startLoc, EntityType.ARMOR_STAND);
            replayEntity.setCustomName("§c[REPLAY] §f" + finalPlayerName);
            replayEntity.setCustomNameVisible(true);
            replayEntity.setGravity(false);
            replayEntity.setInvisible(false);
            replayEntity.setSmall(true);
            replayEntity.setMarker(false);
            replayEntity.setInvulnerable(true);

            viewer.teleport(startLoc.clone().add(3, 1, 3));

            final int[] tick = {0};

            // Store the cancellable handle so we can stop the task when replay ends
            final FoliaScheduler.CancellableTask[] taskRef = {null};

            taskRef[0] = FoliaScheduler.runTaskTimerCancellable(plugin, () -> {
                // Stop condition: replay finished or viewer went offline
                if (tick[0] >= positions.size() || !viewer.isOnline()) {
                    replayEntity.remove();
                    if (viewer.isOnline()) {
                        viewer.sendMessage(Component.text("[Aegis] ", NamedTextColor.RED, TextDecoration.BOLD)
                                .append(Component.text("Replay finished.", NamedTextColor.GREEN)));
                    }
                    // Cancel the repeating task so it stops running
                    if (taskRef[0] != null) taskRef[0].cancel();
                    return;
                }

                double[] pos = positions.get(tick[0]++);
                Location loc = new Location(viewer.getWorld(), pos[0], pos[1], pos[2]);
                if (pos.length >= 5) {
                    loc.setYaw((float) pos[3]);
                    loc.setPitch((float) pos[4]);
                }
                replayEntity.teleport(loc);

            }, 5L, 1L, viewer);

        }, viewer);
    }

    public void listEvidence(Player viewer, String targetUuid) {
        var records = plugin.getDatabaseManager().getEvidenceByPlayer(targetUuid, 10);

        if (records.isEmpty()) {
            viewer.sendMessage(Component.text("[Aegis] ", NamedTextColor.RED, TextDecoration.BOLD)
                    .append(Component.text("No evidence found for this player.", NamedTextColor.GRAY)));
            return;
        }

        String playerName = Bukkit.getOfflinePlayer(UUID.fromString(targetUuid)).getName();
        viewer.sendMessage(Component.empty());
        viewer.sendMessage(Component.text("══════ Evidence List: " + (playerName != null ? playerName : "Unknown") + " ══════",
                NamedTextColor.RED, TextDecoration.BOLD));

        for (String[] record : records) {
            String id    = record[0];
            String check = record[1];
            String vl    = record[2];
            String date  = new SimpleDateFormat("MM/dd HH:mm").format(new Date(Long.parseLong(record[3])));

            viewer.sendMessage(
                    Component.text(" #" + id, NamedTextColor.YELLOW)
                            .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                            .append(Component.text(check, NamedTextColor.RED))
                            .append(Component.text(" (VL:" + vl + ")", NamedTextColor.GRAY))
                            .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                            .append(Component.text(date, NamedTextColor.WHITE)));
        }

        viewer.sendMessage(Component.text(" Use: /ac replay <ID> to watch", NamedTextColor.GRAY));
        viewer.sendMessage(Component.text("═══════════════════════════════════════", NamedTextColor.RED, TextDecoration.BOLD));
        viewer.sendMessage(Component.empty());
    }

    private List<double[]> parseSnapshots(String json) {
        List<double[]> positions = new ArrayList<>();
        try {
            JsonArray array = GSON.fromJson(json, JsonArray.class);
            for (int i = 0; i < array.size(); i++) {
                JsonObject obj = array.get(i).getAsJsonObject();
                double x     = obj.get("x").getAsDouble();
                double y     = obj.get("y").getAsDouble();
                double z     = obj.get("z").getAsDouble();
                double yaw   = obj.has("yaw")   ? obj.get("yaw").getAsDouble()   : 0;
                double pitch = obj.has("pitch") ? obj.get("pitch").getAsDouble() : 0;
                positions.add(new double[]{x, y, z, yaw, pitch});
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse replay data: " + e.getMessage());
        }
        return positions;
    }
}
