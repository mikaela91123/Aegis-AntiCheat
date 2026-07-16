package org.legacy.aegis.check.impl.combat;

import org.bukkit.entity.Player;
import org.legacy.aegis.check.AbstractCheck;
import org.legacy.aegis.check.CheckType;
import org.legacy.aegis.check.CombatCheck;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.profile.PlayerProfile;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// autoclicker A: looks for inhuman click consistency via standard deviation
public class AutoClickerA extends AbstractCheck implements CombatCheck {

    private static final int MIN_SAMPLES = 10;
    private static final double MIN_HUMAN_DEVIATION = 15.0; // Lowered to 15ms
    private static final double MACE_MIN_DEVIATION = 10.0;
    private static final int FLAG_BUFFER = 3;

    private final Map<UUID, Integer> bufferMap = new ConcurrentHashMap<>();

    public AutoClickerA(ConfigManager config) {
        super("AutoClicker (A)", CheckType.COMBAT, "autoclicker.a", config);
    }

    @Override
    public boolean processAttack(Player player, PlayerProfile profile, int targetId) {
        UUID uuid = player.getUniqueId();
        Deque<Long> clickTimes = profile.getClickTimestamps();

        if (clickTimes.size() < MIN_SAMPLES) {
            return false;
        }

        boolean isUsingMace = player.getInventory().getItemInMainHand().getType().name().contains("MACE");

        // Check 1: Raw CPS
        int maxCps = config.getCheckInt("autoclicker.a", "max-cps", 22);
        if (isUsingMace) maxCps += 6;

        int currentCps = profile.getCurrentCPS();
        if (currentCps > maxCps) {
            int buffer = bufferMap.getOrDefault(uuid, 0) + 1;
            if (buffer >= FLAG_BUFFER) {
                bufferMap.put(uuid, 0);
                return true;
            }
            bufferMap.put(uuid, buffer);
            return false;
        }

        // Check 2: Standard deviation
        double stdDev = calculateClickDeviation(clickTimes);
        double minDeviation = isUsingMace ? MACE_MIN_DEVIATION : MIN_HUMAN_DEVIATION;

        if (stdDev >= 0 && stdDev < minDeviation && clickTimes.size() >= MIN_SAMPLES) {
            int minCpsForFlag = isUsingMace ? 14 : 13; // Lowered CPS requirement for flag
            if (currentCps >= minCpsForFlag) {
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

    private double calculateClickDeviation(Deque<Long> clickTimes) {
        if (clickTimes.size() < 3) return -1;
        Long[] times = clickTimes.toArray(new Long[0]);
        double[] intervals = new double[times.length - 1];
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = times[i + 1] - times[i];
        }
        double mean = 0;
        for (double interval : intervals) mean += interval;
        mean /= intervals.length;
        double variance = 0;
        for (double interval : intervals) variance += (interval - mean) * (interval - mean);
        variance /= intervals.length;
        return Math.sqrt(variance);
    }

    @Override
    public void cleanup(UUID uuid) {
        bufferMap.remove(uuid);
    }
}
