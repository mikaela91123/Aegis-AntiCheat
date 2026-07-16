package org.legacy.aegis.profile;

import org.legacy.aegis.physics.PlayerPhysicsTracker;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Represents a player's anti-cheat profile.
 * Tracks playtime, violations, Trust Factor, physics state,
 * evidence buffer, and rollback positions.
 */
public class PlayerProfile {

    private final UUID uuid;
    private final String username;

    private long totalPlaytimeMillis;
    private long sessionStartTime;
    private int totalViolations;
    private int recentViolations;
    private double trustFactor;

    private double lastX, lastY, lastZ;
    private float lastYaw, lastPitch;
    private long lastMoveTick;
    private boolean onGround;
    private boolean wasOnGround;
    private int airTicks;
    private int groundTicks;

    // Used by BadPacketsA to validate the actual packet values (not Bukkit-normalised)
    private float currentPacketYaw;
    private float currentPacketPitch;

    // True after the first movement packet is received (prevents world-origin bypass in NoFallA etc.)
    private boolean receivedFirstPacket = false;

    private boolean bedrockPlayer;
    private int damageTicks;
    private int teleportTicks;
    private volatile long lastAttackTime;

    private final Map<String, Integer> violationLevels = new ConcurrentHashMap<>();

    private final Deque<Long> clickTimestamps = new ArrayDeque<>();

    private final Deque<TickSnapshot> evidenceBuffer = new ArrayDeque<>();
    private int maxBufferSize = 200;
    private List<TickSnapshot> frozenEvidence;

    private final PlayerPhysicsTracker physicsTracker = new PlayerPhysicsTracker();

    private final Deque<double[]> safePositions = new ConcurrentLinkedDeque<>();
    private static final int MAX_SAFE_POSITIONS = 20;
    private String lastSafeWorld;

    private final Map<String, Long> vlThresholdReachedAt = new ConcurrentHashMap<>();

    private int rotationCount;
    private long rotationTickStart;

    private boolean verticalSuspicious;
    private double verticalDeltaPrevious;
    private int verticalSuspiciousTick;
    private int currentTick;
    private double lastDeltaY;

    private volatile boolean cancelNextAttack = false;

    public PlayerProfile(UUID uuid, String username) {
        this.uuid = uuid;
        this.username = username;
        this.totalPlaytimeMillis = 0;
        this.sessionStartTime = System.currentTimeMillis();
        this.totalViolations = 0;
        this.recentViolations = 0;
        this.trustFactor = 5.0;
        this.bedrockPlayer = false;
        this.damageTicks = Integer.MAX_VALUE;
        this.teleportTicks = Integer.MAX_VALUE;
    }


    /**
     * Calculate Trust Factor using a time-decayed violation penalty.
     *
     * Formula: TF = (playtimeWeight × hours) - (decayedViolations × penalty) + base
     *
     * Violations are decayed exponentially: they halve every 50 hours of play.
     * This prevents permanent stigma for players who have reformed.
     */
    public double calculateTrustFactor(double playtimeWeight, double violationPenalty,
                                       double baseScore, double maxScore) {
        long currentPlaytime = totalPlaytimeMillis + (System.currentTimeMillis() - sessionStartTime);
        double playtimeHours = currentPlaytime / 3_600_000.0;

        // Exponential decay: violations halve every 50 hours of gameplay
        double decayFactor = Math.exp(-playtimeHours / 50.0);
        double decayedViolations = totalViolations * decayFactor;

        double tf = (playtimeWeight * playtimeHours) - (decayedViolations * violationPenalty) + baseScore;
        tf = Math.max(0, Math.min(maxScore, tf));
        this.trustFactor = tf;
        return tf;
    }

    public int getEffectiveMaxVl(int baseMaxVl, double trustMultiplier, double trustedMinimum) {
        if (trustFactor >= trustedMinimum) {
            return (int) (baseMaxVl * trustMultiplier);
        }
        return baseMaxVl;
    }

    public void markThresholdReached(String checkName) {
        vlThresholdReachedAt.put(checkName, System.currentTimeMillis());
    }

    public boolean wasThresholdRecentlyReached(String checkName, long withinMs) {
        Long reachedAt = vlThresholdReachedAt.get(checkName);
        if (reachedAt == null) return false;
        return System.currentTimeMillis() - reachedAt < withinMs;
    }

