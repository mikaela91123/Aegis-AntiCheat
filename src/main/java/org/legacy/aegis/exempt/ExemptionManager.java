package org.legacy.aegis.exempt;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.legacy.aegis.Aegis;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.physics.PlayerPhysicsTracker;
import org.legacy.aegis.profile.PlayerProfile;
import org.legacy.aegis.scheduler.FoliaScheduler;

/**
 * Determines if a player should be exempt from specific checks.
 * Prevents false positives for Bedrock players, high-ping users,
 * and transient game states like teleportation and knockback.
 *
 * Supports both Floodgate and GeyserAPI for Bedrock detection.
 */
public class ExemptionManager {

    private final Aegis plugin;
    private final ConfigManager config;
    private boolean floodgateAvailable;
    private boolean geyserAvailable;

    // TPS tracking for lag-aware exemptions
    private double lastTps = 20.0;

    public ExemptionManager(Aegis plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;

        // Check if Floodgate is installed
        this.floodgateAvailable = plugin.getServer().getPluginManager().getPlugin("floodgate") != null;
        if (floodgateAvailable) {
            plugin.getLogger().info("Floodgate detected - Bedrock player exemptions enabled.");
        }

        // Check if Geyser is installed (standalone or as plugin)
        this.geyserAvailable = plugin.getServer().getPluginManager().getPlugin("Geyser-Spigot") != null;
        if (geyserAvailable) {
            plugin.getLogger().info("GeyserAPI detected - Enhanced Bedrock isolation enabled.");
        }

        // TPS must be read on the main/region thread — use runTaskTimer instead of runAsyncTimer
        FoliaScheduler.runTaskTimer(plugin, () -> {
            this.lastTps = Bukkit.getTPS()[0];
        }, 100L, 100L, null); // Update every 5 seconds
    }

    /**
     * Check if a player is a Bedrock player (via Floodgate or GeyserAPI).
     * Uses multiple detection methods for maximum compatibility.
     */
    public boolean isBedrockPlayer(Player player) {
        if (!config.isBedrockExempt()) return false;

        // Method 1: Floodgate API (most reliable)
        if (floodgateAvailable) {
            try {
                Class<?> floodgateApi = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Object instance = floodgateApi.getMethod("getInstance").invoke(null);
                boolean isBedrock = (boolean) floodgateApi.getMethod("isFloodgatePlayer", java.util.UUID.class)
                        .invoke(instance, player.getUniqueId());
                if (isBedrock) return true;
            } catch (Exception ignored) {}
        }

        // Method 2: GeyserAPI (for standalone Geyser setups)
        if (geyserAvailable) {
            try {
                Class<?> geyserApi = Class.forName("org.geysermc.geyser.api.GeyserApi");
                Object instance = geyserApi.getMethod("api").invoke(null);
                boolean isGeyser = (boolean) geyserApi.getMethod("isBedrockPlayer", java.util.UUID.class)
                        .invoke(instance, player.getUniqueId());
                if (isGeyser) return true;
            } catch (Exception ignored) {}
        }

        // Method 3: Fallback — prefix check (Floodgate default prefix is ".")
        if (player.getName().startsWith(".")) return true;

        // Method 4: Check if player's UUID is offline-mode style (Bedrock UUIDs start with 00000000-0000-0000)
        String uuid = player.getUniqueId().toString();
        if (uuid.startsWith("00000000-0000-0000")) return true;

        return false;
    }

    /**
     * Check if a player has the bypass permission.
     */
    public boolean hasBypass(Player player) {
        return player.hasPermission(config.getBypassPermission());
    }

    /**
     * Check if a player should be exempt from movement checks
     * due to recent knockback (damage).
     */
    public boolean isKnockbackExempt(PlayerProfile profile) {
        return profile.getDamageTicks() < config.getKnockbackGraceTicks();
    }

    /**
     * Check if a player should be exempt due to recent teleportation.
     */
    public boolean isTeleportExempt(PlayerProfile profile) {
        return profile.getTeleportTicks() < config.getTeleportGraceTicks();
    }

    /**
     * Check if a player has excessive ping and should be exempt from sensitive checks.
     */
    public boolean isHighPingExempt(Player player) {
        return player.getPing() > config.getHighPingThreshold();
    }

    /**
     * Check if the server is lagging (low TPS) and should be more lenient.
     * Below 18 TPS = lenient mode, below 15 TPS = very lenient.
     */
    public boolean isServerLagging() {
        return lastTps < config.getLagTpsThreshold();
    }

    /**
     * Get the lag severity multiplier.
     * 1.0 = no lag, higher = more lag.
     * Used to multiply VL thresholds during lag.
     */
    public double getLagMultiplier() {
        if (lastTps >= 19.5) return 1.0;
        if (lastTps >= 18.0) return 1.2;
        if (lastTps >= 16.0) return 1.5;
        if (lastTps >= 14.0) return 2.0;
        return 3.0; // Severe lag — very lenient
    }

    /**
     * Get current TPS.
     */
    public double getCurrentTps() {
        return lastTps;
    }

    /**
     * Full exemption check for movement-related checks.
     * Includes lag awareness and block-based exemptions.
     */
    public boolean isMovementExempt(Player player, PlayerProfile profile) {
        if (hasBypass(player)) return true;
        if (profile.isBedrockPlayer()) return true;
        if (isTeleportExempt(profile)) return true;
        if (isKnockbackExempt(profile)) return true;
        // During severe lag, skip movement checks entirely
        if (lastTps < config.getLagDisableThreshold()) return true;

        PlayerPhysicsTracker tracker = profile.getPhysicsTracker();
        if (tracker.isInLiquid()) return true;
        if (tracker.isInWeb() || tracker.isNearWeb()) return true;
        if (tracker.isClimbing() || tracker.isNearClimbable()) return true;
        if (tracker.isNearFlowingWater()) return true;
        if (tracker.isOnBouncyBlock()) return true;
        if (tracker.isBelowBlock()) return true;

        return false;
    }

    /**
     * Full exemption check for combat-related checks.
     */
    public boolean isCombatExempt(Player player, PlayerProfile profile) {
        if (hasBypass(player)) return true;
        if (profile.isBedrockPlayer()) return true;
        if (isHighPingExempt(player)) return true;
        return false;
    }

    /**
     * Check if exemptions should use adaptive thresholds based on network quality.
     * Higher ping or lower TPS = more tolerance in checks.
     */
    public double getAdaptiveTolerance(Player player) {
        double base = 1.0;

        // Ping factor: 0-50ms = 1.0, 100ms = 1.2, 200ms = 1.5, 300+ = 2.0
        int ping = player.getPing();
        if (ping > 200) base *= 1.5;
        else if (ping > 100) base *= 1.2;

        // TPS factor
        base *= getLagMultiplier();

        return base;
    }
}
