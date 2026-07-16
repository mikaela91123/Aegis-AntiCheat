package org.legacy.aegis.check.impl.movement;

import org.bukkit.entity.Player;
import org.legacy.aegis.check.AbstractCheck;
import org.legacy.aegis.check.CheckType;
import org.legacy.aegis.check.MovementCheck;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.physics.PlayerPhysicsTracker;
import org.legacy.aegis.profile.PlayerProfile;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// step hack: player steps up more than 0.6 blocks without jumping
public class StepA extends AbstractCheck implements MovementCheck {

    private static final double MAX_STEP_HEIGHT = 0.6;
    private static final double STEP_BUFFER = 0.05;
    private static final int FLAG_BUFFER = 3;

    // Grace period after having elytra equipped / gliding (ms)
    private static final long ELYTRA_GRACE = 3000L;

    private final Map<UUID, Integer> bufferMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastElytraTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastVelocityTime = new ConcurrentHashMap<>();
    private static final long VELOCITY_GRACE = 2000L; // 2 seconds

    public StepA(ConfigManager config) {
        super("Step (A)", CheckType.MOVEMENT, "step.a", config);
    }

    @Override
    public boolean processMovement(Player player, PlayerProfile profile, double x, double y, double z, boolean onGround) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (player.isInsideVehicle() || player.isFlying() || player.isGliding()
                || player.isSwimming() || player.isClimbing()) {
            return false;
        }

        if (profile.getPhysicsTracker().isInWeb() || profile.getPhysicsTracker().isInLiquid()) {
            return false;
        }
        PlayerPhysicsTracker st = profile.getPhysicsTracker();
        if (st.isNearFlowingWater() || st.isNearWeb() || st.isNearClimbable()) {
            return false;
        }
        if (st.isOnBouncyBlock() || st.isBelowBlock()) {
            return false;
        }

        // Track elytra equip / gliding state for grace period
        boolean hasElytra = player.getInventory().getChestplate() != null
                && player.getInventory().getChestplate().getType().name().contains("ELYTRA");
        if (hasElytra || player.isGliding()) {
            lastElytraTime.put(uuid, now);
            return false;
        }

        // Grace period after elytra unequip — residual momentum can cause upward movement
        if (now - lastElytraTime.getOrDefault(uuid, 0L) < ELYTRA_GRACE) {
            return false;
        }

        // Velocity grace: knockback/wind charge/slime/piston can push player upward legitimately
        if (now - lastVelocityTime.getOrDefault(uuid, 0L) < VELOCITY_GRACE) {
            return false;
        }

        double deltaY = y - profile.getLastY();
        boolean wasOnGround = profile.wasOnGround();

        if (wasOnGround && onGround && deltaY > 0) {
            double maxStep = MAX_STEP_HEIGHT + STEP_BUFFER;

            if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST)) {
                int amplifier = player.getPotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST).getAmplifier();
                maxStep += (amplifier + 1) * 0.1;
            }

            if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)) {
                return false;
            }
            if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)) {
                return false;
            }

            if (deltaY > maxStep) {
                int buffer = bufferMap.getOrDefault(uuid, 0) + 1;
                if (buffer >= FLAG_BUFFER) {
                    bufferMap.put(uuid, 0);
                    return true;
                }
                bufferMap.put(uuid, buffer);
                return false;
            }
        }

        int buffer = bufferMap.getOrDefault(uuid, 0);
        if (buffer > 0) bufferMap.put(uuid, buffer - 1);

        return false;
    }

    public void onVelocity(UUID uuid) {
        lastVelocityTime.put(uuid, System.currentTimeMillis());
    }

    @Override
    public void cleanup(UUID uuid) {
        bufferMap.remove(uuid);
        lastElytraTime.remove(uuid);
        lastVelocityTime.remove(uuid);
    }
}
