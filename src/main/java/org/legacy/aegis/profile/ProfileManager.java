package org.legacy.aegis.profile;

import org.legacy.aegis.Aegis;
import org.legacy.aegis.data.DatabaseManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all active PlayerProfiles.
 * Creates profiles synchronously on join (so they are immediately available)
 * then loads persisted data from the database asynchronously.
 */
public class ProfileManager {

    private final Aegis plugin;
    private final DatabaseManager databaseManager;
    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();

    public ProfileManager(Aegis plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    /**
     * Create a new profile immediately (synchronous) so it is available to
     * PacketHandler the moment the player starts sending packets.
     * Persisted data (playtime, violations, trust factor) is loaded asynchronously
     * in the background; the caller should chain on the returned future if it
     * needs to act after the DB load completes.
     */
    public PlayerProfile createProfile(UUID uuid, String username) {
        PlayerProfile profile = new PlayerProfile(uuid, username);
        profiles.put(uuid, profile);

        // Load persisted data from DB on the database executor thread (non-blocking)
        databaseManager.loadProfileAsync(profile).thenRun(() -> {
            // Apply config settings once data is loaded (safe to set from any thread
            // since the profile is only read from the main thread)
            profile.setMaxBufferSize(plugin.getConfigManager().getEvidenceBufferSize());
        });

        return profile;
    }

    public PlayerProfile getProfile(UUID uuid) {
        return profiles.get(uuid);
    }

    public void removeProfile(UUID uuid) {
        PlayerProfile profile = profiles.remove(uuid);
        if (profile != null) {
            profile.endSession();
            databaseManager.saveProfile(profile);
        }
    }

    public void saveAll() {
        for (PlayerProfile profile : profiles.values()) {
            profile.endSession();
            databaseManager.saveProfile(profile);
        }
        profiles.clear();
    }

    public Map<UUID, PlayerProfile> getAllProfiles() {
        return profiles;
    }
}
