package org.legacy.aegis.check;

import org.bukkit.entity.Player;
import org.legacy.aegis.profile.PlayerProfile;

/**
 * Interface for checks that process combat/attack interactions.
 */
public interface CombatCheck extends Check {

    /**
     * Process an attack interaction.
     *
     * @param player   The attacking player
     * @param profile  The player's anti-cheat profile
     * @param targetId The entity ID of the target
     * @return true if the action is flagged as suspicious
     */
    boolean processAttack(Player player, PlayerProfile profile, int targetId);
}
