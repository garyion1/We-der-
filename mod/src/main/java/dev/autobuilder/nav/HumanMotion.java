package dev.autobuilder.nav;

import dev.autobuilder.config.BuilderConfig;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Motion and timing model for making the builder move the way a person does.
 *
 * The things that actually read as "a script" to anyone watching are uniform
 * timing and linear, perfectly-terminating aim. So none of that here:
 *
 *  - Aim follows a fast coarse sweep that decelerates into a slow fine settle,
 *    the way a hand on a mouse does, rather than turning at a constant rate.
 *  - A constant sub-degree tremor means no two placements ever share an angle,
 *    and the crosshair is never perfectly still.
 *  - Big turns sometimes overshoot slightly and get corrected on the next tick.
 *  - Fatigue accumulates over a session: everything slows a little and pauses
 *    stretch, so hour six doesn't look like minute one.
 *  - Delays are drawn from distributions, never fixed, and occasionally a long
 *    outlier lands -- the equivalent of glancing away for a moment.
 *
 * This is about looking natural rather than robotic. It is not built around any
 * particular server's detection, and doesn't spoof or fingerprint anything -- it
 * just avoids the dead giveaway of machine-perfect timing.
 */
public class HumanMotion {

    private final BuilderConfig config;
    private final BuilderConfig.Pace pace;
    private final ThreadLocalRandom rng = ThreadLocalRandom.current();

    /** Rises toward 1 over a long session; slows motion and lengthens pauses. */
    private int actionsDone;
    private static final int FATIGUE_FULL_AT = 900;

    /** Slow wander applied to the aim when standing still, so it never freezes. */
    private double driftPhaseYaw = Math.random() * Math.PI * 2;
    private double driftPhasePitch = Math.random() * Math.PI * 2;

    public HumanMotion(BuilderConfig config) {
        this.config = config;
        this.pace = config.pace;
    }

    public void noteAction() {
        actionsDone++;
    }

    /** 0 at the start of a session, 1 once thoroughly worn down. */
    public double fatigue() {
        if (!config.simulateFatigue) return 0;
        return Math.min(1.0, actionsDone / (double) FATIGUE_FULL_AT);
    }

    /** Scales every random spread; 0% makes timing and aim near-deterministic. */
    private double jitter() {
        return config.jitterPercent / 100.0;
    }

    /** Global multiplier on delays, on top of pace. */
    private double speedScale() {
        return config.speedPercent / 100.0;
    }

    private double fatigueSpeedFactor() {
        return 1.0 - 0.28 * fatigue();   // up to ~28% slower turning
    }

    private double fatigueDelayFactor() {
        return 1.0 + 0.65 * fatigue();   // and noticeably longer pauses
    }

    // ------------------------------------------------------------------ timing

    /** Ticks to hold still after an action. Log-normal-ish: mostly short, occasionally long. */
    public int dwellTicks() {
        double mean = (pace.minDwellMs + pace.maxDwellMs) / 2.0;
        double spread = (pace.maxDwellMs - pace.minDwellMs) / 2.0;
        double ms = mean + rng.nextGaussian() * spread * 0.6 * jitter();
        // Every so often something distracts a person for a beat longer.
        if (rng.nextDouble() < 0.08 * jitter()) ms *= rng.nextDouble(1.8, 3.5);
        ms = Math.max(pace.minDwellMs * 0.5, ms) * fatigueDelayFactor() * speedScale();
        return Math.max(1, (int) Math.round(ms / 50.0));
    }

    /** An extra beat, as if double-checking the placement. Grows more common when tired. */
    public boolean shouldHesitate() {
        return rng.nextDouble() < 0.06 + 0.10 * fatigue();
    }

    /** Look ahead at where the next block goes before finishing this one. */
    public boolean shouldGlanceAhead() {
        return rng.nextDouble() < 0.22;
    }

    /** Glance around at the surroundings mid-walk, rather than staring dead ahead. */
    public boolean shouldScanSurroundings() {
        return rng.nextDouble() < 0.05;
    }

    /** Baseline think-time before starting a placement, on top of dwell. */
    public int reactionDelayTicks() {
        double ms = rng.nextDouble(120, 380) * pace.actionDelayScale * fatigueDelayFactor() * speedScale();
        return Math.max(1, (int) Math.round(ms / 50.0));
    }

