package org.legacy.aegis.physics;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

// per-player physics simulator, tracks predicted vs actual movement
public class PlayerPhysicsTracker {

    private double predictedVelocityY;
    private double predictedMaxSpeedH;
    private double lastY;
    private boolean lastOnGround;
    private boolean wasJumping;

    private boolean isInLiquid;
    private boolean isInWeb;
    private boolean isClimbing;
    private boolean isInPowderSnow;
    private boolean inBubbleColumnUp;
    private boolean inBubbleColumnDown;
    private long lastInWebTime; // Timestamp of last cobweb contact for grace period

    private boolean isNearFlowingWater;
    private boolean isNearWeb;
    private boolean isNearClimbable;
    private boolean isBelowBlock;
    private boolean isOnBouncyBlock; // slime, honey, bed
    private boolean isOnIce;

    private double actualDeltaY;
    private double actualSpeedH;
    private double fallStartY;
    private boolean isFalling;

    // counts ticks during legit long falls to prevent false fly flags from physics drift
    private int freefallTicks;

    private int consecutiveYViolations;
    private int consecutiveHViolations;
    private int consecutiveGhostBlocks; // Player claims ground but no block beneath

    private double yTolerance = 0.05;
    private double hTolerance = 0.05;

    public PlayerPhysicsTracker() {
        reset();
    }

    // reset everything when player teleports or joins
    public void reset() {
        predictedVelocityY = 0;
        predictedMaxSpeedH = 0;
        lastY = 0;
        lastOnGround = true;
        wasJumping = false;
        actualDeltaY = 0;
        actualSpeedH = 0;
        fallStartY = 0;
        isFalling = false;
        consecutiveYViolations = 0;
        consecutiveHViolations = 0;
        consecutiveGhostBlocks = 0;
        lastInWebTime = 0;
        isInPowderSnow = false;
        inBubbleColumnUp = false;
        inBubbleColumnDown = false;
        isNearFlowingWater = false;
        isNearWeb = false;
        isNearClimbable = false;
        isBelowBlock = false;
        isOnBouncyBlock = false;
        isOnIce = false;
        freefallTicks = 0;
    }

    // reset only violations, used during knockback so we don't flag them when grace period ends
    public void resetViolationCounters() {
        this.consecutiveYViolations = 0;
        this.consecutiveHViolations = 0;
    }

    // apply knockback velocity so our predictions don't flag them
    public void applyExternalVelocity(double velocityY) {
        this.predictedVelocityY = velocityY;
        this.consecutiveYViolations = 0;
        this.consecutiveHViolations = 0;
    }

