package org.legacy.aegis.check.impl.combat;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.legacy.aegis.check.AbstractCheck;
import org.legacy.aegis.check.CheckType;
import org.legacy.aegis.check.CombatCheck;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.profile.PlayerProfile;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// killaura A: two detection layers - identical delta pattern + GCD rotation analysis
public class KillauraA extends AbstractCheck implements CombatCheck {

    // constants    private static final int    MIN_SAMPLES                  = 5;
    private static final int    MAX_IDENTICAL_DELTAS         = 4;
    private static final double CLOSE_QUARTERS_DISTANCE      = 2.0;
    private static final int    MAX_IDENTICAL_DELTAS_CLOSE   = 7;
    private static final float  MACE_PITCH_SNAP_THRESHOLD    = 55.0f;
    private static final int    FLAG_BUFFER                  = 3;

    // GCD analysis constants    /** Minimum number of attack samples before the GCD layer may flag. */
    private static final int    GCD_MIN_SAMPLES              = 20;
    /**
     * Unused directly in flagging logic but documents the design intent:
     * if the coefficient of variation of dot-counts drops below 15 % the
     * movement is suspiciously consistent.
     */
    @SuppressWarnings("unused")
    private static final double GCD_CONSISTENCY_THRESHOLD    = 0.15;
    /** GCD values at or below this threshold are treated as human noise and ignored. */
    private static final double MIN_MEANINGFUL_GCD           = 0.001;

    // per-player state maps    private final Map<UUID, Float>   lastYawDeltaMap        = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> identicalDeltaCountMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> sampleCountMap         = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> bufferMap              = new ConcurrentHashMap<>();

    // GCD rotation analysis state maps
    private final Map<UUID, Double>  lastDeltaAbsYawMap     = new ConcurrentHashMap<>();
    private final Map<UUID, Double>  lastDeltaAbsPitchMap   = new ConcurrentHashMap<>();
    /** Exponential moving average of the per-attack yaw GCD. */
    private final Map<UUID, Double>  gcdModeYawMap          = new ConcurrentHashMap<>();
    /** Exponential moving average of the per-attack pitch GCD (reserved for future use). */
    private final Map<UUID, Double>  gcdModePitchMap        = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> gcdSampleCountMap      = new ConcurrentHashMap<>();


    public KillauraA(ConfigManager config) {
        super("Killaura (A)", CheckType.COMBAT, "killaura.a", config);
    }


