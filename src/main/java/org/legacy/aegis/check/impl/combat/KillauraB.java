package org.legacy.aegis.check.impl.combat;

import org.bukkit.entity.Player;
import org.legacy.aegis.check.AbstractCheck;
import org.legacy.aegis.check.CheckType;
import org.legacy.aegis.check.CombatCheck;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.profile.PlayerProfile;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// killaura B: detects rapid multi-target switching (crystal pvp and mace slam excluded)
public class KillauraB extends AbstractCheck implements CombatCheck {

    // Max target switches allowed within the time window
    private static final int MAX_TARGET_SWITCHES = 7;       // was 4 — allows group PvP
    // Time window in milliseconds
    private static final long TIME_WINDOW_MS = 600;          // was 1000ms — tighter window
    // Grace period after crystal explosion (ms)
    private static final long CRYSTAL_BLAST_GRACE = 2500L;
    // Buffer system: require multiple flags before reporting
    private static final int FLAG_BUFFER = 3;

    private final Map<UUID, Integer> lastTargetIdMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> targetSwitchCountMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> windowStartTimeMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCrystalBlastMap = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> bufferMap = new ConcurrentHashMap<>();

    public KillauraB(ConfigManager config) {
        super("Killaura (B)", CheckType.COMBAT, "killaura.b", config);
    }

    @Override
    public boolean processAttack(Player player, PlayerProfile profile, int targetId) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // End Crystal explosions cause damage to nearby entities that gets
        // processed as attack events. Skip check during crystal PvP.
        long lastCrystalBlast = lastCrystalBlastMap.getOrDefault(uuid, 0L);
        if (now - lastCrystalBlast < CRYSTAL_BLAST_GRACE) {
        // crystal blast triggers server-side attack events, reset tracking and skip
            lastTargetIdMap.put(uuid, targetId);
            targetSwitchCountMap.put(uuid, 0);
            bufferMap.put(uuid, 0);
            return false;
        }

        // Mace with Wind Burst enchantment hits multiple entities with AOE
        boolean isUsingMace = player.getInventory().getItemInMainHand().getType().name().contains("MACE");
        if (isUsingMace && !player.isOnGround()) {
            // mace slam hits multiple entities, legit
            lastTargetIdMap.put(uuid, targetId);
            targetSwitchCountMap.put(uuid, 0);
            return false;
        }

        // crystal blast also resets damageTicks so this catches it too
        if (profile.getDamageTicks() < 10) {
            lastTargetIdMap.put(uuid, targetId);
            targetSwitchCountMap.put(uuid, 0);
            return false;
        }

        int lastTargetId = lastTargetIdMap.getOrDefault(uuid, -1);
        int targetSwitchCount = targetSwitchCountMap.getOrDefault(uuid, 0);
        long windowStartTime = windowStartTimeMap.getOrDefault(uuid, 0L);
        int buffer = bufferMap.getOrDefault(uuid, 0);

        // Reset window if expired
        if (now - windowStartTime > TIME_WINDOW_MS) {
            windowStartTimeMap.put(uuid, now);
            targetSwitchCountMap.put(uuid, 0);
            lastTargetIdMap.put(uuid, targetId);
            return false;
        }

        // Check if target changed
        if (targetId != lastTargetId && lastTargetId != -1) {
            targetSwitchCount++;
            lastTargetIdMap.put(uuid, targetId);

            // Flag if too many switches in the time window
            if (targetSwitchCount >= MAX_TARGET_SWITCHES) {
                buffer++;
                if (buffer >= FLAG_BUFFER) {
                    targetSwitchCountMap.put(uuid, 0);
                    windowStartTimeMap.put(uuid, now);
                    bufferMap.put(uuid, 0);
                    return true;
                }
                targetSwitchCountMap.put(uuid, 0);
                windowStartTimeMap.put(uuid, now);
                bufferMap.put(uuid, buffer);
                return false;
            }
        }

        // Decay buffer when not violating
        if (buffer > 0) {
            bufferMap.put(uuid, buffer - 1);
        }

        lastTargetIdMap.put(uuid, targetId);
        targetSwitchCountMap.put(uuid, targetSwitchCount);
        return false;
    }

    // crystal explosion triggers damage events that look like target switches, skip
    public void onCrystalBlast(UUID uuid) {
        lastCrystalBlastMap.put(uuid, System.currentTimeMillis());
    }

    @Override
    public void cleanup(java.util.UUID uuid) {
        lastTargetIdMap.remove(uuid);
        targetSwitchCountMap.remove(uuid);
        windowStartTimeMap.remove(uuid);
        lastCrystalBlastMap.remove(uuid);
        bufferMap.remove(uuid);
    }
}
