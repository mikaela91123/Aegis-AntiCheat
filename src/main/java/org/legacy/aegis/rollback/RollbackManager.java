package org.legacy.aegis.rollback;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.legacy.aegis.Aegis;
import org.legacy.aegis.profile.PlayerProfile;
import org.legacy.aegis.scheduler.FoliaScheduler;

/**
 * Rollback Manager: Handles teleporting players back to their last
 * safe position when a critical movement violation is detected.
 *
 * This is more effective than kicking for Movement checks because:
 * 1. It immediately cancels the cheat benefit (no distance gained)
 * 2. It doesn't disrupt legitimate players experiencing lag spikes
 * 3. It works silently before escalating to kicks/bans
 *
 * Safety hierarchy:
 * - First offense:  Setback to last safe position
 * - Repeated:       Setback + velocity cancel
 * - Excessive:      Kick escalation
 */
public class RollbackManager {

    private final Aegis plugin;

    public RollbackManager(Aegis plugin) {
        this.plugin = plugin;
    }

    /**
     * Perform a setback: teleport the player to their last safe position.
     *
     * @param player  The player to setback
     * @param profile The player's profile (contains safe positions)
     * @param ticksAgo How many ticks back to rollback to (0 = most recent safe)
     */
    public void setback(Player player, PlayerProfile profile, int ticksAgo) {
        double[] safePos = ticksAgo > 0
                ? profile.getSafePositionAgo(ticksAgo)
                : profile.getLastSafePosition();

        if (safePos == null) {
            // No safe position recorded, teleport to current location (velocity cancel)
            FoliaScheduler.runTask(plugin, () -> {
                player.teleport(player.getLocation());
                player.setVelocity(player.getVelocity().zero());
            }, player);
            return;
        }

        String worldName = profile.getLastSafeWorld();
        if (worldName == null) worldName = player.getWorld().getName();

        String finalWorldName = worldName;
        FoliaScheduler.runTask(plugin, () -> {
            var world = Bukkit.getWorld(finalWorldName);
            if (world == null) world = player.getWorld();

            Location safeLoc = new Location(world, safePos[0], safePos[1], safePos[2],
                    player.getLocation().getYaw(), player.getLocation().getPitch());

            player.teleport(safeLoc);
            player.setVelocity(player.getVelocity().zero());

            // Mark teleport in profile to prevent false flags
            profile.onTeleport();
        }, player);
    }

    /**
     * Advanced setback with velocity cancellation and optional rubber-banding.
     * Used for critical violations (fly, speed with high VL).
     */
    public void criticalSetback(Player player, PlayerProfile profile) {
        // Go back further for critical violations
        setback(player, profile, 5);

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[ROLLBACK] " + player.getName()
                    + " -> setback to safe position (critical)");
        }
    }

    /**
     * Record the current position as safe if not currently flagged.
     *
     * Previously this only recorded when onGround=true, which caused a critical bug:
     * during elytra flight or any airborne movement, NO positions were recorded.
     * When a setback was triggered after landing, the last safe position could be
     * from 10+ seconds ago (before the flight started), sending the player far back.
     *
     * Fix: record every legitimate (non-flagged) position — ground AND air.
     * A position is "safe" if no check flagged it that tick, regardless of ground state.
     */
    public void recordIfSafe(Player player, PlayerProfile profile, boolean onGround, boolean flagged) {
        if (!flagged) {
            profile.recordSafePosition(
                    player.getLocation().getX(),
                    player.getLocation().getY(),
                    player.getLocation().getZ(),
                    player.getWorld().getName()
            );
        }
    }
}
