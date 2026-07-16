package org.legacy.aegis.check.impl.movement;

import org.bukkit.entity.Player;
import org.legacy.aegis.check.AbstractCheck;
import org.legacy.aegis.check.CheckType;
import org.legacy.aegis.check.MovementCheck;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.profile.PlayerProfile;
import org.legacy.aegis.physics.PlayerPhysicsTracker;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// shared base for fly checks, handles all the exemption logic in one place
public abstract class AbstractFlyCheck extends AbstractCheck implements MovementCheck {

    protected final Map<UUID, Long> lastFlightDisable = new ConcurrentHashMap<>();
    protected final Map<UUID, Long> lastElytraEquip = new ConcurrentHashMap<>();
    protected final Map<UUID, Long> lastFireworkUse = new ConcurrentHashMap<>();
    protected final Map<UUID, Long> lastRiptideTime = new ConcurrentHashMap<>();
    protected final Map<UUID, Integer> bufferMap = new ConcurrentHashMap<>();
    protected final Map<UUID, Long> lastVelocityTime = new ConcurrentHashMap<>();

    protected static final long ELYTRA_SWAP_GRACE = 8000L;
    protected static final long FIREWORK_GRACE = 6000L;
    protected static final long GENERAL_FLIGHT_GRACE = 5000L;
    protected static final long COBWEB_GRACE = 1500L;
    protected static final long RIPTIDE_GRACE = 5000L;
    protected static final long VELOCITY_GRACE = 5000L;

    public AbstractFlyCheck(String name, String configPath, ConfigManager config) {
        super(name, CheckType.MOVEMENT, configPath, config);
    }

    @Override
    public boolean processMovement(Player player, PlayerProfile profile, double x, double y, double z, boolean onGround) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (isExempt(player, profile, uuid, now)) {
            return false;
        }

        if (checkFly(player, profile, x, y, z, onGround)) {
            int buffer = bufferMap.getOrDefault(uuid, 0) + 1;
            if (buffer > 1) { // Reduced buffer from 3 to 1
                bufferMap.put(uuid, buffer);
                return true;
            }
            bufferMap.put(uuid, buffer);
            return false;
        }

        int buffer = bufferMap.getOrDefault(uuid, 0);
        if (buffer > 0) bufferMap.put(uuid, buffer - 1);

        return false;
    }

    protected abstract boolean checkFly(Player player, PlayerProfile profile, double x, double y, double z, boolean onGround);

    // returns true if the player should be exempt from the fly check
    protected boolean isExempt(Player player, PlayerProfile profile, UUID uuid, long now) {
        boolean isExpectedFlying = player.isFlying() || player.isGliding() || player.getAllowFlight();

        boolean hasElytra = player.getInventory().getChestplate() != null
                && player.getInventory().getChestplate().getType().name().contains("ELYTRA");

        if (hasElytra || player.isGliding()) {
            lastElytraEquip.put(uuid, now);
        }

        if (isExpectedFlying || player.isInsideVehicle() || player.isSwimming() || player.isClimbing()) {
            lastFlightDisable.put(uuid, now);
            return true;
        }

        if (hasElytra) {
            lastFlightDisable.put(uuid, now);
            return true;
        }

        boolean isRiptideActive = player.isRiptiding()
                || (player.getInventory().getItemInMainHand().getEnchantmentLevel(
                    org.bukkit.enchantments.Enchantment.RIPTIDE) > 0
                && (player.isInWater() || player.isInRain()));

        if (isRiptideActive) {
            lastRiptideTime.put(uuid, now);
            return true;
        }

        if (now - lastRiptideTime.getOrDefault(uuid, 0L) < RIPTIDE_GRACE) {
            if (profile.getPhysicsTracker().getConsecutiveYViolations() > 2) {
                profile.getPhysicsTracker().reset();
            }
            return true;
        }

        long lastElytraTime = lastElytraEquip.getOrDefault(uuid, 0L);
        if (now - lastElytraTime < ELYTRA_SWAP_GRACE) {
            if (profile.getPhysicsTracker().getConsecutiveYViolations() > 2) {
                profile.getPhysicsTracker().reset();
            }
            return true;
        }

        if (now - lastFlightDisable.getOrDefault(uuid, 0L) < GENERAL_FLIGHT_GRACE) {
            return true;
        }

        long lastFireworkTime = lastFireworkUse.getOrDefault(uuid, 0L);
        if (now - lastFireworkTime < FIREWORK_GRACE) {
            if (profile.getPhysicsTracker().getConsecutiveYViolations() > 2) {
                profile.getPhysicsTracker().reset();
            }
            return true;
        }

        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)
                || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)) {
            return true;
        }

        PlayerPhysicsTracker tracker = profile.getPhysicsTracker();

        if (tracker.isInLiquid() || tracker.isInWeb() || tracker.isClimbing()) {
            return true;
        }

        // water current edge case, player gets pushed so exempt them
        if (tracker.isNearFlowingWater()) {
            if (tracker.getConsecutiveYViolations() > 2) {
                tracker.resetViolationCounters();
            }
            return true;
        }

        if (now - tracker.getLastInWebTime() < COBWEB_GRACE) {
            if (tracker.getConsecutiveYViolations() > 2) {
                tracker.reset();
            }
            return true;
        }

        if (tracker.isNearWeb()) {
            return true;
        }

        // near ladder or vine, still getting momentum from it
        if (tracker.isNearClimbable()) {
            if (tracker.getConsecutiveYViolations() > 2) {
                tracker.resetViolationCounters();
            }
            return true;
        }

        // slime/honey/bed bounce is unpredictable, skip
        if (tracker.isOnBouncyBlock()) {
            if (tracker.getConsecutiveYViolations() > 2) {
                tracker.resetViolationCounters();
            }
            return true;
        }

        // ice makes horizontal movement weird
        if (tracker.isOnIce()) {
            return true;
        }

        // cramped ceiling, player clips/bounces off it
        if (tracker.isBelowBlock()) {
            if (tracker.getConsecutiveYViolations() > 2) {
                tracker.resetViolationCounters();
            }
            return true;
        }

        // got knocked back/wind charge/explosion, need to skip and also clear violations
        // otherwise when grace ends there's a backlog of Y violations waiting to fire
        if (now - lastVelocityTime.getOrDefault(uuid, 0L) < VELOCITY_GRACE) {
            if (tracker.getConsecutiveYViolations() > 0) {
                tracker.resetViolationCounters();
            }
            return true;
        }

        return false;
    }

    public void onFireworkUse(UUID uuid) {
        lastFireworkUse.put(uuid, System.currentTimeMillis());
    }

    public void onVelocity(UUID uuid) {
        lastVelocityTime.put(uuid, System.currentTimeMillis());
        bufferMap.put(uuid, 0);
    }

    @Override
    public void cleanup(UUID uuid) {
        lastFlightDisable.remove(uuid);
        lastElytraEquip.remove(uuid);
        lastFireworkUse.remove(uuid);
        lastRiptideTime.remove(uuid);
        bufferMap.remove(uuid);
        lastVelocityTime.remove(uuid);
    }
}
