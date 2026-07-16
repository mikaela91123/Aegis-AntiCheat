package org.legacy.aegis.check.impl.movement;

import org.bukkit.entity.Player;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.profile.PlayerProfile;
import org.legacy.aegis.physics.PlayerPhysicsTracker;

// check B: mostly for hover stuff and nofall
// pattern 1 is the ghost block fake-ground trick
// pattern 2 is just plain hovering mid-air
public class FlyB extends AbstractFlyCheck {

    public FlyB(ConfigManager config) {
        super("Fly (B)", "fly.b", config);
    }

    @Override
    protected boolean checkFly(Player player, PlayerProfile profile, double x, double y, double z, boolean onGround) {
        PlayerPhysicsTracker tracker = profile.getPhysicsTracker();

        if (profile.getDamageTicks() < 10) {
            return false;
        }

        double actualDeltaY = y - profile.getLastY();
        int ghostBlocks = tracker.getConsecutiveGhostBlocks();
        int airTicks = profile.getAirTicks();

        // nofall: reporting onGround but ghost blocks say otherwise
        if (onGround && ghostBlocks > 5) {
            // tiny negative deltaY = anti-kick sink packet, dead giveaway
            if (actualDeltaY < 0.0 && actualDeltaY > -0.05) {
                return true;
            }
            // literally zero Y movement = frozen in air
            if (Math.abs(actualDeltaY) < 0.001) {
                return true;
            }
            return false;
        }

        // airborne hover — been in air too long with Y violations
        if (!onGround && airTicks > 10 && tracker.getConsecutiveYViolations() > 3) {
            // if they're actually falling fast enough the tracker handles it,
            // don't flag legit freefall
            if (tracker.getFreefallTicks() > 10) {
                return false;
            }
            double predictedY = tracker.getPredictedVelocityY();
            if (actualDeltaY > predictedY + 0.08) {
                return true;
            }
        }

        return false;
    }
}
