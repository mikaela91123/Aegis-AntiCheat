package org.legacy.aegis.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.legacy.aegis.Aegis;
import org.legacy.aegis.check.Check;
import org.legacy.aegis.profile.PlayerProfile;
import org.legacy.aegis.profile.ProfileManager;

import java.util.UUID;

/**
 * Listens for Bukkit events to manage player profiles and exemption states.
 */
public class PlayerConnectionListener implements Listener {

    private final Aegis plugin;
    private final ProfileManager profileManager;

    public PlayerConnectionListener(Aegis plugin, ProfileManager profileManager) {
        this.plugin = plugin;
        this.profileManager = profileManager;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // createProfile registers the profile immediately and loads DB data async
        PlayerProfile profile = profileManager.createProfile(player.getUniqueId(), player.getName());

        // Detect Bedrock players synchronously (fast, no DB involved)
        if (plugin.getExemptionManager().isBedrockPlayer(player)) {
            profile.setBedrockPlayer(true);
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[DEBUG] " + player.getName() + " detected as Bedrock player.");
            }
        }

        // Set initial position so checks have a valid baseline immediately
        profile.setLastPosition(
                player.getLocation().getX(),
                player.getLocation().getY(),
                player.getLocation().getZ());
        profile.setLastRotation(
                player.getLocation().getYaw(),
                player.getLocation().getPitch());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        // Clean up per-player state from ALL checks to prevent memory leaks.
        // Without this, ConcurrentHashMaps in checks grow unboundedly as players join/leave.
        if (plugin.getCheckManager() != null) {
            for (Check check : plugin.getCheckManager().getAllChecks()) {
                check.cleanup(uuid);
            }
        }

        // Clean up netty-thread rotation tracking state
        if (plugin.getPacketHandler() != null) {
            plugin.getPacketHandler().cleanup(uuid);
        }

        profileManager.removeProfile(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            PlayerProfile profile = profileManager.getProfile(player.getUniqueId());
            if (profile != null) profile.onDamage();

            // Notify KillauraB about End Crystal / block explosion damage
            EntityDamageEvent.DamageCause cause = event.getCause();
            if (cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                    || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
                if (plugin.getCheckManager() != null) {
                    plugin.getCheckManager().notifyCrystalBlast(player.getUniqueId());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;

        PlayerProfile profile = profileManager.getProfile(attacker.getUniqueId());
        if (profile != null && profile.shouldCancelNextAttack()) {
            event.setCancelled(true);
            profile.setCancelNextAttack(false);
            return;
        }

        String weapon;
        try {
            weapon = attacker.getInventory().getItemInMainHand().getType().name();
        } catch (Exception e) {
            return;
        }

        // ─── Mace fall-damage verifier ───────────────────────────
        if (weapon.contains("MACE")) {
            if (profile != null) profile.onDamage();

            double fallDist = attacker.getFallDistance();
            double lastDeltaY = profile != null ? profile.getLastDeltaY() : 0;
            double baseMaceDamage = 7.0;

            // Case 1: fallDist is tiny but damage is high → teleport spoof
            if (fallDist < 3.0 && event.getDamage() > baseMaceDamage * 1.5) {
                event.setDamage(baseMaceDamage);
                return;
            }

            // Case 2: player had an impossible deltaY (teleport down > 8 blocks
            // in one tick) but fallDist wasn't reset yet (race condition between
            // movement and attack processing). Cap the damage.
            if (lastDeltaY < -8.0) {
                event.setDamage(baseMaceDamage);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(ProjectileHitEvent event) {
        try {
            String type = event.getEntity().getType().name();
            if (type.equals("WIND_CHARGE") || type.equals("BREEZE_WIND_CHARGE")) {
                for (org.bukkit.entity.Entity e : event.getEntity().getNearbyEntities(4.0, 4.0, 4.0)) {
                    if (e instanceof Player p) {
                        PlayerProfile profile = profileManager.getProfile(p.getUniqueId());
                        if (profile != null) profile.onDamage();
                    }
                }
                if (event.getEntity().getShooter() instanceof Player shooter) {
                    PlayerProfile profile = profileManager.getProfile(shooter.getUniqueId());
                    if (profile != null) profile.onDamage();
                }
            }
        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        PlayerProfile profile = profileManager.getProfile(player.getUniqueId());
        if (profile != null) {
            profile.onDamage();
            // Sync physics tracker with the applied velocity so predictions stay accurate.
            // Without this, the tracker accumulates Y-violations during velocity-induced flight.
            profile.getPhysicsTracker().applyExternalVelocity(event.getVelocity().getY());
        }

        if (plugin.getCheckManager() != null) {
            plugin.getCheckManager().notifyVelocity(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        PlayerProfile profile = profileManager.getProfile(player.getUniqueId());
        if (profile != null) profile.onTeleport();

        if (plugin.getCheckManager() != null) {
            plugin.getCheckManager().notifyTeleport(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null) return;

        if (item.getType().name().contains("FIREWORK") && player.isGliding()) {
            if (plugin.getCheckManager() != null) {
                plugin.getCheckManager().notifyFireworkUse(player.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        PlayerProfile profile = profileManager.getProfile(player.getUniqueId());
        if (profile != null) {
            profile.onTeleport();
            profile.setLastPosition(
                    event.getRespawnLocation().getX(),
                    event.getRespawnLocation().getY(),
                    event.getRespawnLocation().getZ());
        }
        if (plugin.getCheckManager() != null) {
            plugin.getCheckManager().notifyTeleport(player.getUniqueId());
        }
    }
}
