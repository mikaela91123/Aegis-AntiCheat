package org.legacy.aegis.check.impl.movement;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.legacy.aegis.check.AbstractCheck;
import org.legacy.aegis.check.CheckType;
import org.legacy.aegis.check.MovementCheck;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.profile.PlayerProfile;
import org.legacy.aegis.physics.PlayerPhysicsTracker;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// check B: looks for sudden burst/spike instead of sustained speed
public class SpeedB extends AbstractCheck implements MovementCheck {

    private final Map<UUID, Long> lastSpeedPotionTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastSpeedAmplifier = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastVelocityTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTeleportTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRiptideTime = new ConcurrentHashMap<>();

    private static final long SPEED_POTION_GRACE = 2000L;
    private static final long VELOCITY_GRACE = 3000L;
    private static final long TELEPORT_GRACE = 1000L;
    private static final long RIPTIDE_GRACE = 5000L;

    public SpeedB(ConfigManager config) {
        super("Speed (B)", CheckType.MOVEMENT, "speed.b", config);
    }

    @Override
    public boolean processMovement(Player player, PlayerProfile profile, double x, double y, double z, boolean onGround) {
        UUID uuid = player.getUniqueId();

        // Skip vehicles, flying, elytra
        if (player.isInsideVehicle() || player.isFlying() || player.isGliding() || player.getAllowFlight()) {
            return false;
        }

        PlayerPhysicsTracker tracker = profile.getPhysicsTracker();

        if (tracker.isInLiquid() || tracker.isInWeb() || tracker.isClimbing()) {
            return false;
        }
        if (tracker.isNearFlowingWater() || tracker.isNearWeb() || tracker.isNearClimbable()) {
            return false;
        }
        if (tracker.isOnBouncyBlock() || tracker.isOnIce() || tracker.isBelowBlock()) {
            return false;
        }

        // Track speed potion state for grace period
        boolean hasSpeedNow = player.hasPotionEffect(PotionEffectType.SPEED);
        if (hasSpeedNow) {
            int amplifier = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            lastSpeedPotionTime.put(uuid, System.currentTimeMillis());
            lastSpeedAmplifier.put(uuid, amplifier);
        }

        long now = System.currentTimeMillis();

        // Speed potion grace period: skip check during potion transition
        long lastSpeedTime = lastSpeedPotionTime.getOrDefault(uuid, 0L);
        long timeSinceSpeed = now - lastSpeedTime;
        if (timeSinceSpeed < SPEED_POTION_GRACE) {
            return false;
        }

        // Skip check if player recently received velocity (knockback, explosion, etc.)
        if (now - lastVelocityTime.getOrDefault(uuid, 0L) < VELOCITY_GRACE) {
            return false;
        }

        // Skip check if player recently teleported
        if (now - lastTeleportTime.getOrDefault(uuid, 0L) < TELEPORT_GRACE) {
            return false;
        }

        // Use profile's damage/teleport ticks as secondary exemption
        if (profile.getDamageTicks() < 20 || profile.getTeleportTicks() < 10) {
            return false;
        }

        // Riptide trident exemption
        boolean isRiptideActive = player.isRiptiding()
                || (player.getInventory().getItemInMainHand().getEnchantmentLevel(
                    org.bukkit.enchantments.Enchantment.RIPTIDE) > 0
                && (player.isInWater() || player.isInRain()));
        if (isRiptideActive) {
            lastRiptideTime.put(uuid, now);
            return false;
        }
        if (now - lastRiptideTime.getOrDefault(uuid, 0L) < RIPTIDE_GRACE) {
            return false;
        }

        double deltaX = x - profile.getLastX();
        double deltaZ = z - profile.getLastZ();
        double currentSpeed = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        // Allowed max horizontal predicted by full Vanilla simulation tick + an extra buffer
        double predictedMax = tracker.getPredictedMaxSpeedH() + 0.35;

        if (currentSpeed > predictedMax) {
            // SpeedB looks for extreme instant violations (like teleporting forward)
            if (currentSpeed - predictedMax > 0.6) {
                // If it's a huge spike past prediction
                if (tracker.getConsecutiveHViolations() > 4) {
                    return true;
                }
            } else if (tracker.getConsecutiveHViolations() > 12) {
                // Slower burn violation
                return true;
            }
        }

        return false;
    }

    // knockback/explosion velocity, skip
    public void onVelocity(UUID uuid) {
        lastVelocityTime.put(uuid, System.currentTimeMillis());
    }

    // teleport, clear cached speed
    public void onTeleport(UUID uuid) {
        lastTeleportTime.put(uuid, System.currentTimeMillis());
    }

    @Override
    public void cleanup(java.util.UUID uuid) {
        lastSpeedPotionTime.remove(uuid);
        lastSpeedAmplifier.remove(uuid);
        lastVelocityTime.remove(uuid);
        lastTeleportTime.remove(uuid);
        lastRiptideTime.remove(uuid);
    }
}
