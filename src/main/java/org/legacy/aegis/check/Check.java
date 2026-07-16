package org.legacy.aegis.check;

import org.bukkit.entity.Player;
import org.legacy.aegis.profile.PlayerProfile;

import java.util.UUID;

/**
 * Base interface for all anti-cheat checks.
 */
public interface Check {

    String getName();
    CheckType getType();
    String getConfigPath();
    boolean isEnabled();
    int getMaxVl();
    String getPunishment();

    /**
     * Clean up all per-player state when a player disconnects.
     * Every check that holds per-UUID maps MUST override this.
     */
    default void cleanup(UUID uuid) {
        // no-op — overridden by checks that maintain per-UUID state
    }
}