    // iterative euclidean GCD for floats, stops when remainder drops below MIN_MEANINGFUL_GCD to avoid fp noise loops
    private double gcd(double a, double b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b > MIN_MEANINGFUL_GCD) {
            double t = b;
            b = a % b;
            a = t;
        }
        return a;
    }


    @Override
    public boolean processAttack(Player player, PlayerProfile profile, int targetId) {
        UUID  uuid         = player.getUniqueId();
        float currentYaw   = player.getLocation().getYaw();
        float currentPitch = player.getLocation().getPitch();

        float yawDelta   = Math.abs(currentYaw   - profile.getLastYaw());
        float pitchDelta = Math.abs(currentPitch - profile.getLastPitch());

        if (yawDelta > 180) yawDelta = 360 - yawDelta;

        // Real humans have sub-pixel mouse precision: their GCD values are tiny
        // and vary between attacks.  Killaura bots inject fixed rotation angles,
        // so their GCD stabilises at a large, consistent value and the ratio
        // |deltaYaw| / gcdMode is a near-perfect integer every attack.
        double absYaw   = Math.abs(currentYaw   - profile.getLastYaw());
        double absPitch = Math.abs(currentPitch - profile.getLastPitch());
        if (absYaw > 180) absYaw = 360 - absYaw;

        double lastAbsYaw   = lastDeltaAbsYawMap.getOrDefault(uuid, 0.0);
        double lastAbsPitch = lastDeltaAbsPitchMap.getOrDefault(uuid, 0.0);

        if (absYaw > 0.05 && lastAbsYaw > 0.05) {
            double gcdYaw = gcd(absYaw, lastAbsYaw);
            if (gcdYaw > MIN_MEANINGFUL_GCD) {
                // smooth GCD via EMA so one human-like rotation doesn't instantly clear the signal
                double currentGcdMode = gcdModeYawMap.getOrDefault(uuid, gcdYaw);
                currentGcdMode = currentGcdMode * 0.85 + gcdYaw * 0.15;
                gcdModeYawMap.put(uuid, currentGcdMode);

                int gcdSamples = gcdSampleCountMap.getOrDefault(uuid, 0) + 1;
                gcdSampleCountMap.put(uuid, gcdSamples);

                // only flag after enough samples so we don't catch coincidental large GCDs at session start
                if (gcdSamples >= GCD_MIN_SAMPLES && currentGcdMode > 0.05) {
                    // How many "mouse steps" did the bot rotate this tick?
                    double dotsYaw       = absYaw / currentGcdMode;
                    double fractionalPart = Math.abs(dotsYaw - Math.round(dotsYaw));

                    // fractional part < 0.02 = near-exact integer multiple of step size = bot behavior
                    if (fractionalPart < 0.02 && currentGcdMode > 0.1) {
                        int buf = bufferMap.getOrDefault(uuid, 0) + 1;
                        if (buf >= FLAG_BUFFER) {
                            // reset so flagged player gets a fresh window
                            bufferMap.put(uuid, 0);
                            gcdSampleCountMap.put(uuid, 0);
                            lastDeltaAbsYawMap.put(uuid, absYaw);
                            lastDeltaAbsPitchMap.put(uuid, absPitch);
                            return true;
                        }
                        bufferMap.put(uuid, buf);
                    }
                }
            }
        }
        lastDeltaAbsYawMap.put(uuid, absYaw);
        lastDeltaAbsPitchMap.put(uuid, absPitch);
        // end GCD analysis
        // original identical-delta check──
        int sampleCount = sampleCountMap.getOrDefault(uuid, 0) + 1;
        sampleCountMap.put(uuid, sampleCount);

        if (sampleCount < MIN_SAMPLES) {
            lastYawDeltaMap.put(uuid, yawDelta);
            return false;
        }

        boolean isUsingMace = player.getInventory().getItemInMainHand().getType().name().contains("MACE");
        boolean isCloseQuarters = false;
        Entity targetEntity = null;
        for (Entity nearby : player.getNearbyEntities(CLOSE_QUARTERS_DISTANCE, CLOSE_QUARTERS_DISTANCE, CLOSE_QUARTERS_DISTANCE)) {
            if (nearby.getEntityId() == targetId) {
                targetEntity = nearby;
                double distance = player.getLocation().toVector().distance(targetEntity.getLocation().toVector());
                isCloseQuarters = distance < CLOSE_QUARTERS_DISTANCE;
                break;
            }
        }

        float lastYawDelta       = lastYawDeltaMap.getOrDefault(uuid, 0f);
        int   identicalDeltaCount = identicalDeltaCountMap.getOrDefault(uuid, 0);
        int   buffer              = bufferMap.getOrDefault(uuid, 0);

        int maxIdentical = isCloseQuarters ? MAX_IDENTICAL_DELTAS_CLOSE : MAX_IDENTICAL_DELTAS;
        if (yawDelta > 0.5f && Math.abs(yawDelta - lastYawDelta) < 0.01f) {
            identicalDeltaCount++;
            if (identicalDeltaCount >= maxIdentical) {
                buffer++;
                if (buffer >= FLAG_BUFFER) {
                    identicalDeltaCountMap.put(uuid, 0);
                    sampleCountMap.put(uuid, 0);
                    bufferMap.put(uuid, 0);
                    lastYawDeltaMap.put(uuid, yawDelta);
                    return true;
                }
                identicalDeltaCountMap.put(uuid, 0);
                sampleCountMap.put(uuid, 0);
            }
        } else {
            identicalDeltaCount = Math.max(0, identicalDeltaCount - 1);
        }

        float pitchSnapThreshold = isUsingMace
                ? MACE_PITCH_SNAP_THRESHOLD
                : (isCloseQuarters ? 40.0f : 30.0f);

        if (pitchDelta > pitchSnapThreshold && yawDelta > 20.0f) {
            if (isUsingMace && !player.isOnGround() && currentPitch > 30.0f) {
                lastYawDeltaMap.put(uuid, yawDelta);
                identicalDeltaCountMap.put(uuid, identicalDeltaCount);
                return false;
            }
            buffer++;
            if (buffer >= FLAG_BUFFER) {
                sampleCountMap.put(uuid, 0);
                bufferMap.put(uuid, 0);
                lastYawDeltaMap.put(uuid, yawDelta);
                identicalDeltaCountMap.put(uuid, identicalDeltaCount);
                return true;
            }
            sampleCountMap.put(uuid, 0);
        }

        if (buffer > 0) bufferMap.put(uuid, buffer - 1);

        lastYawDeltaMap.put(uuid, yawDelta);
        identicalDeltaCountMap.put(uuid, identicalDeltaCount);
        return false;
    }

    @Override
    public void cleanup(UUID uuid) {
        lastYawDeltaMap.remove(uuid);
        identicalDeltaCountMap.remove(uuid);
        sampleCountMap.remove(uuid);
        bufferMap.remove(uuid);
        lastDeltaAbsYawMap.remove(uuid);
        lastDeltaAbsPitchMap.remove(uuid);
        gcdModeYawMap.remove(uuid);
        gcdModePitchMap.remove(uuid);
        gcdSampleCountMap.remove(uuid);
    }
}
