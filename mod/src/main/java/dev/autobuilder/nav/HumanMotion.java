package dev.autobuilder.nav;

import dev.autobuilder.config.BuilderConfig;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Timing/motion helpers so the executor never snaps a look angle or acts on
 * a fixed tick cadence -- every delay and turn rate here is randomized
 * within a pace-dependent range instead of being a constant, which is what
 * actually distinguishes "looks like a player" from "looks like a script"
 * to a casual observer. This is about producing natural-looking motion, not
 * about defeating any particular server's cheat detection -- it doesn't
 * fingerprint or spoof anything, it just avoids the dead giveaway of
 * perfectly uniform timing.
 */
public class HumanMotion {

    private final BuilderConfig.Pace pace;
    private final ThreadLocalRandom rng = ThreadLocalRandom.current();

    public HumanMotion(BuilderConfig.Pace pace) {
        this.pace = pace;
    }

    /** Ticks (20/s) to hold still after finishing an action before starting the next. */
    public int dwellTicks() {
        int ms = rng.nextInt(pace.minDwellMs, pace.maxDwellMs + 1);
        return Math.max(1, Math.round(ms / 50f));
    }

    /** Occasional extra beat, as if double-checking placement -- a few percent of actions. */
    public boolean shouldHesitate() {
        return rng.nextDouble() < 0.06;
    }

    /**
     * One tick's worth of look movement toward a target, easing out as it
     * gets close (fast turn, slow settle) with a little overshoot on big
     * turns, corrected over the following tick or two -- rather than a
     * linear turn-then-stop.
     */
    public record LookState(float yaw, float pitch) {}

    public LookState stepLook(LookState current, float targetYaw, float targetPitch) {
        float yawDelta = wrapDegrees(targetYaw - current.yaw());
        float pitchDelta = targetPitch - current.pitch();

        double maxDegreesPerTick = 18.0 * pace.motionSpeedFraction;
        // Ease-out: move a fraction of the remaining distance, floored so it still converges.
        double yawStep = Math.max(0.6, Math.abs(yawDelta) * 0.35) * Math.signum(yawDelta);
        double pitchStep = Math.max(0.4, Math.abs(pitchDelta) * 0.35) * Math.signum(pitchDelta);
        yawStep = clampAbs(yawStep, maxDegreesPerTick);
        pitchStep = clampAbs(pitchStep, maxDegreesPerTick);

        // Small overshoot on large turns, like a real flick-and-settle.
        boolean bigTurn = Math.abs(yawDelta) > 45;
        if (bigTurn && rng.nextDouble() < 0.15) {
            yawStep *= 1.08;
        }

        // Tiny constant jitter so consecutive placements never share an identical angle.
        double jitterYaw = (rng.nextDouble() - 0.5) * 0.4;
        double jitterPitch = (rng.nextDouble() - 0.5) * 0.3;

        float newYaw = current.yaw() + (float) (yawStep + jitterYaw);
        float newPitch = clamp(current.pitch() + (float) (pitchStep + jitterPitch), -90f, 90f);
        return new LookState(wrapDegrees(newYaw), newPitch);
    }

    public boolean lookCloseEnough(LookState current, float targetYaw, float targetPitch) {
        return Math.abs(wrapDegrees(targetYaw - current.yaw())) < 2.0f && Math.abs(targetPitch - current.pitch()) < 2.0f;
    }

    /** Baseline think-time before starting a new placement, on top of dwell. */
    public int reactionDelayTicks() {
        double baseMs = rng.nextDouble(120, 380) * pace.actionDelayScale;
        return Math.max(1, Math.round((float) (baseMs / 50.0)));
    }

    private static float wrapDegrees(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private static double clampAbs(double value, double max) {
        return Math.max(-max, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
