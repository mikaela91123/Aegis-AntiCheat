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

// timer check: detects clients sending packets faster than 20/sec using nanosecond balance tracking
public class TimerA extends AbstractCheck implements MovementCheck {

    // 50ms per tick in nanos
    private static final long TICK_NANOS = 50_000_000L;

    // max credit a player can build up; ping*2 + 2s base
    // without this they could stand still and get free "fast" packets later
    private static final long BASE_DRIFT_NANOS = 2_000_000_000L;

    // start 120s behind, player has to "earn" their way to real time
    private static final long INITIAL_OFFSET_NANOS = 120_000_000_000L;

    // 10ms buffer before we flag
    private static final long OVERSHOOT_THRESHOLD = 10_000_000L;

    private final Map<UUID, Long> timerBalanceMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> bufferMap = new ConcurrentHashMap<>();

    public TimerA(ConfigManager config) {
        super("Timer (A)", CheckType.MOVEMENT, "timer.a", config);
    }

    @Override
    public boolean processMovement(Player player, PlayerProfile profile,
                                   double x, double y, double z, boolean onGround) {
        // Vehicles send their own movement packets; don't count them against the player
        if (player.isInsideVehicle()) return false;

        UUID uuid = player.getUniqueId();
        long nanoNow = System.nanoTime();

        // Initialize: start 120 seconds behind real time on first packet
        long timerBalance = timerBalanceMap.getOrDefault(uuid, nanoNow - INITIAL_OFFSET_NANOS);

        // Each movement packet "costs" one tick (50ms)
        timerBalance += TICK_NANOS;

        boolean flagged = false;

        // If balance has surpassed real time beyond threshold, player may be sending too fast
        if (timerBalance > nanoNow + OVERSHOOT_THRESHOLD) {
            int buffer = bufferMap.getOrDefault(uuid, 0) + 1;
            if (buffer >= 3) {
                bufferMap.put(uuid, 0);
                timerBalance = nanoNow;
                flagged = true;
            } else {
                bufferMap.put(uuid, buffer);
                // Cap at real time so we don't accumulate runaway balance
                timerBalance = nanoNow;
            }
        } else {
            int buffer = bufferMap.getOrDefault(uuid, 0);
            if (buffer > 0) bufferMap.put(uuid, buffer - 1);
        }

        // Limit how far behind real time the balance can fall.
        // Ping-aware: high-ping players naturally have packets arrive in bursts.
        // We allow: ping * 2ms (RTT approximation) + 2000ms base buffer
        int ping = player.getPing();
        if (ping <= 0 || ping > 3000) ping = 200; // sane default for broken getPing()
        long pingNanos = Math.min((long) ping, 1000L) * 2_000_000L;
        long maxDrift = BASE_DRIFT_NANOS + pingNanos;
        timerBalance = Math.max(timerBalance, nanoNow - maxDrift);

        timerBalanceMap.put(uuid, timerBalance);
        return flagged;
    }

    @Override
    public void cleanup(UUID uuid) {
        timerBalanceMap.remove(uuid);
        bufferMap.remove(uuid);
    }
}
