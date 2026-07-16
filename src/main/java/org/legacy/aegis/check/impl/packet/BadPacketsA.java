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

// badpackets A: looks for invalid coords or impossible pitch angles
public class BadPacketsA extends AbstractCheck implements PacketCheck {

    private static final float MAX_PITCH = 90.0f;
    private static final float MIN_PITCH = -90.0f;
    private static final float PITCH_BUFFER = 5.0f;
    private static final int FLAG_BUFFER = 6;

    private final Map<UUID, Integer> bufferMap = new ConcurrentHashMap<>();

    public BadPacketsA(ConfigManager config) {
        super("BadPackets (A)", CheckType.PACKET, "badpackets.a", config);
    }

    @Override
    public boolean processPacket(Player player, PlayerProfile profile,
                                 double x, double y, double z, boolean onGround) {
        UUID uuid = player.getUniqueId();

        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)
                || Double.isInfinite(x) || Double.isInfinite(y) || Double.isInfinite(z)) {
            return true;
        }

        if (Math.abs(x) > 3.0E7 || Math.abs(y) > 3.0E7 || Math.abs(z) > 3.0E7) {
            return true;
        }

        float pitch = profile.getCurrentPacketPitch();
        if (!Float.isNaN(pitch)) {
            if (pitch > MAX_PITCH + PITCH_BUFFER || pitch < MIN_PITCH - PITCH_BUFFER) {
                int buffer = bufferMap.getOrDefault(uuid, 0) + 1;
                if (buffer >= FLAG_BUFFER) {
                    bufferMap.put(uuid, 0);
                    return true;
                }
                bufferMap.put(uuid, buffer);
                return false;
            }
        }

        // yaw can legitimately go past 360/-360 when spinning around, so we don't bound it

        if (player.isDead()) {
            return false;
        }

        // massive distance moved in one tick = location injection hack (skip if lagged/tp/kb)
        if (profile.hasReceivedFirstPacket()
                && profile.getTeleportTicks() > 15
                && profile.getDamageTicks() > 20) {
            double deltaX = x - profile.getLastX();
            double deltaZ = z - profile.getLastZ();
            double horizDist = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
            // widen threshold if server is lagging
            double tps = org.bukkit.Bukkit.getTPS()[0];
            double threshold = 10.0 + Math.max(0, (20.0 - tps) * 0.5);
            if (horizDist > threshold) {
                return true;
            }
        }

        if (profile.hasReceivedFirstPacket() && profile.getDamageTicks() > 20) {
            double deltaY = y - profile.getLastY();
            // widen fall threshold if server is lagging
            double tps = org.bukkit.Bukkit.getTPS()[0];
            double fallThreshold = -3.92 - Math.max(0, (20.0 - tps) * 0.3);
            if (onGround && deltaY < fallThreshold && profile.getAirTicks() > 15) {
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

    @Override
    public void cleanup(UUID uuid) {
        bufferMap.remove(uuid);
    }
}