    // process a single movement tick
    public PhysicsResult processTick(Player player, double newX, double newY, double newZ,
                                     boolean onGround, double lastX, double lastY, double lastZ) {
        // Calculate actual deltas
        actualDeltaY = newY - lastY;
        double deltaX = newX - lastX;
        double deltaZ = newZ - lastZ;
        actualSpeedH = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        // Safely determine surrounding blocks using getBlockAt() directly
        // Avoids creating Location objects for better GC performance
        this.isInLiquid = false;
        this.isInWeb = false;
        this.isClimbing = false;
        this.isInPowderSnow = false;
        this.inBubbleColumnUp = false;
        this.inBubbleColumnDown = false;
        this.isNearFlowingWater = false;
        this.isNearWeb = false;
        this.isNearClimbable = false;
        this.isBelowBlock = false;
        this.isOnBouncyBlock = false;
        this.isOnIce = false;

        org.bukkit.World world = player.getWorld();
        int baseX = (int) Math.floor(newX);
        int baseY = (int) Math.floor(newY);
        int baseZ = (int) Math.floor(newZ);

        // Check blocks within the player's bounding box (expanded to 0.4 to prevent edge desync)
        // Using integer block coordinates at bounding box edges
        int minX = (int) Math.floor(newX - 0.4);
        int maxX = (int) Math.floor(newX + 0.4);
        int minY = baseY;
        int maxY = (int) Math.floor(newY + 1.8);
        int minZ = (int) Math.floor(newZ - 0.4);
        int maxZ = (int) Math.floor(newZ + 0.4);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                for (int by = minY; by <= maxY; by++) {
                    Material mat = world.getBlockAt(bx, by, bz).getType();

                    if (isLiquid(mat)) this.isInLiquid = true;
                    if (mat == Material.COBWEB) this.isInWeb = true;
                    if (mat == Material.SWEET_BERRY_BUSH) this.isInWeb = true; // Berry bush slows movement like cobweb
                    if (isClimbable(mat)) this.isClimbing = true;
                    if (mat == Material.POWDER_SNOW) this.isInPowderSnow = true;
                    if (mat == Material.BUBBLE_COLUMN) {
                        // Check block data to determine direction
                        try {
                            org.bukkit.block.data.type.BubbleColumn bc =
                                (org.bukkit.block.data.type.BubbleColumn) world.getBlockAt(bx, by, bz).getBlockData();
                            if (bc.isDrag()) this.inBubbleColumnDown = true;
                            else this.inBubbleColumnUp = true;
                        } catch (Exception ignored) {
                            this.inBubbleColumnUp = true; // assume upward if can't determine
package org.legacy.aegis.physics;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

// per-player physics simulator, tracks predicted vs actual movement
public class PlayerPhysicsTracker {

    private double predictedVelocityY;
    private double predictedMaxSpeedH;
    private double lastY;
    private boolean lastOnGround;
    private boolean wasJumping;

    private boolean isInLiquid;
    private boolean isInWeb;
    private boolean isClimbing;
    private boolean isInPowderSnow;
    private boolean inBubbleColumnUp;
    private boolean inBubbleColumnDown;
    private long lastInWebTime; // Timestamp of last cobweb contact for grace period

    private boolean isNearFlowingWater;
    private boolean isNearWeb;
    private boolean isNearClimbable;
    private boolean isBelowBlock;
    private boolean isOnBouncyBlock; // slime, honey, bed
    private boolean isOnIce;

    private double actualDeltaY;
    private double actualSpeedH;
    private double fallStartY;
    private boolean isFalling;

    // counts ticks during legit long falls to prevent false fly flags from physics drift
    private int freefallTicks;

    private int consecutiveYViolations;
    private int consecutiveHViolations;
    private int consecutiveGhostBlocks; // Player claims ground but no block beneath

    private double yTolerance = 0.05;
    private double hTolerance = 0.05;

    public PlayerPhysicsTracker() {
        reset();
    }

    // reset everything when player teleports or joins
    public void reset() {
        predictedVelocityY = 0;
        predictedMaxSpeedH = 0;
        lastY = 0;
        lastOnGround = true;
        wasJumping = false;
        actualDeltaY = 0;
        actualSpeedH = 0;
        fallStartY = 0;
        isFalling = false;
        consecutiveYViolations = 0;
        consecutiveHViolations = 0;
        consecutiveGhostBlocks = 0;
        lastInWebTime = 0;
        isInPowderSnow = false;
        inBubbleColumnUp = false;
        inBubbleColumnDown = false;
        isNearFlowingWater = false;
        isNearWeb = false;
        isNearClimbable = false;
        isBelowBlock = false;
        isOnBouncyBlock = false;
        isOnIce = false;
        freefallTicks = 0;
    }

    // reset only violations, used during knockback so we don't flag them when grace period ends
    public void resetViolationCounters() {
        this.consecutiveYViolations = 0;
        this.consecutiveHViolations = 0;
    }

    // apply knockback velocity so our predictions don't flag them
    public void applyExternalVelocity(double velocityY) {
        this.predictedVelocityY = velocityY;
        this.consecutiveYViolations = 0;
        this.consecutiveHViolations = 0;
    }

    // process a single movement tick
    public PhysicsResult processTick(Player player, double newX, double newY, double newZ,
                                     boolean onGround, double lastX, double lastY, double lastZ) {
        // Calculate actual deltas
        actualDeltaY = newY - lastY;
        double deltaX = newX - lastX;
        double deltaZ = newZ - lastZ;
        actualSpeedH = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        // Safely determine surrounding blocks using getBlockAt() directly
        // Avoids creating Location objects for better GC performance
        this.isInLiquid = false;
        this.isInWeb = false;
        this.isClimbing = false;
        this.isInPowderSnow = false;
        this.inBubbleColumnUp = false;
        this.inBubbleColumnDown = false;
        this.isNearFlowingWater = false;
        this.isNearWeb = false;
        this.isNearClimbable = false;
        this.isBelowBlock = false;
        this.isOnBouncyBlock = false;
        this.isOnIce = false;

        org.bukkit.World world = player.getWorld();
        int baseX = (int) Math.floor(newX);
        int baseY = (int) Math.floor(newY);
        int baseZ = (int) Math.floor(newZ);

        // Check blocks within the player's bounding box (expanded to 0.4 to prevent edge desync)
        // Using integer block coordinates at bounding box edges
        int minX = (int) Math.floor(newX - 0.4);
        int maxX = (int) Math.floor(newX + 0.4);
        int minY = baseY;
        int maxY = (int) Math.floor(newY + 1.8);
        int minZ = (int) Math.floor(newZ - 0.4);
        int maxZ = (int) Math.floor(newZ + 0.4);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                for (int by = minY; by <= maxY; by++) {
                    Material mat = world.getBlockAt(bx, by, bz).getType();

                    if (isLiquid(mat)) this.isInLiquid = true;
                    if (mat == Material.COBWEB) this.isInWeb = true;
                    if (mat == Material.SWEET_BERRY_BUSH) this.isInWeb = true; // Berry bush slows movement like cobweb
                    if (isClimbable(mat)) this.isClimbing = true;
                    if (mat == Material.POWDER_SNOW) this.isInPowderSnow = true;
                    if (mat == Material.BUBBLE_COLUMN) {
                        // Check block data to determine direction
                        try {
                            org.bukkit.block.data.type.BubbleColumn bc =
                                (org.bukkit.block.data.type.BubbleColumn) world.getBlockAt(bx, by, bz).getBlockData();
                            if (bc.isDrag()) this.inBubbleColumnDown = true;
                            else this.inBubbleColumnUp = true;
                        } catch (Exception ignored) {
                            this.inBubbleColumnUp = true; // assume upward if can't determine
                        }
                    }
                }
            }
        }

        // near-block state (like water pushing you or cobweb slowing you down when you brush against it)
        int nearMinX = (int) Math.floor(newX - 1.0);
        int nearMaxX = (int) Math.floor(newX + 1.0);
        int nearMinY = baseY - 1;
        int nearMaxY = (int) Math.floor(newY + 2.0);
        int nearMinZ = (int) Math.floor(newZ - 1.0);
        int nearMaxZ = (int) Math.floor(newZ + 1.0);

        for (int bx = nearMinX; bx <= nearMaxX; bx++) {
            for (int bz = nearMinZ; bz <= nearMaxZ; bz++) {
                for (int by = nearMinY; by <= nearMaxY; by++) {
                    Material mat = world.getBlockAt(bx, by, bz).getType();

                    if (isLiquid(mat)) this.isNearFlowingWater = true;
                    if (mat == Material.COBWEB || mat == Material.SWEET_BERRY_BUSH) {
                        this.isNearWeb = true;
                        if (!this.isInWeb) {
                            this.lastInWebTime = System.currentTimeMillis();
                        }
                    }
                    if (isClimbable(mat)) this.isNearClimbable = true;
                }
            }
        }

        // Also check below feet within bounding box width
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                for (int checkY = (int) Math.floor(newY - 0.90); checkY <= (int) Math.floor(newY - 0.01); checkY++) {
                    Material feetMat = world.getBlockAt(bx, checkY, bz).getType();

                    if (isLiquid(feetMat)) this.isInLiquid = true;
                    if (feetMat == Material.COBWEB) this.isInWeb = true;
                    if (feetMat == Material.SWEET_BERRY_BUSH) this.isInWeb = true;
                    if (isClimbable(feetMat)) this.isClimbing = true;

                    if (feetMat == Material.SLIME_BLOCK || feetMat == Material.HONEY_BLOCK) {
                        this.isOnBouncyBlock = true;
                    }
                    if (feetMat.name().contains("BED")) {
                        this.isOnBouncyBlock = true;
                    }
                    if (feetMat == Material.ICE || feetMat == Material.PACKED_ICE
                            || feetMat == Material.BLUE_ICE || feetMat == Material.FROSTED_ICE) {
                        this.isOnIce = true;
                    }
                }
            }
        }

        // Ceiling check within bounding box width
        int headY = (int) Math.floor(newY + 1.8);
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                if (world.getBlockAt(bx, headY, bz).getType().isSolid()) {
                    this.isBelowBlock = true;
                    break;
                }
            }
            if (this.isBelowBlock) break;
        }

