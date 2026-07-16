package org.legacy.aegis.check.impl.movement;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.legacy.aegis.check.AbstractCheck;
import org.legacy.aegis.check.CheckType;
import org.legacy.aegis.check.MovementCheck;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.profile.PlayerProfile;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// jesus/waterwalk: player walks on water surface without sinking
public class JesusA extends AbstractCheck implements MovementCheck {

    // Buffer system: require multiple flags before reporting
    private static final int FLAG_BUFFER = 3;
    // How many ticks the player must be "on water surface" before flagging
    private static final int MIN_SURFACE_TICKS = 4;

    private final Map<UUID, Integer> surfaceTicksMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> bufferMap = new ConcurrentHashMap<>();

    public JesusA(ConfigManager config) {
        super("Jesus (A)", CheckType.MOVEMENT, "jesus.a", config);
    }

    @Override
    public boolean processMovement(Player player, PlayerProfile profile, double x, double y, double z, boolean onGround) {
        UUID uuid = player.getUniqueId();

        // Skip high-ping players (above 350ms — packet timing unreliable)
        if (player.getPing() > 350) {
            surfaceTicksMap.put(uuid, 0);
            return false;
        }

        // Skip if player is flying, gliding, swimming, climbing, sneaking,
        // blocking with shield, or in vehicle
        if (player.isFlying() || player.isGliding() || player.isSwimming()
                || player.isClimbing() || player.isSneaking()
                || player.isInsideVehicle() || player.isBlocking()) {
            surfaceTicksMap.put(uuid, 0);
            return false;
        }

        // Skip if player has allow flight (creative, spectator)
        if (player.getAllowFlight()) {
            surfaceTicksMap.put(uuid, 0);
            return false;
        }

        // Skip if player is in cobweb
        if (profile.getPhysicsTracker().isInWeb()) {
            surfaceTicksMap.put(uuid, 0);
            return false;
        }

        // Skip if player has Frost Walker enchantment on boots
        // Frost Walker turns water into ice beneath the player's feet
        var boots = player.getInventory().getBoots();
        if (boots != null && boots.getEnchantmentLevel(
                org.bukkit.enchantments.Enchantment.FROST_WALKER) > 0) {
            surfaceTicksMap.put(uuid, 0);
            return false;
        }

        // Skip levitation (player floats above water legitimately)
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)) {
            surfaceTicksMap.put(uuid, 0);
            return false;
        }

        // Skip slow falling (player descends slowly, can appear to walk on water)
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)) {
            surfaceTicksMap.put(uuid, 0);
            return false;
        }

        // Check blocks at player's feet and below
        org.bukkit.World world = player.getWorld();
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);

        Material feetBlock = world.getBlockAt(blockX, blockY, blockZ).getType();
        Material belowBlock = world.getBlockAt(blockX, blockY - 1, blockZ).getType();
        Material aboveBlock = world.getBlockAt(blockX, blockY + 1, blockZ).getType();

        boolean isOnWaterSurface = isWater(feetBlock) || (isWater(belowBlock) && !feetBlock.isSolid());
        boolean isHeadAboveWater = !isWater(aboveBlock);

        // Skip holding a trident with Riptide while in water (charging animation)
        if (isOnWaterSurface && isHeadAboveWater) {
            var mainHand = player.getInventory().getItemInMainHand();
            if (mainHand.getType() == Material.TRIDENT
                    && mainHand.containsEnchantment(org.bukkit.enchantments.Enchantment.RIPTIDE)) {
                surfaceTicksMap.put(uuid, 0);
                return false;
            }
        }

        // Check if player is standing on lily pad (legitimate)
        if (belowBlock == Material.LILY_PAD || feetBlock == Material.LILY_PAD) {
            surfaceTicksMap.put(uuid, 0);
            return false;
        }

        // Check if player is standing on a block above water (e.g., slab, carpet)
        if (belowBlock.isSolid()) {
            surfaceTicksMap.put(uuid, 0);
            return false;
        }

        // Check if player is in a boat (boats float on water)
        if (player.getVehicle() != null) {
            surfaceTicksMap.put(uuid, 0);
            return false;
        }

        // Detection: player is at water surface, head above water,
        // not sinking, and claiming to be on ground
        if (isOnWaterSurface && isHeadAboveWater && onGround) {
            // Player is standing on water surface without legitimate means
            int surfaceTicks = surfaceTicksMap.getOrDefault(uuid, 0) + 1;
            surfaceTicksMap.put(uuid, surfaceTicks);

            if (surfaceTicks >= MIN_SURFACE_TICKS) {
                int buffer = bufferMap.getOrDefault(uuid, 0) + 1;
                if (buffer >= FLAG_BUFFER) {
                    bufferMap.put(uuid, 0);
                    surfaceTicksMap.put(uuid, 0);
                    return true;
                }
                bufferMap.put(uuid, buffer);
                return false;
            }
        } else if (!isOnWaterSurface) {
            // Decay surface ticks when not on water
            int surfaceTicks = surfaceTicksMap.getOrDefault(uuid, 0);
            if (surfaceTicks > 0) surfaceTicksMap.put(uuid, surfaceTicks - 1);
        }

        // Decay buffer
        int buffer = bufferMap.getOrDefault(uuid, 0);
        if (buffer > 0) bufferMap.put(uuid, buffer - 1);

        return false;
    }

    private boolean isWater(Material mat) {
        return mat == Material.WATER;
    }

    @Override
    public void cleanup(java.util.UUID uuid) {
        surfaceTicksMap.remove(uuid);
        bufferMap.remove(uuid);
    }
}
