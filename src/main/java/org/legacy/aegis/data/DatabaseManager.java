package org.legacy.aegis.data;

import org.legacy.aegis.Aegis;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.profile.PlayerProfile;
import org.legacy.aegis.scheduler.FoliaScheduler;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages SQLite database for persisting player profiles and evidence data.
 * All database operations run on a dedicated single-threaded executor to
 * guarantee serialised (thread-safe) access to the shared SQLite connection.
 */
public class DatabaseManager {

    private final Aegis plugin;
    private final ConfigManager config;
    private Connection connection;
    private final ExecutorService executor;

    private static final String CREATE_PROFILES_TABLE = """
        CREATE TABLE IF NOT EXISTS player_profiles (
            uuid VARCHAR(36) PRIMARY KEY,
            username VARCHAR(16) NOT NULL,
            total_playtime_ms BIGINT DEFAULT 0,
            total_violations INT DEFAULT 0,
            trust_factor DOUBLE DEFAULT 5.0,
            last_seen BIGINT DEFAULT 0
        )
    """;

    private static final String CREATE_EVIDENCE_TABLE = """
        CREATE TABLE IF NOT EXISTS evidence (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player_uuid VARCHAR(36) NOT NULL,
            check_name VARCHAR(64) NOT NULL,
            violation_level INT NOT NULL,
            trust_factor DOUBLE NOT NULL,
            snapshot_data TEXT NOT NULL,
            created_at BIGINT NOT NULL
        )
    """;

    private static final String CREATE_EVIDENCE_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_evidence_uuid ON evidence(player_uuid)";

    private static final String CREATE_APPEALS_TABLE = """
        CREATE TABLE IF NOT EXISTS appeals (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player_uuid VARCHAR(36) NOT NULL,
            player_name VARCHAR(16) NOT NULL,
            evidence_id INT NOT NULL,
            reason TEXT NOT NULL,
            submitted_at BIGINT NOT NULL,
            status VARCHAR(16) NOT NULL
        )
    """;