    public void endSession() {
        totalPlaytimeMillis += (System.currentTimeMillis() - sessionStartTime);
    }


    public int getViolationLevel(String checkName) {
        return violationLevels.getOrDefault(checkName, 0);
    }

    public int addViolation(String checkName) {
        int newVl = violationLevels.merge(checkName, 1, Integer::sum);
        totalViolations++;
        recentViolations++;
        return newVl;
    }

    public void resetViolation(String checkName) {
        violationLevels.put(checkName, 0);
    }

    /**
     * Set the violation level for a check directly.
     * Used by ViolationManager for partial VL reset after an early setback.
     */
    public void setViolationLevel(String checkName, int level) {
        violationLevels.put(checkName, Math.max(0, level));
    }

    /**
     * Decay all per-check violation levels AND recentViolations.
     * Called periodically to prevent false accumulation from lag spikes.
     */
    public void decayViolations() {
        violationLevels.replaceAll((check, vl) -> Math.max(0, vl - 1));
        // Also decay the session recent violations counter
        if (recentViolations > 0) {
            recentViolations = Math.max(0, recentViolations - 1);
        }
    }


    public java.util.Deque<Long> getClickTimestamps() {
        return clickTimestamps;
    }

    public void registerClick() {
        long now = System.currentTimeMillis();
        clickTimestamps.addLast(now);
        // Keep 2 seconds — AutoClickerA needs this window for statistical deviation analysis
        while (!clickTimestamps.isEmpty() && now - clickTimestamps.peekFirst() > 2000) {
            clickTimestamps.pollFirst();
        }
    }

