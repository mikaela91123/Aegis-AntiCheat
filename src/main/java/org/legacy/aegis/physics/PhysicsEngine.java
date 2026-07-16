package org.legacy.aegis.physics;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

// physics engine, replicates vanilla movement physics perfectly to predict where a player should be
public class PhysicsEngine {

    public static final double GRAVITY = 0.08;
    public static final double AIR_DRAG = 0.98;
    public static final double DEFAULT_SLIPPERINESS = 0.6;
    public static final double ICE_SLIPPERINESS = 0.98;
    public static final double SLIME_SLIPPERINESS = 0.8;
    public static final double BLUE_ICE_SLIPPERINESS = 0.989;
    public static final double JUMP_VELOCITY = 0.42;
    public static final double BASE_WALK_SPEED = 0.1;
    public static final double BASE_SPRINT_MULTIPLIER = 1.3;
    public static final double AIR_ACCELERATION = 0.02;
    public static final double TERMINAL_VELOCITY = 3.92;

    // simulate 1 tick of vertical gravity/jumping
    public static double predictNextVelocityY(double currentVelocityY, boolean onGround,
                                               boolean jumping, Player player,
                                               boolean isInWeb, boolean isInLiquid, boolean isClimbing) {
        double velocityY = currentVelocityY;

        if (onGround) {
            velocityY = 0;
            if (jumping) {
                velocityY = JUMP_VELOCITY;

                // jump boost
                if (player != null && player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                    int amplifier = player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier();
                    velocityY += (amplifier + 1) * 0.1;
                }
            }
        } else {
            // gravity and air drag
            velocityY -= GRAVITY;
            velocityY *= AIR_DRAG;
        }

        // webs/liquids/ladders heavily limit fall speed
        if (isInWeb) {
            velocityY = Math.max(-0.05, velocityY);
        } else if (isInLiquid || isClimbing) {
            velocityY = Math.max(-0.5, velocityY);
        }

        // limit to max fall speed
        velocityY = Math.max(-TERMINAL_VELOCITY, velocityY);

        return velocityY;
    }

    // extended version that handles powder snow and bubble columns
    public static double predictNextVelocityYExtended(double currentVelocityY, boolean onGround,
                                                       boolean jumping, Player player,
                                                       boolean isInWeb, boolean isInLiquid,
                                                       boolean isClimbing, boolean isInPowderSnow,
                                                       boolean inBubbleColumnUp) {
        double velocityY = predictNextVelocityY(currentVelocityY, onGround, jumping, player,
                                                 isInWeb, isInLiquid, isClimbing);

        // powder snow slows fall
        if (isInPowderSnow) {
            velocityY = Math.max(-0.03, velocityY); // very slow terminal velocity in powder snow
        }

        // bubble columns push up/down
        if (inBubbleColumnUp) {
            velocityY = Math.min(velocityY + 0.1, 0.3); // upward bubble column acceleration
        }

        return velocityY;
    }

    // predict max allowed horizontal movement for this tick
    public static double predictMaxHorizontalSpeed(Player player, boolean onGround,
                                                    boolean sprinting, Block belowBlock,
                                                    boolean isInWeb, boolean isInLiquid, boolean isClimbing) {
        double baseSpeed;

        if (onGround) {
            double slipperiness = getSlipperiness(belowBlock);
            double friction = slipperiness * 0.91;
            double acceleration = 0.1 * (0.6 / slipperiness) * (0.6 / slipperiness) * (0.6 / slipperiness);

            baseSpeed = acceleration;
            if (sprinting) {
                baseSpeed *= BASE_SPRINT_MULTIPLIER;
            }

            // calculate max ground speed based on friction
            baseSpeed = baseSpeed / (1.0 - friction);
        } else {
            // in air: account for sprint jump momentum carry
            baseSpeed = sprinting ? 0.45 : 0.30;
        }

        // environment overrides
        if (isInWeb) {
            baseSpeed = 0.15; // Webs severely limit speed
        } else if (isInLiquid) {
            baseSpeed = 0.20; // Base swimming speed (no Depth Strider)
            // Depth Strider enchantment increases underwater speed
            if (player != null) {
                var boots = player.getInventory().getBoots();
                if (boots != null) {
                    int depthStriderLevel = boots.getEnchantmentLevel(
                            org.bukkit.enchantments.Enchantment.DEPTH_STRIDER);
                    if (depthStriderLevel > 0) {
                        // Depth Strider III = ground speed underwater
                        // Each level adds ~0.07 to base speed
                        baseSpeed += 0.07 * depthStriderLevel;
                        // At level 3, player moves at near ground speed
                        if (depthStriderLevel >= 3) {
                            baseSpeed = onGround ? 0.28 : 0.15;
                            if (sprinting) baseSpeed *= BASE_SPRINT_MULTIPLIER;
                        }
                    }
                }
            }
            // Dolphin's Grace allows very fast swimming
            if (player != null && player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
                baseSpeed += 0.5; // Significant speed boost
            }
        } else if (isClimbing) {
            baseSpeed = 0.15; // Normal climb horizontal is slow
        }

        // soul sand slows
        if (belowBlock != null && belowBlock.getType() == Material.SOUL_SAND) {
            baseSpeed *= 0.4;
        }

        // honey block slows
        if (belowBlock != null && belowBlock.getType() == Material.HONEY_BLOCK) {
            baseSpeed *= 0.4;
        }

        // mud slows
        if (belowBlock != null && belowBlock.getType() == Material.MUD) {
            baseSpeed *= 0.65;
        }

        // speed potion
        if (player != null && player.hasPotionEffect(PotionEffectType.SPEED)) {
            int amplifier = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier();
            baseSpeed *= 1.0 + 0.25 * (amplifier + 1);
            baseSpeed += 0.05 * (amplifier + 1); // Extra flat buffer per level
        }

        // slowness potion
        if (player != null && player.hasPotionEffect(PotionEffectType.SLOWNESS)) {
            int amplifier = player.getPotionEffect(PotionEffectType.SLOWNESS).getAmplifier();
            baseSpeed *= 1.0 - 0.15 * (amplifier + 1);
            baseSpeed = Math.max(0, baseSpeed);
        }

        return baseSpeed;
    }

