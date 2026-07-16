package org.legacy.aegis.check.impl.movement;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.legacy.aegis.check.AbstractCheck;
import org.legacy.aegis.check.CheckType;
import org.legacy.aegis.check.MovementCheck;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.profile.PlayerProfile;
import org.legacy.aegis.physics.PlayerPhysicsTracker;
import org.legacy.aegis.physics.PhysicsEngine;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// check A: physics-based horizontal speed check, compares tick-to-tick delta
public class SpeedA extends AbstractCheck implements MovementCheck {

    private final Map<UUID, Double> lastDeltaXZMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> bufferMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSpeedPotionTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastSpeedAmplifier = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastVelocityTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTeleportTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRiptideTime = new ConcurrentHashMap<>();

    // grace period constants
    private static final long SPEED_POTION_GRACE = 3500L;
    private static final long VELOCITY_GRACE = 5000L;
    private static final long TELEPORT_GRACE = 3000L;
    private static final long RIPTIDE_GRACE = 5000L;

    public SpeedA(ConfigManager config) {
        super("Speed (A)", CheckType.MOVEMENT, "speed.a", config);
    }

    @Override
    public boolean processMovement(Player player, PlayerProfile profile, double x, double y, double z, boolean onGround) {
        if (player.isInsideVehicle() || player.isFlying() || player.isGliding()) {
            return false;
        }

        PlayerPhysicsTracker tracker = profile.getPhysicsTracker();

        // If player is in extreme edge cases, do not check Speed A.
        if (tracker.isInLiquid() || tracker.isInWeb() || tracker.isClimbing()) {
            return false;
        }

        // Near-block exemptions
        if (tracker.isNearFlowingWater() || tracker.isNearWeb() || tracker.isNearClimbable()) {
            return false;
        }
        if (tracker.isOnBouncyBlock() || tracker.isOnIce()) {
            return false;
        }
        if (tracker.isBelowBlock()) {
            return false;
        }

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // skip if got knocked back or hit by explosion, velocity lasts multiple ticks
        if (now - lastVelocityTime.getOrDefault(uuid, 0L) < VELOCITY_GRACE) {
            trackCurrentSpeed(uuid, x, z, profile);
            return false;
        }

        // teleport makes position delta meaningless
        if (now - lastTeleportTime.getOrDefault(uuid, 0L) < TELEPORT_GRACE) {
            trackCurrentSpeed(uuid, x, z, profile);
            return false;
        }

        // also check raw profile ticks
        if (profile.getDamageTicks() < 20 || profile.getTeleportTicks() < 10) {
            trackCurrentSpeed(uuid, x, z, profile);
            return false;
        }

        // riptide launches the player really fast, don't flag that
        boolean isRiptideActive = player.isRiptiding()
                || (player.getInventory().getItemInMainHand().getEnchantmentLevel(
                    org.bukkit.enchantments.Enchantment.RIPTIDE) > 0
                && (player.isInWater() || player.isInRain()));
        if (isRiptideActive) {
            lastRiptideTime.put(uuid, now);
            trackCurrentSpeed(uuid, x, z, profile);
            return false;
        }
        if (now - lastRiptideTime.getOrDefault(uuid, 0L) < RIPTIDE_GRACE) {
            trackCurrentSpeed(uuid, x, z, profile);
            return false;
        }

        double lastDeltaXZ = lastDeltaXZMap.getOrDefault(uuid, 0.0);
        int buffer = bufferMap.getOrDefault(uuid, 0);

        double deltaX = x - profile.getLastX();
        double deltaY = y - profile.getLastY();
        double deltaZ = z - profile.getLastZ();
        double deltaXZ = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        double friction = 0.91;
        double acceleration;

        if (profile.wasOnGround()) {
            Block belowBlock = player.getLocation().clone().subtract(0, 0.5, 0).getBlock();
            double slipperiness = PhysicsEngine.getSlipperiness(belowBlock);

            friction = slipperiness * 0.91;
            acceleration = 0.1 * (0.6 / slipperiness) * (0.6 / slipperiness) * (0.6 / slipperiness);

            if (player.isSprinting()) {
                acceleration *= 1.3; // BASE_SPRINT_MULTIPLIER
            }
        } else {
            // airborne
            acceleration = player.isSprinting() ? 0.026 : 0.02;
        }

        // predicted limit this tick based on last speed * friction + acceleration
        double limit = (lastDeltaXZ * friction) + acceleration;

        // airborne and velocity jump / sprint jump boost logic
        if (!profile.wasOnGround() || deltaY > 0.0) {
            // small extra window for ping jitter in the air
            limit += 0.05;

            // sprint-jump adds +0.2 to XZ directly, also wider detection window for network jitter
            if (deltaY > 0.35 && profile.wasOnGround()) {
                if (player.isSprinting()) {
                    limit += 0.2;
                    // Sprint-jump momentum persists for multiple air ticks
                    // Add extra buffer for the first 3 air ticks after sprint-jump
                    if (profile.getAirTicks() <= 3) {
                        limit += 0.05;
                    }
                }
            }
        }

        // apply speed potion adjustments, also track transitions to avoid false flags
        boolean hasSpeedNow = player.hasPotionEffect(PotionEffectType.SPEED);
        int currentAmplifier = 0;
        if (hasSpeedNow) {
            currentAmplifier = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            lastSpeedPotionTime.put(uuid, System.currentTimeMillis());
            lastSpeedAmplifier.put(uuid, currentAmplifier);

            // use the higher of current or recent amplifier since bukkit sometimes misses it on the transition tick
            int recentAmplifier = lastSpeedAmplifier.getOrDefault(uuid, 0);
            int effectiveAmplifier = Math.max(currentAmplifier, recentAmplifier);

            // vanilla is 0.2/level, using 0.25 for safety buffer
            limit *= 1.0 + (0.25 * effectiveAmplifier);
            limit += 0.1 * effectiveAmplifier; // Increased from 0.08 to 0.1 for more breathing room
        } else {
            // potion just wore off but movement is still fast, apply partial bonus for a bit
            long lastSpeedTime = lastSpeedPotionTime.getOrDefault(uuid, 0L);
            long timeSinceSpeed = System.currentTimeMillis() - lastSpeedTime;
            if (timeSinceSpeed < SPEED_POTION_GRACE) {
                int recentAmplifier = lastSpeedAmplifier.getOrDefault(uuid, 0);
                if (recentAmplifier > 0) {
                    // Apply diminishing speed bonus during grace period
                    double graceFactor = 1.0 - (timeSinceSpeed / (double) SPEED_POTION_GRACE);
                    limit *= 1.0 + (0.15 * recentAmplifier * graceFactor);
                    limit += 0.05 * recentAmplifier * graceFactor;
                }
            }
        }

        if (player.hasPotionEffect(PotionEffectType.SLOWNESS)) {
            int amplifier = player.getPotionEffect(PotionEffectType.SLOWNESS).getAmplifier() + 1;
            limit *= 1.0 - (0.15 * amplifier);
        }

        boolean flagged = false;

        // 0.12 epsilon: accounts for ping jitter, tick rate variance, position interpolation
        if (deltaXZ > limit + 0.12) {
            buffer++;
            if (buffer > 8) {
                flagged = true;
            }
        } else {
            buffer = Math.max(0, buffer - 1);
        }

        // store for next tick
        lastDeltaXZMap.put(uuid, deltaXZ);
        bufferMap.put(uuid, buffer);

        return flagged;
    }

