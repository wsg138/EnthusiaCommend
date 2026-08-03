package org.enthusia.rep.stalk;

import org.enthusia.rep.region.RegionManager;

/** Pure routing and state-update rules for stalking movement observations. */
final class StalkMovementRouting {
    private StalkMovementRouting() { }

    static Mode forMove(boolean teleportEvent, boolean sameBlock) {
        return teleportEvent || sameBlock ? Mode.IGNORE : Mode.TRANSITION;
    }

    static Mode forTeleport(boolean sameWorld) {
        return sameWorld ? Mode.TRANSITION : Mode.BASELINE;
    }

    static Mode forWorldChange() {
        return Mode.BASELINE;
    }

    static Observation observe(Mode mode, RegionManager.LogicalZone previous,
                               RegionManager.LogicalZone next) {
        if (mode == Mode.IGNORE) {
            return new Observation(previous, false);
        }
        boolean alert = mode == Mode.TRANSITION && StalkZoneTransition.shouldAlert(previous, next);
        return new Observation(next, alert);
    }

    enum Mode {
        IGNORE,
        BASELINE,
        TRANSITION
    }

    record Observation(RegionManager.LogicalZone rememberedZone, boolean alert) { }
}
