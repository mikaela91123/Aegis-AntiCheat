package org.legacy.aegis.check.impl.movement;

import org.bukkit.entity.Player;
import org.legacy.aegis.check.AbstractCheck;
import org.legacy.aegis.check.CheckType;
import org.legacy.aegis.check.MovementCheck;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.profile.PlayerProfile;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// nofall: player spoofs onGround=true while actually falling to skip fall damage
public class NoFallA extends AbstractCheck implements MovementCheck {

    private static final double FALL_THRESHOLD = -0.08;
    private static final int SPOOF_THRESHOLD = 2;  // was 4 — need only 2 consecutive spoofs
    private static final int FLAG_BUFFER = 2;       // was 3

    private final Map<UUID, Integer> consecutiveSpoofsMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> bufferMap = new ConcurrentHashMap<>();

    public NoFallA(ConfigManager config) {
        super("NoFall (A)", CheckType.MOVEMENT, "nofall.a", config);
    }

    @Override
    public boolean processMovement(Player player, PlayerProfile profile,
                                   double x, double y, double z, boolean onGround) {
        UUID uuid = player.getUniqueId();

        // use the dedicated flag, old check comparing coords to 0,0,0 was bypassable at world origin
        if (!profile.hasReceivedFirstPacket()) {
            return false;
        }

        if (player.isInsideVehicle() || player.isFlying() || player.isGliding()
                || player.isSwimming() || player.isClimbing()) {
            consecutiveSpoofsMap.put(uuid, 0);
            return false;
        }

        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)) {
            consecutiveSpoofsMap.put(uuid, 0);
            return false;
        }

        if (profile.getPhysicsTracker().isInWeb() || profile.getPhysicsTracker().isInLiquid()) {
            consecutiveSpoofsMap.put(uuid, 0);
            return false;
        }

        // damageTicks gets reset on knockback too so this covers wind/crystal edge cases
        if (profile.getDamageTicks() < 10) {
            consecutiveSpoofsMap.put(uuid, 0);
            return false;
        }

        int consecutiveSpoofs = consecutiveSpoofsMap.getOrDefault(uuid, 0);
        double deltaY = y - profile.getLastY();

        if (onGround && deltaY < FALL_THRESHOLD) {
            boolean actuallyOnGround = hasGroundBelow(x, y, z, player.getWorld());

            if (!actuallyOnGround) {
                consecutiveSpoofs++;
                if (consecutiveSpoofs >= SPOOF_THRESHOLD) {
                    int buffer = bufferMap.getOrDefault(uuid, 0) + 1;
                    if (buffer >= FLAG_BUFFER) {
                        consecutiveSpoofsMap.put(uuid, 0);
                        bufferMap.put(uuid, 0);
                        return true;
                    }
                    bufferMap.put(uuid, buffer);
                    consecutiveSpoofsMap.put(uuid, 0);
                    return false;
                }
            } else {
                consecutiveSpoofs = 0;
            }
        } else {
            if (!onGround || deltaY >= 0) consecutiveSpoofs = 0;
        }

        int buffer = bufferMap.getOrDefault(uuid, 0);
        if (buffer > 0) bufferMap.put(uuid, buffer - 1);

        consecutiveSpoofsMap.put(uuid, consecutiveSpoofs);
        return false;
    }

    // check if there's a solid block within 0.5 below the player's feet, 3x3 footprint
    private boolean hasGroundBelow(double x, double y, double z, org.bukkit.World world) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);

        // check at feet level and one below to handle slabs, snow layers, etc.
        for (int dy = 0; dy >= -1; dy--) {
            int by = (int) Math.floor(y + dy);
            // Sample a 3x3 area at the player's footprint to catch edge cases
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    org.bukkit.block.Block b = world.getBlockAt(bx + ox, by, bz + oz);
                    if (b.getType().isSolid() || isSolidTop(b)) return true;
                }
            }
        }
        return false;
    }

    // slabs, stairs, snow layers aren't "solid" but have solid tops so handle them
    private boolean isSolidTop(org.bukkit.block.Block b) {
        String name = b.getType().name();
        return name.contains("SLAB") || name.contains("STAIR") || name.contains("STEP")
            || b.getType() == org.bukkit.Material.SNOW
            || b.getType() == org.bukkit.Material.SOUL_SAND
            || b.getType() == org.bukkit.Material.LILY_PAD;
    }

    @Override
    public void cleanup(UUID uuid) {
        consecutiveSpoofsMap.remove(uuid);
        bufferMap.remove(uuid);
    }
}
