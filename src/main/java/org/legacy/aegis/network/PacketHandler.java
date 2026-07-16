package org.legacy.aegis.network;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.legacy.aegis.Aegis;
import org.legacy.aegis.check.Check;
import org.legacy.aegis.check.CheckManager;
import org.legacy.aegis.check.CombatCheck;
import org.legacy.aegis.check.MovementCheck;
import org.legacy.aegis.check.PacketCheck;
import org.legacy.aegis.check.impl.movement.VerticalDeltaA;
import org.legacy.aegis.exempt.ExemptionManager;
import org.legacy.aegis.scheduler.FoliaScheduler;
import org.legacy.aegis.physics.PlayerPhysicsTracker;
import org.legacy.aegis.profile.PlayerProfile;
import org.legacy.aegis.profile.ProfileManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// main packet listener, intercepts packets async and sends them to the correct region thread
public class PacketHandler extends PacketListenerAbstract {

    private final Aegis plugin;
    private final CheckManager checkManager;
    private final ProfileManager profileManager;
    private final ExemptionManager exemptionManager;

    // netty thread rotation tracking, used for instant attack cancellation to prevent macekill bypasses
    private final Map<UUID, int[]> nettyRotationCount = new ConcurrentHashMap<>();

    public PacketHandler(Aegis plugin, CheckManager checkManager,
                          ProfileManager profileManager, ExemptionManager exemptionManager) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        this.checkManager = checkManager;
        this.profileManager = profileManager;
        this.exemptionManager = exemptionManager;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        UUID uuid = event.getUser().getUUID();
        if (uuid == null) return;

        if (isMovementPacket(event)) {
            WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);

            // rotation-only packets don't have position data, gotta check before reading
            boolean hasPos = flying.hasPositionChanged();
            boolean hasRot = flying.hasRotationChanged();
            boolean onGround = flying.isOnGround();

            // Count rotation-ONLY packets (PLAYER_ROTATION, no position)
            // per 50ms tick. Combined position+rotation packets are not
            // counted (normal movement), BUT we also don't clear the
            // counter on them — the hack sends rotation-only spam BEFORE
            // the teleport packet, and we need that count to persist
            // until the attack arrives.
            // Threshold: > 1 (2+ rotations in one tick = spam).
            if (!hasPos && hasRot) {
                long now = System.currentTimeMillis();
                int[] data = nettyRotationCount.computeIfAbsent(uuid, k -> new int[]{0, (int) now});
                if (now - data[1] > 50) {
                    data[0] = 1;
                    data[1] = (int) now;
                } else {
                    data[0]++;
                }
            }

            // read safely on netty thread
            final double px    = hasPos ? flying.getLocation().getX()     : Double.NaN;
            final double py    = hasPos ? flying.getLocation().getY()     : Double.NaN;
            final double pz    = hasPos ? flying.getLocation().getZ()     : Double.NaN;
            final float  pyaw  = hasRot ? flying.getLocation().getYaw()   : Float.NaN;
            final float  ppitch= hasRot ? flying.getLocation().getPitch() : Float.NaN;

