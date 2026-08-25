package dev.autobuilder.schematic;

import java.util.function.BooleanSupplier;

/**
 * Keeps a LitematicFileSchematicSource pointed at whatever is currently placed
 * in Litematica, so the builder just follows it -- no file picker, no typing
 * coordinates.
 *
 * Checked periodically rather than every tick: LitematicaBridge goes through
 * reflection, and re-parsing a large .litematic on every single tick would be
 * wasteful when it only actually needs to happen when something changes.
 */
public class LitematicaSync {

    private static final int CHECK_INTERVAL_TICKS = 20; // once a second

    private final LitematicFileSchematicSource target;
    private final BooleanSupplier buildActive;
    private int ticksUntilCheck;
    private String status = "checking...";

    /**
     * @param buildActive true while a build is running/planning -- syncing is
     *                    skipped then, so an incidental change in Litematica
     *                    (or the sync catching it mid-edit) can't swap the
     *                    schematic out from under a build in progress.
     */
    public LitematicaSync(LitematicFileSchematicSource target, BooleanSupplier buildActive) {
        this.target = target;
        this.buildActive = buildActive;
    }

    /** Call once per client tick. */
    public void tick() {
        if (buildActive.getAsBoolean()) return;
        if (ticksUntilCheck-- > 0) return;
        ticksUntilCheck = CHECK_INTERVAL_TICKS;
        check();
    }

    private void check() {
        if (!LitematicaBridge.isLitematicaLoaded()) {
            status = "Litematica isn't installed";
            target.clear();
            return;
        }

        LitematicaBridge.Placement placement = LitematicaBridge.getActivePlacement();
        if (placement == null) {
            status = "No schematic placed in Litematica";
            target.clear();
            return;
        }

        if (!target.matches(placement.file(), placement.origin(), placement.rotation(), placement.mirror())) {
            target.load(placement.name(), placement.file(), placement.origin(),
                    placement.rotation(), placement.mirror());
        }
        status = target.isLoaded() ? "Following Litematica" : "Found a placement, but couldn't read it";
    }

    /** Force an immediate check rather than waiting out the interval -- used when the menu opens. */
    public void checkNow() {
        if (buildActive.getAsBoolean()) return;
        ticksUntilCheck = 0;
        check();
    }

    public String getStatus() { return status; }
}