    public DatabaseManager(Aegis plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "aegis-database");
            t.setDaemon(true);
            return t;
        });
    }

    public void initialize() {
        // Run initialization on the DB executor so the connection is created on that thread
        try {
            CompletableFuture<Void> init = new CompletableFuture<>();
            executor.submit(() -> {
                try {
                    File dbFile = new File(plugin.getDataFolder(), config.getSqliteFile());
                    if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();

                    String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
                    connection = DriverManager.getConnection(url);

                    try (Statement stmt = connection.createStatement()) {
                        stmt.execute("PRAGMA journal_mode = WAL;");
                        stmt.execute("PRAGMA synchronous = NORMAL;");
                        stmt.execute("PRAGMA cache_size = 10000;");
                        stmt.execute("PRAGMA temp_store = MEMORY;");
                    }

                    try (Statement stmt = connection.createStatement()) {
                        stmt.execute(CREATE_PROFILES_TABLE);
                        stmt.execute(CREATE_EVIDENCE_TABLE);
                        stmt.execute(CREATE_EVIDENCE_INDEX);
                        stmt.execute(CREATE_APPEALS_TABLE);
                    }

                    plugin.getLogger().info("SQLite database initialized successfully.");
                    init.complete(null);

                    if (config.isCleanupEnabled()) scheduleCleanup();
                } catch (SQLException e) {
                    plugin.getLogger().severe("Failed to initialize SQLite database: " + e.getMessage());
                    init.completeExceptionally(e);
                }
            });
            // Block here during plugin enable only — this is acceptable during startup
            init.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().severe("Database initialization timed out or failed: " + e.getMessage());
        }
    }


    /**
     * Asynchronously load persisted profile data from the database.
     * Returns a CompletableFuture that completes when loading is done.
     * Safe to call from any thread (including main thread on player join).
     */
    public CompletableFuture<Void> loadProfileAsync(PlayerProfile profile) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.submit(() -> {
            try {
                String sql = "SELECT total_playtime_ms, total_violations, trust_factor " +
                             "FROM player_profiles WHERE uuid = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, profile.getUuid().toString());
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        profile.setTotalPlaytimeMillis(rs.getLong("total_playtime_ms"));
                        profile.setTotalViolations(rs.getInt("total_violations"));
                        profile.setTrustFactor(rs.getDouble("trust_factor"));
                    }
                }
                future.complete(null);
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to load profile for " + profile.getUsername() + ": " + e.getMessage());
                future.complete(null); // Complete normally so callers don't have to handle exceptions
            }
        });
        return future;
    }

    /** Save profile data to database (async, fire-and-forget). */
    public void saveProfile(PlayerProfile profile) {
        executor.submit(() -> {
            try {
                String sql = """
                    INSERT INTO player_profiles (uuid, username, total_playtime_ms, total_violations, trust_factor, last_seen)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        username = excluded.username,
                        total_playtime_ms = excluded.total_playtime_ms,
                        total_violations = excluded.total_violations,
                        trust_factor = excluded.trust_factor,
                        last_seen = excluded.last_seen
                """;
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, profile.getUuid().toString());
                    ps.setString(2, profile.getUsername());
                    ps.setLong(3, profile.getTotalPlaytimeMillis());
                    ps.setInt(4, profile.getTotalViolations());
                    ps.setDouble(5, profile.getTrustFactor());
                    ps.setLong(6, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to save profile for " + profile.getUsername() + ": " + e.getMessage());
            }
        });
    }


    /** Save evidence snapshot (async, fire-and-forget). */
    public void saveEvidence(String playerUuid, String checkName, int vl, double trustFactor, String snapshotJson) {
        executor.submit(() -> {
            try {
                String sql = "INSERT INTO evidence (player_uuid, check_name, violation_level, trust_factor, snapshot_data, created_at) " +
                             "VALUES (?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerUuid);
                    ps.setString(2, checkName);
                    ps.setInt(3, vl);
                    ps.setDouble(4, trustFactor);
                    ps.setString(5, snapshotJson);
                    ps.setLong(6, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to save evidence: " + e.getMessage());
            }
        });
    }

    /**
     * Asynchronously retrieve a specific evidence record by ID.
     * Returns CompletableFuture<String[]> or null value if not found.
     */
    public CompletableFuture<String[]> getEvidenceByIdAsync(int evidenceId) {
        CompletableFuture<String[]> future = new CompletableFuture<>();
        executor.submit(() -> {
            try {
                String sql = "SELECT player_uuid, check_name, violation_level, trust_factor, snapshot_data, created_at " +
                             "FROM evidence WHERE id = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setInt(1, evidenceId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        future.complete(new String[]{
                                rs.getString("player_uuid"),
                                rs.getString("check_name"),
                                String.valueOf(rs.getInt("violation_level")),
                                String.valueOf(rs.getDouble("trust_factor")),
                                rs.getString("snapshot_data"),
                                String.valueOf(rs.getLong("created_at"))
                        });
                    } else {
                        future.complete(null);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to retrieve evidence #" + evidenceId + ": " + e.getMessage());
                future.complete(null);
            }
        });
        return future;
    }

    /**
     * Synchronous getEvidenceById — kept for backward-compat with ReplayManager.
     * NOTE: Do NOT call from the main thread on large databases.
     * Prefer getEvidenceByIdAsync where possible.
     */
    public String[] getEvidenceById(int evidenceId) {
        try {
            return getEvidenceByIdAsync(evidenceId).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("getEvidenceById timed out: " + e.getMessage());
            return null;
        }
    }

    /**
     * Asynchronously list recent evidence records for a player.
     */
    public CompletableFuture<List<String[]>> getEvidenceByPlayerAsync(String playerUuid, int limit) {
        CompletableFuture<List<String[]>> future = new CompletableFuture<>();
        executor.submit(() -> {
            List<String[]> results = new ArrayList<>();
            try {
                String sql = "SELECT id, check_name, violation_level, created_at " +
                             "FROM evidence WHERE player_uuid = ? ORDER BY created_at DESC LIMIT ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, playerUuid);
                    ps.setInt(2, limit);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        results.add(new String[]{
                                String.valueOf(rs.getInt("id")),
                                rs.getString("check_name"),
                                String.valueOf(rs.getInt("violation_level")),
                                String.valueOf(rs.getLong("created_at"))
                        });
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to list evidence for " + playerUuid + ": " + e.getMessage());
            }
            future.complete(results);
        });
        return future;
    }

    /** Synchronous variant kept for backward-compat. */
    public List<String[]> getEvidenceByPlayer(String playerUuid, int limit) {
        try {
            return getEvidenceByPlayerAsync(playerUuid, limit).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("getEvidenceByPlayer timed out: " + e.getMessage());
            return new ArrayList<>();
        }
    }


    public void saveAppeal(org.legacy.aegis.appeal.AppealManager.Appeal appeal) {
        executor.submit(() -> {
            try {
                String sql = "INSERT INTO appeals (id, player_uuid, player_name, evidence_id, reason, submitted_at, status) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setInt(1, appeal.id());
                    ps.setString(2, appeal.playerUuid().toString());
                    ps.setString(3, appeal.playerName());
                    ps.setInt(4, appeal.evidenceId());
                    ps.setString(5, appeal.reason());
                    ps.setLong(6, appeal.submittedAt());
                    ps.setString(7, appeal.status());
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to save appeal: " + e.getMessage());
            }
        });
    }

    public void updateAppealStatus(int id, String status) {
        executor.submit(() -> {
            try {
                String sql = "UPDATE appeals SET status = ? WHERE id = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, status);
                    ps.setInt(2, id);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to update appeal status: " + e.getMessage());
            }
        });
    }

    public java.util.Map<Integer, org.legacy.aegis.appeal.AppealManager.Appeal> loadAppeals() {
        java.util.Map<Integer, org.legacy.aegis.appeal.AppealManager.Appeal> appeals = new java.util.concurrent.ConcurrentHashMap<>();
        try {
            // Run on DB executor to prevent locking main thread during startup
            executor.submit(() -> {
                try {
                    String sql = "SELECT id, player_uuid, player_name, evidence_id, reason, submitted_at, status FROM appeals";
                    try (PreparedStatement ps = connection.prepareStatement(sql)) {
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            appeals.put(rs.getInt("id"), new org.legacy.aegis.appeal.AppealManager.Appeal(
                                    rs.getInt("id"),
                                    java.util.UUID.fromString(rs.getString("player_uuid")),
                                    rs.getString("player_name"),
                                    rs.getInt("evidence_id"),
                                    rs.getString("reason"),
                                    rs.getLong("submitted_at"),
                                    rs.getString("status")
                            ));
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to load appeals: " + e.getMessage());
                }
            }).get(); // Wait for load to finish during startup
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to wait for appeals load: " + e.getMessage());
        }
        return appeals;
    }

    private void scheduleCleanup() {
        long intervalTicks = config.getCleanupIntervalHours() * 60L * 60L * 20L;
        FoliaScheduler.runAsyncTimer(plugin, () -> {
            try {
                long cutoff = System.currentTimeMillis() - ((long) config.getCleanupMaxAgeDays() * 24 * 60 * 60 * 1000);
                String sql = "DELETE FROM evidence WHERE created_at < ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setLong(1, cutoff);
                    int deleted = ps.executeUpdate();
                    if (deleted > 0)
                        plugin.getLogger().info("Database cleanup: removed " + deleted + " old evidence records.");
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Database cleanup failed: " + e.getMessage());
            }
        }, intervalTicks, intervalTicks);
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
                executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("Error closing database: " + e.getMessage());
        }
    }
}