            // getting player is thread safe in paper
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) return;

            // use player so folia knows which region thread to use
            FoliaScheduler.runTask(plugin, () -> {
                PlayerProfile profile = profileManager.getProfile(uuid);
                if (profile == null) return;

                // fill in missing values with last known if packet only had pos or only rot
                double x     = hasPos ? px     : profile.getLastX();
                double y     = hasPos ? py     : profile.getLastY();
                double z     = hasPos ? pz     : profile.getLastZ();
                float  yaw   = hasRot ? pyaw   : profile.getLastYaw();
                float  pitch = hasRot ? ppitch : profile.getLastPitch();

                handleMovementSync(player, profile, x, y, z, yaw, pitch, onGround, hasPos, hasRot);
            }, player);
        }

        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
            if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                int targetId = interact.getEntityId();

                // Set lastAttackTime BEFORE any null checks so that the
                // RotationA check sees combat state as early as possible.
                long lastAttack = 0;
                PlayerProfile attackProfile = profileManager.getProfile(uuid);
                if (attackProfile != null) {
                    lastAttack = attackProfile.getLastAttackTime();
                    attackProfile.setLastAttackTime(System.currentTimeMillis());
                }

                // Read rotation count tracked above (netty thread) before the
                // server processes the packet. This is THE critical fix for
                // MaceKill rotation bypass — waiting until region thread is too late.
                boolean cancelled = false;
                int[] rotData = nettyRotationCount.get(uuid);
                int maxPerTick = checkManager.getConfig().getCheckInt("rotation.a", "max-per-tick", 3);
                
                // higher limit if actively fighting to avoid jitter click false flags
                boolean inCombat = (lastAttack > 0 && (System.currentTimeMillis() - lastAttack) < 2000);
                int limit = inCombat ? Math.max(12, maxPerTick * 4) : Math.max(5, maxPerTick * 2);
                
                if (rotData != null && rotData[0] > limit) {
                    event.setCancelled(true);
                    cancelled = true;
                }

                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) return;

                final boolean attackCancelled = cancelled;
                FoliaScheduler.runTask(plugin, () -> {
                    PlayerProfile profile = profileManager.getProfile(uuid);
                    if (profile == null) return;
                    if (attackCancelled) {
                        // actually flag it on the region thread so alerts work properly
                        profile.registerClick();
                        profile.setLastAttackTime(System.currentTimeMillis());
                        flagRotationSpam(player, profile);
                    } else {
                        handleInteractionSync(player, profile, targetId);
                    }
                }, player);
            }
        }
    }

    // Helpers

    private boolean isMovementPacket(PacketReceiveEvent event) {
        return event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING;
    }

    // flag rotation spam silently
    private void flagRotationSpam(Player player, PlayerProfile profile) {
        for (Check check : checkManager.getAllChecks()) {
            if (check.getName().equals("Rotation (A)") && check.isEnabled()) {
                checkManager.getViolationManager().flag(player, profile, check);
                break;
            }
        }
    }

    // handles movement packets after they bridge to region thread
    private void handleMovementSync(Player player, PlayerProfile profile,
                                      double x, double y, double z,
                                      float yaw, float pitch,
                                      boolean onGround, boolean hasPos, boolean hasRot) {

        // save raw wire values for badpackets
        profile.setCurrentPacketRotation(yaw, pitch);

        profile.setOnGround(onGround);
        profile.incrementCurrentTick();

        // Count rotation-ONLY packets per 50ms tick. Clear the count
        // on any packet with position data so normal movement (which may
        // include rotation) doesn't carry stale counts from previous
        // rapid-aiming ticks into the RotationA check.
        if (!hasPos && hasRot) {
            long now = System.currentTimeMillis();
            if (now - profile.getRotationTickStart() > 50) {
                profile.setRotationCount(1);
                profile.setRotationTickStart(now);
            } else {
                profile.incrementRotationCount();
            }
        } else {
            profile.setRotationCount(0);
        }

        profile.tickExemptions();

        // only save snapshot if we actually moved
        if (hasPos) {
            profile.recordTick(x, y, z, yaw, pitch, onGround);
        }

        // save y delta for mace checks
        if (hasPos) {
            profile.setLastDeltaY(y - profile.getLastY());
        }

        PlayerPhysicsTracker physicsTracker = profile.getPhysicsTracker();
        physicsTracker.adjustTolerance(exemptionManager.getCurrentTps(), player.getPing());
        physicsTracker.processTick(
                player, x, y, z, onGround,
                profile.getLastX(), profile.getLastY(), profile.getLastZ());

        // The exemption system skips movement checks when the player has
        // recent knockback (damageTicks < 25). After the first mace hit,
        // the entity-damage listener calls profile.onDamage(), making the
        // player exempt. The MaceKill exploit then sends the vertical
        // teleport during this grace window. Running VerticalDeltaA here,
        // before the exemption check, closes that bypass window entirely.
        boolean flagged = false;
        VerticalDeltaA vdCheck = checkManager.getVerticalDeltaA();
        if (vdCheck != null && vdCheck.isEnabled()
                && vdCheck.processMovement(player, profile, x, y, z, onGround)) {
            checkManager.getViolationManager().flag(player, profile, vdCheck);
            flagged = true;
        }

        if (exemptionManager.isMovementExempt(player, profile)) {
            if (hasPos) {
                profile.setLastPosition(x, y, z);
                if (plugin.getRollbackManager() != null) {
                    plugin.getRollbackManager().recordIfSafe(player, profile, onGround, false);
                }
            }
            profile.setLastRotation(yaw, pitch);
            return;
        }

        for (PacketCheck check : checkManager.getPacketChecks()) {
            if (!check.isEnabled()) continue;
            if (check.processPacket(player, profile, x, y, z, onGround)) {
                checkManager.getViolationManager().flag(player, profile, check);
                flagged = true;
            }
        }

        for (MovementCheck check : checkManager.getMovementChecks()) {
            if (!check.isEnabled()) continue;
            if (check.processMovement(player, profile, x, y, z, onGround)) {
                checkManager.getViolationManager().flag(player, profile, check);
                flagged = true;
            }
        }

        if (hasPos && plugin.getRollbackManager() != null) {
            plugin.getRollbackManager().recordIfSafe(player, profile, onGround, flagged);
        }

        if (hasPos) {
            profile.setLastPosition(x, y, z);
        }
        profile.setLastRotation(yaw, pitch);
    }

    // handle attack on region thread
    private void handleInteractionSync(Player player, PlayerProfile profile, int targetId) {
        profile.registerClick();
        profile.setLastAttackTime(System.currentTimeMillis());

        // The primary cancellation occurs on the netty thread above. This
        // fallback catches edge cases where the netty check might not fire.
        int maxPerTick = checkManager.getConfig().getCheckInt("rotation.a", "max-per-tick", 3);
        boolean inCombat = profile.getCurrentCPS() > 0;
        int limit = inCombat ? Math.max(12, maxPerTick * 4) : Math.max(5, maxPerTick * 2);
        if (profile.getRotationCount() > limit) {
            flagRotationSpam(player, profile);
            profile.resetRotationCount();
            return;
        }

        if (exemptionManager.isCombatExempt(player, profile)) {
            return;
        }

        boolean cancelAttack = false;
        for (CombatCheck check : checkManager.getCombatChecks()) {
            if (!check.isEnabled()) continue;
            if (check.processAttack(player, profile, targetId)) {
                checkManager.getViolationManager().flag(player, profile, check);
                cancelAttack = true;
            }
        }
        
        if (cancelAttack) {
            profile.setCancelNextAttack(true);
        }
    }

    // clean up
    public void cleanup(UUID uuid) {
        nettyRotationCount.remove(uuid);
    }
}
