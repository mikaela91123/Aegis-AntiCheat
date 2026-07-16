package org.legacy.aegis.check;

import org.bukkit.entity.Player;
import org.legacy.aegis.profile.PlayerProfile;

/**
 * Interface for checks that process raw packet data (e.g. BadPackets).
 */
public interface PacketCheck extends Check {

    /**
     * Process a packet check.
     *
     * @param player  The Bukkit player
     * @param profile The player's anti-cheat profile
     * @param x       Current X position
     * @param y       Current Y position
     * @param z       Current Z position
     * @param onGround Whether the client claims to be on ground
     * @return true if the packet is flagged as suspicious
     */
    boolean processPacket(Player player, PlayerProfile profile, double x, double y, double z, boolean onGround);
}