    /** Break length around the configured seconds -- never exactly the same twice. */
    public int breakTicks(int configuredSeconds) {
        double spread = 0.35 * jitter();
        double seconds = configuredSeconds * rng.nextDouble(1 - spread, 1 + spread * 1.4) * fatigueDelayFactor();
        return Math.max(20, (int) Math.round(seconds * 20));
    }

    // ------------------------------------------------------------------ aiming

    public record LookState(float yaw, float pitch) {}

    /**
     * Advance the aim one tick toward a target. Fast while far, decelerating into
     * a slow settle as it arrives, with tremor throughout and the occasional
     * overshoot on a big swing.
     */
    public LookState stepLook(LookState current, float targetYaw, float targetPitch) {
        float yawDelta = wrapDegrees(targetYaw - current.yaw());
        float pitchDelta = targetPitch - current.pitch();
        double distance = Math.hypot(yawDelta, pitchDelta);

        if (distance < 0.01) return applyTremor(current);

        // Ease-in-out on the approach: a long swing moves quickly in the middle
        // and eases into the target instead of stopping dead on arrival.
        double topSpeed = 22.0 * pace.motionSpeedFraction * fatigueSpeedFactor();
        double step = topSpeed * smoothstep(Math.min(1.0, distance / 55.0));

        // Fine-settle: the last few degrees are always slow and deliberate,
        // which is where machine-like aim gives itself away most.
        if (distance < 7.0) {
            step = Math.min(step, distance * 0.40 + 0.12);
        }
        step = Math.max(0.30, step);

        // A big swing occasionally goes slightly past and is corrected next tick.
        if (distance > 45 && rng.nextDouble() < 0.18) {
            step *= rng.nextDouble(1.05, 1.18);
        }

        double scale = Math.min(1.0, step / distance);
        float newYaw = current.yaw() + (float) (yawDelta * scale);
        float newPitch = current.pitch() + (float) (pitchDelta * scale);
        return applyTremor(new LookState(newYaw, clampPitch(newPitch)));
    }

    /** Sub-degree hand tremor -- always on, so the crosshair is never perfectly still. */
    private LookState applyTremor(LookState state) {
        float yaw = state.yaw() + (float) (rng.nextGaussian() * 0.075 * jitter());
        float pitch = state.pitch() + (float) (rng.nextGaussian() * 0.055 * jitter());
        return new LookState(wrapDegrees(yaw), clampPitch(pitch));
    }

    /**
     * Slow wander for when the builder is standing still (resting, waiting on a
     * purchase). Two out-of-phase sine drifts read as idle human sway rather than
     * a statue or a random twitch.
     */
    public LookState idleDrift(LookState current) {
        driftPhaseYaw += 0.013 + rng.nextDouble() * 0.004;
        driftPhasePitch += 0.009 + rng.nextDouble() * 0.003;
        float yaw = current.yaw() + (float) (Math.sin(driftPhaseYaw) * 0.55);
        float pitch = current.pitch() + (float) (Math.sin(driftPhasePitch) * 0.32);
        return applyTremor(new LookState(yaw, clampPitch(pitch)));
    }

    /** A yaw/pitch to glance at during a break -- somewhere plausible, not a random spin. */
    public LookState wanderTarget(LookState current) {
        float yaw = current.yaw() + (float) rng.nextDouble(-75, 75);
        float pitch = (float) rng.nextDouble(-35, 25);
        return new LookState(wrapDegrees(yaw), pitch);
    }

    public boolean lookCloseEnough(LookState current, float targetYaw, float targetPitch) {
        return Math.abs(wrapDegrees(targetYaw - current.yaw())) < 2.2f
                && Math.abs(targetPitch - current.pitch()) < 2.2f;
    }

    /** Fraction of full walking input to hold this tick -- gait is never perfectly even. */
    public boolean shouldEaseOffThrottle() {
        return rng.nextDouble() < 0.07;
    }

    // ------------------------------------------------------------------ helpers

    private static double smoothstep(double t) {
        return t * t * (3 - 2 * t);
    }

    private static float wrapDegrees(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private static float clampPitch(float pitch) {
        return Math.max(-90f, Math.min(90f, pitch));
    }
}