    // gets block friction
    public static double getSlipperiness(Block block) {
        if (block == null) return DEFAULT_SLIPPERINESS;

        return switch (block.getType()) {
            case ICE, PACKED_ICE, FROSTED_ICE -> ICE_SLIPPERINESS;
            case BLUE_ICE -> BLUE_ICE_SLIPPERINESS;
            case SLIME_BLOCK -> SLIME_SLIPPERINESS;
            case POWDER_SNOW -> 0.6; // same as default but handled specially for fall
            default -> DEFAULT_SLIPPERINESS;
        };
    }

    // check if vertical movement is physically possible
    public static boolean isYDeltaValid(double predictedVelY, double actualDeltaY, double tolerance,
                                         boolean isInWeb, boolean isInLiquid, boolean isClimbing) {
        if (isInLiquid || isClimbing) {
            // lenient bounds for swimming/climbing
            return actualDeltaY >= -1.0 && actualDeltaY <= 0.5;
        }
        if (isInWeb) {
            return actualDeltaY >= -0.1 && actualDeltaY <= 0.1;
        }

        return Math.abs(actualDeltaY - predictedVelY) <= tolerance;
    }

    // check vertical movement with extra block state exceptions
    public static boolean isYDeltaValidExtended(double predictedVelY, double actualDeltaY, double tolerance,
                                                 boolean isInWeb, boolean isInLiquid, boolean isClimbing,
                                                 boolean isBelowBlock, boolean isOnBouncyBlock) {
        if (isInLiquid || isClimbing) {
            return actualDeltaY >= -1.0 && actualDeltaY <= 0.5;
        }
        if (isInWeb) {
            return actualDeltaY >= -0.1 && actualDeltaY <= 0.1;
        }
        // ceiling collision can cause erratic Y changes
        if (isBelowBlock) {
            return actualDeltaY >= -0.6 && actualDeltaY <= 0.6;
        }
        // bouncing is unpredictable
        if (isOnBouncyBlock) {
            return actualDeltaY >= -3.0 && actualDeltaY <= 3.0;
        }

        return Math.abs(actualDeltaY - predictedVelY) <= tolerance;
    }

    // check if horizontal movement is physically possible
    public static boolean isHorizontalSpeedValid(double maxAllowed, double actualSpeed, double tolerance,
                                                  boolean isInWeb, boolean isInLiquid, boolean isClimbing) {
        if (isInLiquid && actualSpeed > maxAllowed + tolerance) {
            // allow dolphin's grace speeds
            return actualSpeed <= 1.2;
        }
        return actualSpeed <= maxAllowed + tolerance;
    }

    // calculate next Y position
    public static double predictNextY(double currentY, double velocityY) {
        return currentY + velocityY;
    }

    // check if fall damage should happen
    public static boolean shouldTakeFallDamage(double fallDistance) {
        return fallDistance > 3.0;
    }

    // calculate expected fall damage
    public static int calculateFallDamage(double fallDistance) {
        if (fallDistance <= 3.0) return 0;
        return (int) Math.ceil(fallDistance - 3.0);
    }
}