        // Find the best supporting block below the bounding box
        Block belowBlock = null;
        double maxSlipperiness = 0.0;
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                for (int checkY = (int) Math.floor(newY - 0.90); checkY <= (int) Math.floor(newY - 0.01); checkY++) {
                    Block b = world.getBlockAt(bx, checkY, bz);
                    Material type = b.getType();
                    if (type.isSolid() || type == Material.COBWEB || type == Material.SWEET_BERRY_BUSH) {
                        double slip = PhysicsEngine.getSlipperiness(b);
                        if (slip > maxSlipperiness) {
                            maxSlipperiness = slip;
                            belowBlock = b;
                        }
                    }
                }
            }
        }
        if (belowBlock == null) {
            belowBlock = world.getBlockAt(baseX, baseY - 1, baseZ);
        }

        // Track last cobweb contact time for grace period
        if (this.isInWeb) {
            this.lastInWebTime = System.currentTimeMillis();
        }

        // Determine if player is trying to jump
        boolean jumping = !lastOnGround ? false : (actualDeltaY > 0.01);

        predictedVelocityY = PhysicsEngine.predictNextVelocityY(
                predictedVelocityY, lastOnGround, jumping, player, isInWeb, isInLiquid, isClimbing);

        boolean yValid = PhysicsEngine.isYDeltaValid(
                predictedVelocityY, actualDeltaY, yTolerance, isInWeb, isInLiquid, isClimbing);

        // resync physics during long falls because the server and client will slowly drift apart over long drops
        boolean inLegitFreeFall = !onGround
                && actualDeltaY < 0
                && actualDeltaY >= -PhysicsEngine.TERMINAL_VELOCITY
                && !isInLiquid && !isInWeb && !isClimbing;

        if (inLegitFreeFall) {
            freefallTicks++;
            // After a short ramp-up (5 ticks), continuously resync to avoid drift
            if (freefallTicks > 5) {
                // Treat as valid regardless of prediction match — resync below
                yValid = true;
            }
        } else {
            freefallTicks = 0;
        }

        // Ghost-block detection makes consecutiveYViolations work even when
        // NoFall sends onGround=true while airborne.
        boolean actuallyAirborne = !onGround || consecutiveGhostBlocks > 5;

        if (!yValid && actuallyAirborne) {
            consecutiveYViolations++;
        } else {
            consecutiveYViolations = Math.max(0, consecutiveYViolations - 1);
        }

        // reset vertical drift when landing
        if (onGround) {
            predictedVelocityY = 0;
            freefallTicks = 0;
        } else {
            // Update prediction based on what actually happened
            // to prevent drift from accumulating over many ticks.
            // Always resync during freefall to stay locked to the real client.
            if (yValid || inLegitFreeFall) {
                predictedVelocityY = actualDeltaY;
            }
        }

        predictedMaxSpeedH = PhysicsEngine.predictMaxHorizontalSpeed(
                player, onGround, player.isSprinting(), belowBlock, isInWeb, isInLiquid, isClimbing);

        boolean hValid = PhysicsEngine.isHorizontalSpeedValid(
                predictedMaxSpeedH, actualSpeedH, hTolerance, isInWeb, isInLiquid, isClimbing);

        if (!hValid) {
            consecutiveHViolations++;
        } else {
            consecutiveHViolations = Math.max(0, consecutiveHViolations - 1);
        }

        if (onGround) {
            boolean hasBlockBelow = false;
            // Expanded to 0.4 to prevent false ghost block flags on block edges
            int minBX = (int) Math.floor(newX - 0.4);
            int maxBX = (int) Math.floor(newX + 0.4);
            int minBZ = (int) Math.floor(newZ - 0.4);
            int maxBZ = (int) Math.floor(newZ + 0.4);

            for (int bx = minBX; bx <= maxBX; bx++) {
                for (int bz = minBZ; bz <= maxBZ; bz++) {
                    for (int checkY = (int) Math.floor(newY - 0.90); checkY <= (int) Math.floor(newY - 0.01); checkY++) {
                        Material mat = world.getBlockAt(bx, checkY, bz).getType();
                        // Solid blocks, or blocks that support the player (like cobweb or sweet berry bush)
                        if (mat.isSolid() || mat == Material.COBWEB || mat == Material.SWEET_BERRY_BUSH) {
                            hasBlockBelow = true;
                            break;
                        }
                    }
                    if (hasBlockBelow) break;
                }
                if (hasBlockBelow) break;
            }

            if (!hasBlockBelow) {
                consecutiveGhostBlocks++;
            } else {
                consecutiveGhostBlocks = 0;
            }
        } else {
            consecutiveGhostBlocks = 0;
        }

        if (!onGround && actualDeltaY < -0.1) {
            if (!isFalling) {
                fallStartY = lastY;
                isFalling = true;
            }
        }
        double fallDistance = 0;
        if (onGround && isFalling) {
            fallDistance = fallStartY - newY;
            isFalling = false;
        }

        // update state
        this.lastY = newY;
        this.lastOnGround = onGround;
        this.wasJumping = jumping;

        return new PhysicsResult(
                yValid, hValid,
                consecutiveYViolations, consecutiveHViolations, consecutiveGhostBlocks,
                actualDeltaY, actualSpeedH,
                predictedVelocityY, predictedMaxSpeedH,
                fallDistance
        );
    }

    // loosen tolerances if server is lagging or player has high ping
    public void adjustTolerance(double currentTPS, int playerPing) {
        // Base tolerance
        double tpsFactor = Math.max(0.5, 20.0 / Math.max(1, currentTPS)); // 1.0 at 20 TPS, higher at lower TPS
        double pingFactor = 1.0 + (playerPing / 500.0); // Add tolerance for high ping

        this.yTolerance = 0.05 * tpsFactor * pingFactor;
        this.hTolerance = 0.05 * tpsFactor * pingFactor;
    }

    public double getPredictedVelocityY() { return predictedVelocityY; }
    public double getPredictedMaxSpeedH() { return predictedMaxSpeedH; }
    public int getConsecutiveYViolations() { return consecutiveYViolations; }
    public int getConsecutiveHViolations() { return consecutiveHViolations; }
    public boolean isInLiquid() { return isInLiquid; }
    public boolean isInWeb() { return isInWeb; }
    public boolean isClimbing() { return isClimbing; }
    public long getLastInWebTime() { return lastInWebTime; }
    public boolean isInPowderSnow() { return isInPowderSnow; }
    public boolean inBubbleColumnUp() { return inBubbleColumnUp; }
    public boolean inBubbleColumnDown() { return inBubbleColumnDown; }
    public boolean isNearFlowingWater() { return isNearFlowingWater; }
    public boolean isNearWeb() { return isNearWeb; }
    public boolean isNearClimbable() { return isNearClimbable; }
    public boolean isBelowBlock() { return isBelowBlock; }
    public boolean isOnBouncyBlock() { return isOnBouncyBlock; }
    public boolean isOnIce() { return isOnIce; }
    public int getConsecutiveGhostBlocks() { return consecutiveGhostBlocks; }
    public int getFreefallTicks() { return freefallTicks; }

    private boolean isLiquid(Material mat) {
        return mat == Material.WATER || mat == Material.LAVA;
    }

    private boolean isClimbable(Material mat) {
        return mat == Material.LADDER || mat == Material.VINE || mat == Material.SCAFFOLDING
            || mat == Material.TWISTING_VINES || mat == Material.WEEPING_VINES || mat == Material.CAVE_VINES;
    }

    /**
     * Result of one physics tick simulation.
     */
    public record PhysicsResult(
            boolean yValid, boolean hValid,
            int consecutiveYViolations, int consecutiveHViolations, int ghostBlockTicks,
            double actualDeltaY, double actualSpeedH,
            double predictedVelY, double predictedMaxSpeedH,
            double fallDistance
    ) {}
}
