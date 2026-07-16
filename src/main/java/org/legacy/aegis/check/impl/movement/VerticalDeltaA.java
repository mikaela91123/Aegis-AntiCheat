package org.legacy.aegis.check.impl.movement;

import org.bukkit.entity.Player;
import org.legacy.aegis.Aegis;
import org.legacy.aegis.check.AbstractCheck;
import org.legacy.aegis.check.CheckType;
import org.legacy.aegis.check.MovementCheck;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.profile.PlayerProfile;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VerticalDeltaA extends AbstractCheck implements MovementCheck {

    private static final double MAX_RISE = 6.0;
    private static final double MATCH_WINDOW_TICKS = 2;
    private static final double MIN_FALL_DISTANCE = 8.0;
    private static final double TERMINAL_VELOCITY = -8.0;
    private static final int TPS_GRACE = 40;
    private static final int FLAG_BUFFER = 1;

    private final Map<UUID, Integer> bufferMap = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastHugeFallY = new ConcurrentHashMap<>();

    public VerticalDeltaA(ConfigManager config) {
        super("VerticalDelta (A)", CheckType.MOVEMENT, "verticaldelta.a", config);
    }

    @Override
    public boolean processMovement(Player player, PlayerProfile profile,
                                    double x, double y, double z, boolean onGround) {
        UUID uuid = player.getUniqueId();

        if (!profile.hasReceivedFirstPacket()) return false;
        if (profile.getTeleportTicks() < TPS_GRACE) return false;
        if (player.getPing() > 350) return false;

        double deltaY = y - profile.getLastY();

        // if deltaY is absurdly large the player was teleported without our listener catching it
        // treat it as a position reset and skip
        if (Math.abs(deltaY) > 10.0) {
            profile.setVerticalSuspicious(false);
            profile.setVerticalDeltaPrevious(0);
            profile.setVerticalSuspiciousTick(0);
            lastHugeFallY.remove(uuid);
            return false;
        }

        boolean suspicious = profile.isVerticalSuspicious();
        double previousDelta = profile.getVerticalDeltaPrevious();
        int suspiciousTick = profile.getVerticalSuspiciousTick();

        // detection 1: teleport-up then teleport-down in two consecutive packets
        // only flags if both rise and fall are within MATCH_WINDOW_TICKS so riptide/knockback doesn't trigger it
        if (suspicious
                && deltaY < -MAX_RISE
                && previousDelta > MAX_RISE
                && profile.getCurrentTick() - suspiciousTick <= MATCH_WINDOW_TICKS) {
            double rise = Math.abs(previousDelta);
            double fall = Math.abs(deltaY);
            if (Math.abs(rise - fall) < 1.5) {
                return flagAndSetback(player, profile, uuid);
            }
        }

        // detection 2: huge single-packet fall
        if (deltaY < -MIN_FALL_DISTANCE) {
            return flagAndSetback(player, profile, uuid);
        }

        // detection 3: two-packet fall then claims ground
        Double fallY = lastHugeFallY.get(uuid);
        if (fallY != null && onGround && Math.abs(y - fallY) < 0.1) {
            lastHugeFallY.remove(uuid);
            return flagAndSetback(player, profile, uuid);
        }
        if (deltaY < -MIN_FALL_DISTANCE && !onGround) {
            lastHugeFallY.put(uuid, y);
        } else {
            lastHugeFallY.remove(uuid);
        }

        // detection 4: fell faster than terminal velocity
        if (onGround && deltaY < TERMINAL_VELOCITY) {
            return flagAndSetback(player, profile, uuid);
        }

        // mark suspicious if sudden rise so we can check for matching fall next tick
        if (!suspicious && deltaY > MAX_RISE) {
            profile.setVerticalSuspicious(true);
            profile.setVerticalDeltaPrevious(deltaY);
            profile.setVerticalSuspiciousTick(profile.getCurrentTick());
            return false;
        }

        // legit ground contact, clear suspicious state
        if (onGround && deltaY >= 0) {
            profile.setVerticalSuspicious(false);
            profile.setVerticalDeltaPrevious(0);
            profile.setVerticalSuspiciousTick(0);
        }

        int buffer = bufferMap.getOrDefault(uuid, 0);
        if (buffer > 0) bufferMap.put(uuid, buffer - 1);

        return false;
    }

    private boolean flagAndSetback(Player player, PlayerProfile profile, UUID uuid) {
        Aegis plugin = Aegis.get();
        if (plugin.getRollbackManager() != null) {
            plugin.getRollbackManager().setback(player, profile, 0);
        }
        player.setFallDistance(0);
        profile.setVerticalSuspicious(false);
        profile.setVerticalDeltaPrevious(0);
        profile.setVerticalSuspiciousTick(0);

        int buffer = bufferMap.getOrDefault(uuid, 0) + 1;
        if (buffer >= FLAG_BUFFER) {
            bufferMap.put(uuid, 0);
            return true;
        }
        bufferMap.put(uuid, buffer);
        return false;
    }

    @Override
    public void cleanup(UUID uuid) {
        bufferMap.remove(uuid);
        lastHugeFallY.remove(uuid);
    }
}
