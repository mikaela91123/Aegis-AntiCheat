package org.legacy.aegis;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.legacy.aegis.check.CheckManager;
import org.legacy.aegis.command.AegisCommand;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.data.DatabaseManager;
import org.legacy.aegis.exempt.ExemptionManager;
import org.legacy.aegis.listener.PlayerConnectionListener;
import org.legacy.aegis.network.PacketHandler;
import org.legacy.aegis.profile.ProfileManager;
import org.legacy.aegis.scheduler.FoliaScheduler;
import org.legacy.aegis.violation.ViolationManager;
import org.legacy.aegis.webhook.DiscordWebhook;
import org.legacy.aegis.appeal.AppealManager;
import org.legacy.aegis.rollback.RollbackManager;

// main plugin class, boots everything up
public final class Aegis extends JavaPlugin {

    private static Aegis instance;

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private ProfileManager profileManager;
    private CheckManager checkManager;
    private ExemptionManager exemptionManager;
    private ViolationManager violationManager;
    private DiscordWebhook discordWebhook;

    private AppealManager appealManager;
    private RollbackManager rollbackManager;
    private PacketHandler packetHandler;

    @Override
    public void onLoad() {
        instance = this;

        // PacketEvents must be loaded in onLoad()
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings()
                .reEncodeByDefault(true)
                .checkForUpdates(false);
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        long start = System.currentTimeMillis();

        // Initialize Folia detection
        FoliaScheduler.init(this);

        // 1. Load configuration
        configManager = new ConfigManager(this);
        configManager.load();

        // 2. Initialize database
        databaseManager = new DatabaseManager(this, configManager);
        databaseManager.initialize();

        // 3. Initialize profile manager (starts tracking player data for Trust Factor)
        profileManager = new ProfileManager(this, databaseManager);

        // 4. Initialize exemption engine
        exemptionManager = new ExemptionManager(this, configManager);

        // 5. Initialize Discord webhook
        discordWebhook = new DiscordWebhook(this);



        // 6. Initialize violation manager
        violationManager = new ViolationManager(this, configManager, profileManager, discordWebhook);

        // 7. Initialize rollback manager
        rollbackManager = new RollbackManager(this);

        // 8. Initialize check manager and register all checks
        checkManager = new CheckManager(this, configManager, exemptionManager, violationManager, profileManager);

        // 7. Register packet listener
        packetHandler = new PacketHandler(this, checkManager, profileManager, exemptionManager);
        PacketEvents.getAPI().getEventManager().registerListener(packetHandler);
        PacketEvents.getAPI().init();

        // 8. Register Bukkit event listeners
        Bukkit.getPluginManager().registerEvents(new PlayerConnectionListener(this, profileManager), this);

        // 9. Register commands
        AegisCommand aegisCommand = new AegisCommand(this, configManager, violationManager, profileManager);
        getCommand("ac").setExecutor(aegisCommand);
        getCommand("ac").setTabCompleter(aegisCommand);

        // 10. Initialize appeal system
        appealManager = new AppealManager(this);
        getCommand("appeal").setExecutor(appealManager);
        getCommand("appeal").setTabCompleter(appealManager);

        long elapsed = System.currentTimeMillis() - start;
        getLogger().info("§a§lAegis Anti-Cheat §7v" + getDescription().getVersion() + " §aenabled in §f" + elapsed + "ms");
    }

    @Override
    public void onDisable() {
        // Save all player profiles
        if (profileManager != null) {
            profileManager.saveAll();
        }



        // Close database connections
        if (databaseManager != null) {
            databaseManager.shutdown();
        }

        // Terminate PacketEvents
        if (PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().terminate();
        }

        getLogger().info("§c§lAegis Anti-Cheat §7disabled.");
    }

    // hot reload, called from /ac reload
    public void reload() {
        configManager.load();
        checkManager.reloadChecks();
        getLogger().info("§aAegis configuration reloaded successfully.");
    }

    // getters

    public static Aegis get() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public ProfileManager getProfileManager() {
        return profileManager;
    }

    public CheckManager getCheckManager() {
        return checkManager;
    }

    public ExemptionManager getExemptionManager() {
        return exemptionManager;
    }

    public ViolationManager getViolationManager() {
        return violationManager;
    }

    public DiscordWebhook getDiscordWebhook() {
        return discordWebhook;
    }



    public AppealManager getAppealManager() {
        return appealManager;
    }

    public RollbackManager getRollbackManager() {
        return rollbackManager;
    }

    public PacketHandler getPacketHandler() {
        return packetHandler;
    }
}
