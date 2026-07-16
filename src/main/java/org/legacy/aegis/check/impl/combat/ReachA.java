package org.legacy.aegis.check.impl.combat;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.legacy.aegis.check.AbstractCheck;
import org.legacy.aegis.check.CheckType;
import org.legacy.aegis.check.CombatCheck;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.profile.PlayerProfile;
import org.legacy.aegis.profile.ProfileManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// reach A: rewind-hitbox, accounts for both attacker and victim ping
public class ReachA extends AbstractCheck implements CombatCheck {

    private final ProfileManager profileManager;

    private final Map<UUID, Integer> bufferMap = new ConcurrentHashMap<>();
    private final Map<UUID, Double> cancelBufferMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMaceSwapTime = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastHeldItemType = new ConcurrentHashMap<>();

    private static final long MACE_SWAP_GRACE = 400L;
    private static final double MACE_FALL_VERTICAL_BUFFER = 1.5;

    public ReachA(ConfigManager config, ProfileManager profileManager) {
        super("Reach (A)", CheckType.COMBAT, "reach.a", config);
        this.profileManager = profileManager;
    }

    @Override
    public boolean processAttack(Player player, PlayerProfile profile, int targetId) {
        if (player.getGameMode() == GameMode.CREATIVE) return false;

        Entity target = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getEntityId() == targetId) { target = p; break; }
        }
        if (target == null) return false;
        if (!(target instanceof org.bukkit.entity.LivingEntity)) return false;

        // find min distance across victim's recent positions
        PlayerProfile targetProfile = profileManager.getProfile(target.getUniqueId());
        Vector eyePos = player.getEyeLocation().toVector();
        double minDistance = Double.MAX_VALUE;

        if (targetProfile != null) {
            long now = System.currentTimeMillis();
            int victimPing = 0;
            Player vp = Bukkit.getPlayer(targetProfile.getUuid());
            if (vp != null) victimPing = vp.getPing();

            // check time window based on combined ping of attacker and victim
            long maxWindow = (long)(player.getPing() * 0.6 + victimPing * 0.4) + 300;

            for (PlayerProfile.TickSnapshot snapshot : targetProfile.getEvidenceSnapshot()) {
                long timeDiff = now - snapshot.timestamp();
                if (timeDiff > maxWindow || timeDiff < -50) continue;

                double minX = snapshot.x() - 0.35;
                double maxX = snapshot.x() + 0.35;
                double minY = snapshot.y();           // feet level
                double maxY = snapshot.y() + 1.85;   // head level (1.8 + 0.05 fp buffer)
                double minZ = snapshot.z() - 0.35;
                double maxZ = snapshot.z() + 0.35;

                double dx = Math.max(minX - eyePos.getX(), Math.max(0, eyePos.getX() - maxX));
                double dy = Math.max(minY - eyePos.getY(), Math.max(0, eyePos.getY() - maxY));
                double dz = Math.max(minZ - eyePos.getZ(), Math.max(0, eyePos.getZ() - maxZ));
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist < minDistance) minDistance = dist;
            }
        }

        if (minDistance == Double.MAX_VALUE) {
            BoundingBox box = target.getBoundingBox().clone();
            box.expand(0.05);
            double dx = Math.max(box.getMinX() - eyePos.getX(), Math.max(0, eyePos.getX() - box.getMaxX()));
            double dy = Math.max(box.getMinY() - eyePos.getY(), Math.max(0, eyePos.getY() - box.getMaxY()));
            double dz = Math.max(box.getMinZ() - eyePos.getZ(), Math.max(0, eyePos.getZ() - box.getMaxZ()));
            minDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        double maxReach = config.getCheckDouble("reach.a", "max-reach", 3.05);

        int attackerPing = player.getPing();
        int victimPing2 = 0;
        if (targetProfile != null) {
            Player vp = Bukkit.getPlayer(targetProfile.getUuid());
            if (vp != null) victimPing2 = vp.getPing();
        }
        maxReach += (attackerPing + victimPing2) * 0.0025;

        UUID uuid = player.getUniqueId();

        if (player.isSprinting()) maxReach += 0.12;

        String currentItemType = player.getInventory().getItemInMainHand().getType().name();
        String previousItemType = lastHeldItemType.getOrDefault(uuid, currentItemType);
        boolean isUsingMace = currentItemType.contains("MACE");
        boolean wasUsingSpear = previousItemType.contains("TRIDENT") || previousItemType.contains("SPEAR");

        if (isUsingMace && wasUsingSpear && !previousItemType.equals(currentItemType)) {
            lastMaceSwapTime.put(uuid, System.currentTimeMillis());
            bufferMap.put(uuid, 0);
            cancelBufferMap.put(uuid, 0.0);
        }
        lastHeldItemType.put(uuid, currentItemType);

        long lastSwapTime = lastMaceSwapTime.getOrDefault(uuid, 0L);
        if (isUsingMace && System.currentTimeMillis() - lastSwapTime < MACE_SWAP_GRACE) {
            bufferMap.put(uuid, 0);
            cancelBufferMap.put(uuid, 0.0);
            return false;
        }

        if (isUsingMace && !player.isOnGround()) {
            maxReach += 0.3;
            double fallDistance = player.getFallDistance();
            if (fallDistance > 1.5) {
                maxReach += Math.min(MACE_FALL_VERTICAL_BUFFER, fallDistance * 0.3);
            }
        }

        int buffer = bufferMap.getOrDefault(uuid, 0);
        double cancelBuffer = cancelBufferMap.getOrDefault(uuid, 0.0);

        if (minDistance > maxReach) {
            cancelBuffer = Math.min(1.0, cancelBuffer + 0.25);
            buffer++;
            if (buffer > 3) {
                bufferMap.put(uuid, buffer);
                cancelBufferMap.put(uuid, cancelBuffer);
                return true;
            }
        } else {
            buffer = Math.max(0, buffer - 1);
            cancelBuffer = Math.max(0, cancelBuffer - 0.25);
        }

        bufferMap.put(uuid, buffer);
        cancelBufferMap.put(uuid, cancelBuffer);
        return false;
    }

    @Override
    public void cleanup(UUID uuid) {
        bufferMap.remove(uuid);
        cancelBufferMap.remove(uuid);
        lastMaceSwapTime.remove(uuid);
        lastHeldItemType.remove(uuid);
    }
}
