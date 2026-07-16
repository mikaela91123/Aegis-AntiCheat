package org.legacy.aegis.check.impl.movement;

import org.bukkit.entity.Player;
import org.legacy.aegis.config.ConfigManager;
import org.legacy.aegis.profile.PlayerProfile;
import org.legacy.aegis.physics.PlayerPhysicsTracker;

// check A: basic Y-delta vs gravity prediction
public class FlyA extends AbstractFlyCheck {

    public FlyA(ConfigManager config) {
        super("Fly (A)", "fly.a", config);
    }

    @Override
    protected boolean checkFly(Player player, PlayerProfile profile, double x, double y, double z, boolean onGround) {
        PlayerPhysicsTracker tracker = profile.getPhysicsTracker();

        if (!onGround && tracker.getConsecutiveYViolations() > 4) {
            // skip during confirmed freefall, prediction drifts over long falls
            if (tracker.getFreefallTicks() > 10) {
                return false;
            }
            double actualDeltaY = y - profile.getLastY();
            if (actualDeltaY > tracker.getPredictedVelocityY() + 0.1) {
                return true;
            }
        }

        return false;
    }
}