    // track speed without resetting to zero during grace periods
    // if we reset to zero the limit formula cold-starts too low and fires false flags immediately after grace
    private void trackCurrentSpeed(UUID uuid, double x, double z, PlayerProfile profile) {
        double dx = x - profile.getLastX();
        double dz = z - profile.getLastZ();
        double speed = Math.sqrt(dx * dx + dz * dz);
        lastDeltaXZMap.put(uuid, speed);
        bufferMap.put(uuid, 0);
    }

    // velocity from knockback/explosion, skip the check for a bit
    public void onVelocity(UUID uuid) {
        lastVelocityTime.put(uuid, System.currentTimeMillis());
    }

    // player teleported, reset momentum tracking
    public void onTeleport(UUID uuid) {
        lastTeleportTime.put(uuid, System.currentTimeMillis());
        lastDeltaXZMap.put(uuid, 0.0);
        bufferMap.put(uuid, 0);
    }

    @Override
    public void cleanup(java.util.UUID uuid) {
        lastDeltaXZMap.remove(uuid);
        bufferMap.remove(uuid);
        lastSpeedPotionTime.remove(uuid);
        lastSpeedAmplifier.remove(uuid);
        lastVelocityTime.remove(uuid);
        lastTeleportTime.remove(uuid);
        lastRiptideTime.remove(uuid);
    }
}
