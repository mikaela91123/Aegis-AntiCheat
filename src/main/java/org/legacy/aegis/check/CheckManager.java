package org.legacy.aegis.check;

import org.legacy.aegis.Aegis;
import org.legacy.aegis.check.impl.movement.*;
import org.legacy.aegis.check.impl.combat.*;
import org.legacy.aegis.check.impl.packet.*;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.exempt.ExemptionManager;
import org.legacy.aegis.profile.ProfileManager;
import org.legacy.aegis.violation.ViolationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CheckManager {

    private final Aegis plugin;
    private final ConfigManager config;
    private final ExemptionManager exemptionManager;
    private final ViolationManager violationManager;
    private final ProfileManager profileManager;

    private final List<Check> checks = new ArrayList<>();
    private List<MovementCheck> movementChecks = List.of();
    private List<CombatCheck> combatChecks = List.of();
    private List<PacketCheck> packetChecks = List.of();

    private FlyA flyA;
    private FlyB flyB;
    private SpeedA speedA;
    private SpeedB speedB;
    private KillauraB killauraB;
    private StepA stepA;
    private RotationA rotationA;
    private VerticalDeltaA verticalDeltaA;

    public CheckManager(Aegis plugin, ConfigManager config, ExemptionManager exemptionManager,
                        ViolationManager violationManager, ProfileManager profileManager) {
        this.plugin = plugin;
        this.config = config;
        this.exemptionManager = exemptionManager;
        this.violationManager = violationManager;
        this.profileManager = profileManager;

        registerChecks();
    }

    private void registerChecks() {
        checks.clear();

        registerCheck(new FlyA(config));
        registerCheck(new FlyB(config));
        registerCheck(new SpeedA(config));
        registerCheck(new SpeedB(config));
        registerCheck(new TimerA(config));
        registerCheck(new NoFallA(config));
        registerCheck(new StepA(config));
        registerCheck(new JesusA(config));

        registerCheck(new KillauraA(config));
        registerCheck(new KillauraB(config));
        registerCheck(new AutoClickerA(config));
        registerCheck(new ReachA(config, profileManager));

        registerCheck(new BadPacketsA(config));
        registerCheck(new RotationA(config));
        registerCheck(new VerticalDeltaA(config));

        rebuildCaches();
        plugin.getLogger().info("Registered " + checks.size() + " checks.");
    }

    private void rebuildCaches() {
        List<MovementCheck> mc = new ArrayList<>();
        List<CombatCheck> cc = new ArrayList<>();
        List<PacketCheck> pc = new ArrayList<>();

        for (Check check : checks) {
            if (check instanceof MovementCheck m && check.isEnabled()) mc.add(m);
            if (check instanceof CombatCheck c && check.isEnabled()) cc.add(c);
            if (check instanceof PacketCheck p && check.isEnabled()) pc.add(p);

            if (check instanceof FlyA a) flyA = a;
            else if (check instanceof FlyB b) flyB = b;
            else if (check instanceof SpeedA a) speedA = a;
            else if (check instanceof SpeedB b) speedB = b;
            else if (check instanceof KillauraB b) killauraB = b;
            else if (check instanceof StepA s) stepA = s;
            else if (check instanceof RotationA r) rotationA = r;
            else if (check instanceof VerticalDeltaA v) verticalDeltaA = v;
        }
        movementChecks = mc;
        combatChecks = cc;
        packetChecks = pc;
    }

    public void reloadChecks() {
        registerChecks();
    }

    public List<MovementCheck> getMovementChecks() { return movementChecks; }
    public List<CombatCheck> getCombatChecks() { return combatChecks; }
    public List<PacketCheck> getPacketChecks() { return packetChecks; }
    public List<Check> getAllChecks() { return checks; }

    public void registerCheck(Check check) { checks.add(check); }

    public void notifyFireworkUse(UUID uuid) {
        if (flyA != null) flyA.onFireworkUse(uuid);
        if (flyB != null) flyB.onFireworkUse(uuid);
    }
    public void notifyVelocity(UUID uuid) {
        if (speedA != null) speedA.onVelocity(uuid);
        if (speedB != null) speedB.onVelocity(uuid);
        if (flyA != null) flyA.onVelocity(uuid);
        if (flyB != null) flyB.onVelocity(uuid);
        if (stepA != null) stepA.onVelocity(uuid);
    }
    public void notifyTeleport(UUID uuid) {
        if (speedA != null) speedA.onTeleport(uuid);
        if (speedB != null) speedB.onTeleport(uuid);
    }
    public void notifyCrystalBlast(UUID uuid) {
        if (killauraB != null) killauraB.onCrystalBlast(uuid);
    }

    public ConfigManager getConfig() { return config; }
    public ExemptionManager getExemptionManager() { return exemptionManager; }
    public ViolationManager getViolationManager() { return violationManager; }
    public ProfileManager getProfileManager() { return profileManager; }
    public VerticalDeltaA getVerticalDeltaA() { return verticalDeltaA; }
    public RotationA getRotationA() { return rotationA; }
}