    public int getCurrentCPS() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (long ts : clickTimestamps) {
            if (now - ts <= 1000) count++;
        }
        return count;
    }

    /**
     * Returns a snapshot copy of click timestamps (up to 2 seconds history).
     * Used by AutoClickerA for standard deviation analysis.
     * AutoClickerA no longer maintains its own separate click map.
     */
    public List<Long> getClickTimestampsSnapshot() {
        return new ArrayList<>(clickTimestamps);
    }


    public void recordTick(double x, double y, double z, float yaw, float pitch, boolean onGround) {
        TickSnapshot snapshot = new TickSnapshot(x, y, z, yaw, pitch, onGround, System.currentTimeMillis());
        evidenceBuffer.addLast(snapshot);
        while (evidenceBuffer.size() > maxBufferSize) {
            evidenceBuffer.pollFirst();
        }
    }

    public List<TickSnapshot> getEvidenceSnapshot() {
        return new ArrayList<>(evidenceBuffer);
    }

    public List<TickSnapshot> freezeEvidence() {
        this.frozenEvidence = new ArrayList<>(evidenceBuffer);
        return this.frozenEvidence;
    }

    public List<TickSnapshot> getFrozenEvidence() {
        return frozenEvidence != null ? frozenEvidence : new ArrayList<>();
    }


    public PlayerPhysicsTracker getPhysicsTracker() {
        return physicsTracker;
    }


    public void recordSafePosition(double x, double y, double z, String worldName) {
        safePositions.addLast(new double[]{x, y, z});
        this.lastSafeWorld = worldName;
        while (safePositions.size() > MAX_SAFE_POSITIONS) {
            safePositions.pollFirst();
        }
    }


    public boolean shouldCancelNextAttack() {
        return cancelNextAttack;
    }

    public void setCancelNextAttack(boolean cancelNextAttack) {
        this.cancelNextAttack = cancelNextAttack;
    }


    public double[] getLastSafePosition() {
        return safePositions.peekLast();
    }

    public double[] getSafePositionAgo(int ticksAgo) {
        if (safePositions.isEmpty()) return null;
        int index = Math.max(0, safePositions.size() - 1 - ticksAgo);
        int i = 0;
        for (double[] pos : safePositions) {
            if (i == index) return pos;
            i++;
        }
        return safePositions.peekLast();
    }

    public String getLastSafeWorld() {
        return lastSafeWorld;
    }


    public void tickExemptions() {
        if (damageTicks < Integer.MAX_VALUE) damageTicks++;
        if (teleportTicks < Integer.MAX_VALUE) teleportTicks++;
    }

    public void onDamage() {
        this.damageTicks = 0;
    }

    public void onTeleport() {
        this.teleportTicks = 0;
        physicsTracker.reset();
    }


    public UUID getUuid() { return uuid; }
    public String getUsername() { return username; }
    public long getTotalPlaytimeMillis() { return totalPlaytimeMillis; }
    public void setTotalPlaytimeMillis(long millis) { this.totalPlaytimeMillis = millis; }
    public int getTotalViolations() { return totalViolations; }
    public void setTotalViolations(int v) { this.totalViolations = v; }
    public int getRecentViolations() { return recentViolations; }
    public void setRecentViolations(int v) { this.recentViolations = v; }
    public double getTrustFactor() { return trustFactor; }
    public void setTrustFactor(double tf) { this.trustFactor = tf; }

    public double getLastX() { return lastX; }
    public double getLastY() { return lastY; }
    public double getLastZ() { return lastZ; }
    public float getLastYaw() { return lastYaw; }
    public float getLastPitch() { return lastPitch; }
    public long getLastMoveTick() { return lastMoveTick; }
    public boolean isOnGround() { return onGround; }
    public boolean wasOnGround() { return wasOnGround; }
    public int getAirTicks() { return airTicks; }
    public int getGroundTicks() { return groundTicks; }
    public boolean isBedrockPlayer() { return bedrockPlayer; }
    public int getDamageTicks() { return damageTicks; }
    public int getTeleportTicks() { return teleportTicks; }
    public long getLastAttackTime() { return lastAttackTime; }

    /** True after the first movement packet has been received for this session. */
    public boolean hasReceivedFirstPacket() { return receivedFirstPacket; }

    /** Raw packet yaw — set directly from the incoming packet before Bukkit normalises it. */
    public float getCurrentPacketYaw() { return currentPacketYaw; }
    /** Raw packet pitch — set directly from the incoming packet before Bukkit normalises it. */
    public float getCurrentPacketPitch() { return currentPacketPitch; }

    public void setLastPosition(double x, double y, double z) {
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
        this.receivedFirstPacket = true; // mark profile as initialised
    }

    public void setLastRotation(float yaw, float pitch) {
        this.lastYaw = yaw;
        this.lastPitch = pitch;
    }

    /** Store raw packet rotation values BEFORE checks run so BadPacketsA can read them. */
    public void setCurrentPacketRotation(float yaw, float pitch) {
        this.currentPacketYaw = yaw;
        this.currentPacketPitch = pitch;
    }

    public void setLastMoveTick(long tick) { this.lastMoveTick = tick; }

    public void setOnGround(boolean onGround) {
        this.wasOnGround = this.onGround;
        this.onGround = onGround;
        if (onGround) { groundTicks++; airTicks = 0; }
        else { airTicks++; groundTicks = 0; }
    }

    public void setBedrockPlayer(boolean bedrock) { this.bedrockPlayer = bedrock; }
    public void setLastAttackTime(long time) { this.lastAttackTime = time; }
    public void setMaxBufferSize(int size) { this.maxBufferSize = size; }


    public int getRotationCount() { return rotationCount; }
    public void setRotationCount(int count) { this.rotationCount = count; }
    public void incrementRotationCount() { this.rotationCount++; }
    public void resetRotationCount() { this.rotationCount = 0; }
    public long getRotationTickStart() { return rotationTickStart; }
    public void setRotationTickStart(long time) { this.rotationTickStart = time; }


    public boolean isVerticalSuspicious() { return verticalSuspicious; }
    public void setVerticalSuspicious(boolean suspicious) { this.verticalSuspicious = suspicious; }
    public double getVerticalDeltaPrevious() { return verticalDeltaPrevious; }
    public void setVerticalDeltaPrevious(double delta) { this.verticalDeltaPrevious = delta; }
    public int getVerticalSuspiciousTick() { return verticalSuspiciousTick; }
    public void setVerticalSuspiciousTick(int tick) { this.verticalSuspiciousTick = tick; }
    public int getCurrentTick() { return currentTick; }
    public void incrementCurrentTick() { this.currentTick++; }
    public double getLastDeltaY() { return lastDeltaY; }
    public void setLastDeltaY(double deltaY) { this.lastDeltaY = deltaY; }


    public record TickSnapshot(double x, double y, double z, float yaw, float pitch,
                                boolean onGround, long timestamp) {}
}
