package org.legacy.aegis.check.impl.packet;

import org.bukkit.entity.Player;
import org.legacy.aegis.check.AbstractCheck;
import org.legacy.aegis.check.CheckType;
import org.legacy.aegis.check.PacketCheck;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.profile.PlayerProfile;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// rotation A: spamming rotation packets too fast
public class RotationA extends AbstractCheck implements PacketCheck {

    private static final int FLAG_BUFFER = 5;

    // skip during actual combat (mace slam, jitter click, etc)
    private static final long COMBAT_SKIP_MS = 5000;

    // cooldown grace period after combat ends
    private static final long COMBAT_COOLDOWN_MS = 10000;

    // threshold multipliers
    private static final int COOLDOWN_MULTIPLIER = 3;

    private final Map<UUID, Integer> bufferMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCombatEndMap = new ConcurrentHashMap<>();

    public RotationA(ConfigManager config) {
        super("Rotation (A)", CheckType.PACKET, "rotation.a", config);
    }

    @Override
    public boolean processPacket(Player player, PlayerProfile profile,
                                  double x, double y, double z, boolean onGround) {
        UUID uuid = player.getUniqueId();
        int count = profile.getRotationCount();
        int maxPerTick = config.getCheckInt(getConfigPath(), "max-per-tick", 3);

        if (player.getPing() > 350) {
            decayBuffer(uuid);
            return false;
        }

        long now = System.currentTimeMillis();
        long lastAttack = profile.getLastAttackTime();
        int damageTicks = profile.getDamageTicks();

        // skip if actively fighting
        if (lastAttack > 0 && now - lastAttack < COMBAT_SKIP_MS) {
            decayBuffer(uuid);
            lastCombatEndMap.put(uuid, now);
            return false;
        }
        if (damageTicks < 100) {
            decayBuffer(uuid);
            lastCombatEndMap.put(uuid, now);
            return false;
        }

        // higher limit during cooldown
        Long combatEnd = lastCombatEndMap.get(uuid);
        boolean inCooldown = combatEnd != null && (now - combatEnd) < COMBAT_COOLDOWN_MS;

        int effectiveMax = inCooldown ? maxPerTick * COOLDOWN_MULTIPLIER : maxPerTick;

        if (count > effectiveMax) {
            int buffer = bufferMap.getOrDefault(uuid, 0) + 1;
            if (buffer >= FLAG_BUFFER) {
                bufferMap.put(uuid, 0);
                profile.resetRotationCount();
                return true;
            }
            bufferMap.put(uuid, buffer);
            return false;
        }

        decayBuffer(uuid);
        return false;
    }

    private void decayBuffer(UUID uuid) {
        int buffer = bufferMap.getOrDefault(uuid, 0);
        if (buffer > 0) bufferMap.put(uuid, buffer - 1);
    }

    @Override
    public void cleanup(UUID uuid) {
        bufferMap.remove(uuid);
        lastCombatEndMap.remove(uuid);
    }
}
