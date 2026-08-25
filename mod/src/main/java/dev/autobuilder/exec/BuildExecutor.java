package dev.autobuilder.exec;

import dev.autobuilder.config.BuilderConfig;
import dev.autobuilder.economy.AuctionHouseBuyer;
import dev.autobuilder.nav.HumanMotion;
import dev.autobuilder.nav.PathFinder;
import dev.autobuilder.planner.BuildPlanner;
import dev.autobuilder.planner.BuildPlanner.BlockPlacement;
import dev.autobuilder.planner.BuildPlanner.Plan;
import dev.autobuilder.schematic.SchematicSource;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * The tick-driven state machine that actually plays the game: walk, look,
 * clear what's in the way, place, restock, rest, repeat.
 *
 * Per placement the cycle is NAVIGATE (pearl-climbing where there's no walkable
 * route) -> ALIGN -> ENSURE_ITEM (shopping the auction house if enabled) ->
 * BREAK if something wrong occupies the spot -> PLACE -> DWELL, with REST
 * folded in every breakEveryBlocks placements.
 */
public class BuildExecutor {

    public enum State { IDLE, PLANNING, RUNNING, PAUSED, RETURNING_HOME, DONE, FAILED }
    private enum Step {
        NAVIGATE, PEARL_THROW, PEARL_WAIT, ALIGN, ENSURE_ITEM, FETCH_ITEM, BREAK, PLACE, DWELL, RETRY_WAIT, REST, SKIP
    }

    private final BuilderConfig config;
    private final SchematicSource schematic;
    private final AuctionHouseBuyer auctionBuyer;
    private HumanMotion motion;

    private State state = State.IDLE;
    private Step step = Step.NAVIGATE;
    private String statusMessage = "idle";

    private Plan plan;
    private int placementIndex;
    private int placedCount;
    private final List<BlockPlacement> skipped = new ArrayList<>();

    private List<PathFinder.PathStep> path = List.of();
    private int pathIndex;
    private HumanMotion.LookState look = new HumanMotion.LookState(0, 0);

    private int waitTicks;
    private BlockPos pearlLandingTarget;
    /** The creative-mode item fetch in progress, and which hotbar slot it's going into. */
    private Item fetchItem;
    private int fetchSlot;

    /** Materials the auction house couldn't supply; don't keep retrying them. */
    private final Set<Item> unbuyable = new HashSet<>();
    private int blocksSinceBreak;
    private int nextBreakAt = Integer.MAX_VALUE;
    private long buildStartedAtMs;
    private HumanMotion.LookState restGazeTarget;

    private boolean verifyPassDone;
    private BlockPos homePosition;
    /**
     * The schematic's own bounding box, padded by BUILD_AREA_MARGIN. Build
     * navigation is fenced to this box so the pathfinder can never wander off
     * looking for a route -- it either finds one that stays near the
     * structure, or the block is skipped. Not applied to the return-home walk.
     */
    private record BuildArea(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static BuildArea of(Collection<BlockPos> positions) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : positions) {
                minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
                minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
                minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
            }
            return new BuildArea(minX, minY, minZ, maxX, maxY, maxZ);
        }

        boolean contains(BlockPos p, int margin) {
            return p.getX() >= minX - margin && p.getX() <= maxX + margin
                    && p.getY() >= minY - margin && p.getY() <= maxY + margin
                    && p.getZ() >= minZ - margin && p.getZ() <= maxZ + margin;
        }
    }
    private static final int BUILD_AREA_MARGIN = 6;
    private BuildArea buildArea;
    /** Completion text held while walking home, so the final status keeps it. */
    private String finishedMessage = "";
    private int retriesOnCurrent;
    private int consecutiveFailures;
    /** Ticks spent mining the current obstruction; caps how long one block can stall. */
    private int breakTicks;
    private static final int MAX_BREAK_TICKS = 200; // 10 seconds
    /** How many already-built blocks a drift check samples before giving up. */
    private static final int DRIFT_SAMPLE = 400;
    private long lastVerifyAtMs;

    public BuildExecutor(BuilderConfig config, SchematicSource schematic) {
        this.config = config;
        this.schematic = schematic;
        this.auctionBuyer = new AuctionHouseBuyer(config);
        this.motion = new HumanMotion(config);
    }

    public void start(MinecraftClient client) {
        if (!schematic.isLoaded()) {
            statusMessage = "no schematic loaded";
            if (client.player != null) {
                client.player.sendMessage(Text.literal(
                        "[Auto Builder] Can't start: " + schematic.describe()
                                + " -- open [ and check the Build tab status."), false);
            }
            return;
        }
        this.verifyPassDone = false;
        this.retriesOnCurrent = 0;
        this.consecutiveFailures = 0;
        this.homePosition = client.player != null ? client.player.getBlockPos() : null;
        this.motion = new HumanMotion(config); // pick up any pace change from the GUI
        this.unbuyable.clear();
        this.blocksSinceBreak = 0;
        this.nextBreakAt = jitteredBreakInterval();
        this.buildStartedAtMs = System.currentTimeMillis();
        this.lastVerifyAtMs = this.buildStartedAtMs;
        state = State.PLANNING;
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[Auto Builder] Planning " + schematic.describe() + "..."), false);
        }
    }

    public void pause() {
        if (state == State.RUNNING) state = State.PAUSED;
        else if (state == State.PAUSED) state = State.RUNNING;
        resetInputs();
    }

    public void stop() {
        state = State.IDLE;
        resetInputs();
        statusMessage = "stopped";
    }

    public State getState() { return state; }

    /** True while a build is actually underway -- used to hold off re-syncing the schematic mid-build. */
    public boolean isActive() {
        return state == State.PLANNING || state == State.RUNNING || state == State.RETURNING_HOME;
    }
    public String getStatusMessage() { return statusMessage; }
    public int getPlacedCount() { return placedCount; }
    public int getTotalCount() { return plan == null ? 0 : plan.order().size(); }
    public int getSkippedCount() { return skipped.size(); }
    public int getFatiguePercent() { return (int) Math.round(motion.fatigue() * 100); }
    public int getRemovalCount() { return plan == null ? 0 : plan.removals(); }

    /** Which Y layer is being worked on, and how many there are. */
    public String getLayerProgress() {
        if (plan == null || placementIndex >= plan.order().size()) return "-";
        int y = plan.order().get(placementIndex).pos().getY();
        int total = plan.maxLayer() - plan.minLayer() + 1;
        int index = y - plan.minLayer() + 1;
        return "y=" + y + "  (" + index + " of " + total + ")";
    }

    /** Blocks placed per minute, measured over the run so far. */
    public double getBlocksPerMinute() {
        long elapsed = System.currentTimeMillis() - buildStartedAtMs;
        if (elapsed < 5000 || placedCount == 0) return 0;
        return placedCount / (elapsed / 60000.0);
    }

    /** Rough finish estimate from the observed rate; empty until it means something. */
    public String getEta() {
        double rate = getBlocksPerMinute();
        if (rate <= 0 || plan == null) return "--";
        int left = plan.order().size() - placementIndex;
        if (left <= 0) return "done";
        int minutes = (int) Math.ceil(left / rate);
        if (minutes < 60) return minutes + " min";
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    /** What the plan still needs, minus what's already carried. */
    public Map<Item, Integer> getShortfall(MinecraftClient client) {
        Map<Item, Integer> shortfall = new LinkedHashMap<>();
        if (plan == null || client.player == null) return shortfall;
        for (Map.Entry<Item, Integer> e : plan.materialsNeeded().entrySet()) {
            int have = countItem(client.player, e.getKey());
            int missing = e.getValue() - have;
            if (missing > 0) shortfall.put(e.getKey(), missing);
        }
        return shortfall;
    }

    public Map<Item, Integer> getMaterials() {
        return plan == null ? Map.of() : plan.materialsNeeded();
    }

    public boolean isAwaitingPurchaseConfirmation() { return auctionBuyer.awaitingConfirmation(); }
    public String getPurchasePrompt() { return auctionBuyer.confirmationPrompt(); }
    public void confirmPurchase() { auctionBuyer.confirmPurchase(); }
    public void declinePurchase() { auctionBuyer.declinePurchase(); }

    /** Call once per client tick (client.player/world guaranteed non-null by caller). */
    public void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        switch (state) {
            case PLANNING -> doPlan(client, player);
            case RUNNING -> doRun(client, player);
            case RETURNING_HOME -> handleReturnHome(client, player);
            default -> { /* IDLE / PAUSED / DONE / FAILED: no per-tick work */ }
        }
    }

    private void doPlan(MinecraftClient client, ClientPlayerEntity player) {
        Map<BlockPos, BlockState> target = schematic.getTargetBlocks();
        buildArea = target.isEmpty() ? null : BuildArea.of(target.keySet());
        Map<BlockPos, BlockState> toPlace = new HashMap<>();
        for (var entry : target.entrySet()) {
            BlockState wanted = entry.getValue();
            if (config.skipFluids && !wanted.getFluidState().isEmpty()) continue;
            // Chests/signs would be placed empty -- their contents aren't read
            // from the schematic -- so there's an option to leave them out.
            if (config.skipBlockEntities && wanted.hasBlockEntity()) continue;

            BlockState current = client.world.getBlockState(entry.getKey());
            if (!current.equals(wanted)) {
                toPlace.put(entry.getKey(), wanted);
            }
        }

        Set<BlockPos> worldSolid = new HashSet<>();
        for (BlockPos pos : toPlace.keySet()) {
            for (Direction d : Direction.values()) {
                BlockPos n = pos.offset(d);
                if (!toPlace.containsKey(n) && !client.world.getBlockState(n).isAir()) {
                    worldSolid.add(n);
                }
            }
        }

        // Anything standing inside the schematic's own bounds that the schematic
        // doesn't call for is a deviation. Optionally clear it, so the finished
        // build matches the schematic rather than merely containing it.
        Set<BlockPos> toRemove = new HashSet<>();
        if (config.removeExtraBlocks) {
            for (BlockPos pos : target.keySet()) {
                // (bounds are the schematic's own positions; only those are touched)
                BlockState wanted = target.get(pos);
                BlockState actual = client.world.getBlockState(pos);
                if (wanted != null && wanted.isAir() && !actual.isAir()) {
                    toRemove.add(pos);
                }
            }
        }

        BuildPlanner planner = new BuildPlanner(config);
        plan = planner.plan(toPlace, toRemove, worldSolid, player.getBlockPos());
        placementIndex = 0;
        retriesOnCurrent = 0;
        consecutiveFailures = 0;
        // A verify pass keeps the running totals -- it's the same build continuing,
        // not a new one.
        if (!verifyPassDone) {
            placedCount = 0;
            skipped.clear();
        }

        if (plan.order().isEmpty()) {
            resetInputs();
            String done = verifyPassDone
                    ? "complete and verified: " + placedCount + " placed"
                    : "nothing to build -- world already matches the schematic";
            // Head home from here too, not just from the end of doRun -- with the
            // verify pass on, a finished build always lands in this branch.
            if (!verifyPassDone) {
                player.sendMessage(Text.literal("[Auto Builder] " + done), false);
            }
            if (beginReturnHomeIfWanted(player, done)) return;
            state = State.DONE;
            statusMessage = done;
            return;
        }

        state = State.RUNNING;
        step = Step.NAVIGATE;
        statusMessage = "building " + plan.order().size() + " blocks (" + plan.scaffoldBlocksUsed() + " scaffold)";
    }

    private void doRun(MinecraftClient client, ClientPlayerEntity player) {
        String abort = checkSafety(client, player);
        if (abort != null) {
            resetInputs();
            state = State.PAUSED;
            statusMessage = abort;
            player.sendMessage(Text.literal("[Auto Builder] paused: " + abort), false);
            return;
        }

        if (placementIndex >= plan.order().size()) {
            // A verify pass re-diffs the schematic against the world and replans
            // whatever is still missing: placements can silently fail (server
            // rejection, a mob in the way, a mistimed click), and the only honest
            // way to know the build is actually complete is to look again.
            if (config.verifyPass && !verifyPassDone) {
                verifyPassDone = true;
                statusMessage = "verifying -- re-checking for missed blocks";
                state = State.PLANNING;
                return;
            }
            resetInputs();
            String done = "complete: " + placedCount + " placed"
                    + (skipped.isEmpty() ? "" : ", " + skipped.size() + " skipped");
            player.sendMessage(Text.literal("[Auto Builder] complete: " + placedCount + " placed"
                    + (skipped.isEmpty() ? "" : ", " + skipped.size() + " skipped")), false);
            if (beginReturnHomeIfWanted(player, done)) return;
            state = State.DONE;
            statusMessage = done;
            return;
        }

        // Periodically re-diff the whole schematic mid-build. Water spreads, mobs
        // break things, another player edits the area, a placement silently
        // failed -- without this the builder would march on past all of it.
        if (config.continuousVerify
                && System.currentTimeMillis() - lastVerifyAtMs > config.verifyIntervalSeconds * 1000L) {
            lastVerifyAtMs = System.currentTimeMillis();
            if (hasDrifted(client)) {
                statusMessage = "schematic drifted -- replanning";
                resetInputs();
                state = State.PLANNING;
                return;
            }
        }

        BlockPlacement bp = plan.order().get(placementIndex);

        switch (step) {
            case NAVIGATE -> handleNavigate(client, player, bp);
            case PEARL_THROW -> handlePearlThrow(client, player);
            case PEARL_WAIT -> handlePearlWait(client, player);
            case ALIGN -> handleAlign(client, player, bp);
            case ENSURE_ITEM -> handleEnsureItem(client, player, bp);
            case FETCH_ITEM -> handleFetchItem(client, player);
            case BREAK -> handleBreak(client, bp);
            case PLACE -> handlePlace(client, player, bp);
            case DWELL -> handleDwell(player);
            case RETRY_WAIT -> {
                if (waitTicks-- <= 0) step = Step.ALIGN;
            }
            case REST -> handleRest(player);
            case SKIP -> advance(false);
        }
    }

    /** Returns a reason to stop, or null to carry on. */
    private String checkSafety(MinecraftClient client, ClientPlayerEntity player) {
        if (config.stopOnLowHealth && player.getHealth() <= config.lowHealthThreshold) {
            return "health below " + (config.lowHealthThreshold / 2.0) + " hearts";
        }
        if (config.maxBuildMinutes > 0
                && System.currentTimeMillis() - buildStartedAtMs > config.maxBuildMinutes * 60_000L) {
            return "hit the " + config.maxBuildMinutes + " minute time limit";
        }
        if (config.stopOnLowHunger && player.getHungerManager().getFoodLevel() <= config.lowHungerThreshold) {
            return "hunger below " + config.lowHungerThreshold;
        }
        if (config.stopOnPlayerNearby && client.world != null) {
            double radiusSq = (double) config.stopOnPlayerRadius * config.stopOnPlayerRadius;
            for (PlayerEntity other : client.world.getPlayers()) {
                if (other != player && other.squaredDistanceTo(player) <= radiusSq) {
                    return other.getName().getString() + " came within "
                            + config.stopOnPlayerRadius + " blocks";
                }
            }
        }
        if (config.stopWhenInventoryFull && isInventoryFull(player)) {
            return "inventory is full";
        }
        if (consecutiveFailures >= config.stopAfterConsecutiveFailures) {
            // "Something is wrong" told the user nothing. statusMessage already
            // carries the reason for whichever of those failures happened most
            // recently (couldn't path there, no support to build from, wrong
            // block landed, ...), so surface it instead of a dead end.
            return consecutiveFailures + " placements in a row failed -- last reason: " + statusMessage;
        }
        return null;
    }

    /**
     * Has anything already built stopped matching the schematic? Only checks the
     * portion the plan has passed, since what's ahead is expected to be wrong.
     */
    private boolean hasDrifted(MinecraftClient client) {
        Map<BlockPos, BlockState> target = schematic.getTargetBlocks();
        int checked = 0;
        for (int i = 0; i < placementIndex && checked < DRIFT_SAMPLE; i++) {
            BlockPlacement bp = plan.order().get(i);
            if (bp.isRemoval() || bp.temporaryScaffold()) continue;
            BlockState wanted = target.get(bp.pos());
            if (wanted == null) continue;
            checked++;
            if (!matchesTarget(client.world.getBlockState(bp.pos()), wanted)) return true;
        }
        return false;
    }

    private boolean isInventoryFull(ClientPlayerEntity player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).isEmpty()) return false;
        }
        return true;
    }

    /**
     * A pause between stretches of work. Not a freeze: the aim drifts the whole
     * time, and every so often it settles on somewhere new to look, the way
     * someone standing back to survey their work does.
     */
    private void handleRest(ClientPlayerEntity player) {
        if (config.lookAroundOnBreak) {
            if (restGazeTarget == null || waitTicks % 60 == 0) {
                restGazeTarget = motion.wanderTarget(look);
            }
            look = motion.stepLook(look, restGazeTarget.yaw(), restGazeTarget.pitch());
        } else {
            look = motion.idleDrift(look);
        }
        player.setYaw(look.yaw());
        player.setPitch(look.pitch());

        if (waitTicks-- <= 0) {
            restGazeTarget = null;
            step = Step.NAVIGATE;
        }
    }

    // -- NAVIGATE ------------------------------------------------------

    private void handleNavigate(MinecraftClient client, ClientPlayerEntity player, BlockPlacement bp) {
        if (bp.supportNeighbor() == null && !bp.isRemoval()) {
            // A stranded placement (nothing solid nearby, scaffold couldn't
            // reach it either) used to fall into computeStandPosition's
            // removal branch anyway -- stand below and look up, as if there
            // were something here to break -- and only discover there was
            // nothing to build against after actually walking there. With
            // several of these scattered around a floating section, that
            // reads as wandering pointlessly from spot to spot. Skip it here,
            // before a single step is taken.
            statusMessage = "skipping " + bp.pos().toShortString() + " -- nothing solid nearby to build from";
            skipped.add(bp);
            consecutiveFailures++;
            advance(false);
            return;
        }
        if (path.isEmpty() && pathIndex == 0) {
            BlockPos standGoal = computeStandPosition(client, player, bp);
            path = buildPath(client, player, standGoal, true, true);
            if (path.isEmpty()) {
                // Can't route there at all -- give up on this block rather than stall forever.
                statusMessage = "couldn't find a way to " + bp.pos().toShortString();
                skipped.add(bp);
                consecutiveFailures++;
                resetInputs();
                advance(false);
                return;
            }
        }

        if (pathIndex >= path.size()) {
            resetInputs();
            path = List.of();
            pathIndex = 0;
            step = Step.ALIGN;
            return;
        }
        walkOneStep(client, player);
    }

    /**
     * Switches to walking home, if that's wanted and we aren't already there.
     * Returns true if the walk has taken over.
     */
    private boolean beginReturnHomeIfWanted(ClientPlayerEntity player, String completionMessage) {
        if (!config.returnHomeWhenDone || homePosition == null) return false;
        if (player.getBlockPos().isWithinDistance(homePosition, 2.0)) return false;
        finishedMessage = completionMessage;
        path = List.of();
        pathIndex = 0;
        state = State.RETURNING_HOME;
        statusMessage = "heading back to the start";
        return true;
    }

    /**
     * Walks back to where the build began. Deliberately routed without pearl
     * climbing: the pearl steps are driven by the placement state machine, which
     * isn't running any more, so a pearl step here would never be thrown and the
     * walk would stall forever.
     */
    private void handleReturnHome(MinecraftClient client, ClientPlayerEntity player) {
        if (path.isEmpty() && pathIndex == 0) {
            path = buildPath(client, player, homePosition, false, false);
            if (path.isEmpty()) {
                finishReturn("couldn't find a way back to the start");
                return;
            }
        }
        if (pathIndex >= path.size() || player.getBlockPos().isWithinDistance(homePosition, 2.0)) {
            finishReturn("back at the start");
            return;
        }
        walkOneStep(client, player);
    }

    private void finishReturn(String note) {
        resetInputs();
        state = State.DONE;
        statusMessage = finishedMessage + " -- " + note;
    }

    /**
     * Puts the fastest available tool for this block in hand. Uses the game's own
     * mining-speed calculation rather than a hardcoded table, so it picks
     * sensibly for modded and vanilla blocks alike.
     */
    private void selectBestTool(MinecraftClient client, ClientPlayerEntity player, BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        var inventory = player.getInventory();
        int bestSlot = inventory.getSelectedSlot();
        float bestSpeed = inventory.getStack(bestSlot).getMiningSpeedMultiplier(state);

        for (int i = 0; i <= 8; i++) {
            float speed = inventory.getStack(i).getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        if (bestSlot != inventory.getSelectedSlot()) {
            selectHotbarSlot(player, bestSlot);
        }
    }

    private List<PathFinder.PathStep> buildPath(MinecraftClient client, ClientPlayerEntity player,
                                                BlockPos goal, boolean allowPearls, boolean fenceToSchematic) {
        // Fencing keeps the search from ever proposing a step far from the
        // structure -- without it, a blocked direct route makes A* fan out in
        // every direction looking for *a* way through, which is what sent the
        // bot wandering off. If the player is currently outside the fence
        // (shouldn't normally happen) drop it rather than trap them with no path.
        boolean applyFence = fenceToSchematic && buildArea != null
                && buildArea.contains(player.getBlockPos(), BUILD_AREA_MARGIN);
        PathFinder finder = new PathFinder(
                pos -> isStandable(client, pos) && (!applyFence || buildArea.contains(pos, BUILD_AREA_MARGIN)),
                allowPearls && config.usePearlClimbing, 32,
                config.allowJump, config.maxFallDistance);
        return finder.findPath(player.getBlockPos(), goal, config.maxPathNodes);
    }

    private void walkOneStep(MinecraftClient client, ClientPlayerEntity player) {
        PathFinder.PathStep current = path.get(pathIndex);
        if (current.type() == PathFinder.StepType.PEARL_CLIMB) {
            pearlLandingTarget = current.pos();
            step = Step.PEARL_THROW;
            return;
        }

        stepToward(client, player, current.pos());
        if (config.allowJump && current.type() == PathFinder.StepType.JUMP && player.isOnGround()) {
            client.options.jumpKey.setPressed(true);
        }
        // Sneak when there's a drop next to the next step, so a mistimed input
        // doesn't walk off the edge of what's being built.
        client.options.sneakKey.setPressed(config.sneakNearEdges && nextToDrop(client, current.pos()));

        if (player.getBlockPos().isWithinDistance(current.pos(), 0.5)) {
            client.options.jumpKey.setPressed(false);
            pathIndex++;
        }
    }

    /** True if any block orthogonally adjacent to this one has nothing solid beneath it. */
    private boolean nextToDrop(MinecraftClient client, BlockPos pos) {
        var world = client.world;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = pos.offset(d).down();
            if (world.getBlockState(neighbor).isAir()
                    && world.getBlockState(neighbor.down()).isAir()) {
                return true;
            }
        }
        return false;
    }

    private BlockPos computeStandPosition(MinecraftClient client, ClientPlayerEntity player, BlockPlacement bp) {
        if (bp.supportNeighbor() == null || bp.isRemoval()) {
            // Directly below, looking up, is the natural spot to break a block --
            // but unlike the other branches below, this was never actually
            // checked for standability. A removal target sitting over a hole or
            // hazard would silently fail to path every time. Fall back to a
            // horizontal neighbour the same way an UP-face placement does.
            BlockPos below = bp.pos().down();
            if (isStandable(client, below)) return below;
            return closestStandableNeighbor(client, player, bp.pos(), below);
        }
        Direction face = bp.clickFace();
        if (face == null) return bp.pos().down();

        // NEVER return bp.pos() -- the bot would be standing inside the target block,
        // and the server rejects block placement when it collides with the player.

        if (face == Direction.DOWN) {
            // Placing on ceiling: support is directly above target.
            // Stand 2 below the target, look up.
            return bp.pos().down(2);
        }
        if (face == Direction.UP) {
            // Placing on top: support is directly below target. Any of the four
            // horizontal neighbours (at target's Y) works, since the layer below
            // is already solid there in a bottom-up build. Pick whichever is
            // actually standable and closest to the player right now, instead of
            // blindly walking north -- that's what sent the bot away from builds
            // that face south/east/west.
            return closestStandableNeighbor(client, player, bp.pos(), bp.pos().north());
        }
        // Horizontal face: support is to one side of the target. Standing one
        // block past the target in the click direction is the natural spot, but
        // fall back to the nearest standable neighbour if that spot is blocked.
        BlockPos primary = bp.pos().offset(face);
        if (isStandable(client, primary)) return primary;
        return closestStandableNeighbor(client, player, bp.pos(), primary);
    }

    /** Nearest standable position (to the player) among the four horizontal neighbours of pos. */
    private BlockPos closestStandableNeighbor(MinecraftClient client, ClientPlayerEntity player,
                                               BlockPos pos, BlockPos fallback) {
        BlockPos playerPos = player.getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos candidate = pos.offset(d);
            if (!isStandable(client, candidate)) continue;
            double dist = playerPos.getSquaredDistance(candidate.getX(), candidate.getY(), candidate.getZ());
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best != null ? best : fallback;
    }

    private boolean isStandable(MinecraftClient client, BlockPos feet) {
        var world = client.world;
        if (world.getBlockState(feet).isSolidBlock(world, feet)) return false;
        if (world.getBlockState(feet.up()).isSolidBlock(world, feet.up())) return false;
        if (!world.getBlockState(feet.down()).isSolidBlock(world, feet.down())) return false;

        if (config.avoidHazards) {
            if (isHazard(client, feet) || isHazard(client, feet.up()) || isHazard(client, feet.down())) {
                return false;
            }
            // Don't stand right next to lava either -- close enough to burn.
            for (Direction d : Direction.Type.HORIZONTAL) {
                if (isHazard(client, feet.offset(d))) return false;
            }
        }
        return true;
    }

    private boolean isHazard(MinecraftClient client, BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        return state.isOf(Blocks.LAVA) || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.MAGMA_BLOCK) || state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.POWDER_SNOW);
    }

    // -- PEARL CLIMB -----------------------------------------------------
    // Approximate: binary-search a throw pitch that a simple gravity+drag
    // simulation predicts will land near the target. Real net latency and
    // server-side pearl physics will disagree with this somewhat -- expect
    // to tune PEARL_SPEED/GRAVITY/DRAG or the pitch search bounds in-game.

    private static final double PEARL_SPEED = 1.5, PEARL_GRAVITY = 0.03, PEARL_DRAG = 0.99;

    private void handlePearlThrow(MinecraftClient client, ClientPlayerEntity player) {
        int pearlSlot = findHotbarOrInventorySlot(player, Items.ENDER_PEARL);
        int pearlCount = countItem(player, Items.ENDER_PEARL);
        if (pearlSlot < 0 || pearlCount <= config.pearlReserve) {
            statusMessage = "out of ender pearls (reserve=" + config.pearlReserve + ") -- can't climb, skipping block";
            consecutiveFailures++;
            advance(false);
            return;
        }
        selectHotbarSlot(player, pearlSlot);

        Vec3d eye = player.getEyePos();
        double dx = pearlLandingTarget.getX() + 0.5 - eye.x;
        double dz = pearlLandingTarget.getZ() + 0.5 - eye.z;
        double dy = pearlLandingTarget.getY() - eye.y;
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float targetPitch = solvePearlPitch(horizDist, dy);

        look = motion.stepLook(look, targetYaw, targetPitch);
        player.setYaw(look.yaw());
        player.setPitch(look.pitch());

        if (motion.lookCloseEnough(look, targetYaw, targetPitch)) {
            client.interactionManager.interactItem(player, Hand.MAIN_HAND);
            waitTicks = 40; // give the pearl time to fly + teleport to resolve
            step = Step.PEARL_WAIT;
        }
    }

    private float solvePearlPitch(double horizontalDistance, double desiredDy) {
        float lo = -80f, hi = 75f;
        for (int i = 0; i < 20; i++) {
            float mid = (lo + hi) / 2f;
            double landedDy = simulatePearlDy(mid, horizontalDistance);
            if (landedDy > desiredDy) lo = mid; else hi = mid;
        }
        return (lo + hi) / 2f;
    }

    private double simulatePearlDy(double pitchDeg, double horizontalDistance) {
        double pitchRad = Math.toRadians(pitchDeg);
        double vHoriz = PEARL_SPEED * Math.cos(pitchRad);
        double vY = -PEARL_SPEED * Math.sin(pitchRad);
        double x = 0, y = 0;
        for (int t = 0; t < 400 && x < horizontalDistance; t++) {
            x += vHoriz;
            y += vY;
            vY = (vY - PEARL_GRAVITY) * PEARL_DRAG;
            vHoriz *= PEARL_DRAG;
        }
        return y;
    }

    private void handlePearlWait(MinecraftClient client, ClientPlayerEntity player) {
        if (waitTicks-- <= 0 || player.getBlockPos().isWithinDistance(pearlLandingTarget, 3.0)) {
            pathIndex++;
            step = Step.NAVIGATE;
        }
    }

    // -- ALIGN / PLACE ---------------------------------------------------

    private void handleAlign(MinecraftClient client, ClientPlayerEntity player, BlockPlacement bp) {
        if (bp.supportNeighbor() == null || bp.clickFace() == null) {
            // Planner couldn't find anything to support this one -- nothing
            // touches it (not the world, not anything built so far), and even
            // SCAFFOLD_AWARE found no ground within reach to build a column up
            // from. This is what a genuinely floating section (nothing solid
            // for dozens of blocks in any direction -- open water, a void
            // world, a schematic placed somewhere it doesn't actually rest on
            // anything) looks like: there's nothing sensible to click, so skip
            // it, but say so instead of just counting a silent failure.
            statusMessage = "skipping " + bp.pos().toShortString() + " -- nothing solid nearby to build from";
            skipped.add(bp);
            advance(false);
            return;
        }

        Vec3d faceCenter = Vec3d.ofCenter(bp.supportNeighbor())
                .add(bp.clickFace().getOffsetX() * 0.5, bp.clickFace().getOffsetY() * 0.5, bp.clickFace().getOffsetZ() * 0.5);
        Vec3d eye = player.getEyePos();
        double dx = faceCenter.x - eye.x, dy = faceCenter.y - eye.y, dz = faceCenter.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));

        look = motion.stepLook(look, targetYaw, targetPitch);
        player.setYaw(look.yaw());
        player.setPitch(look.pitch());

        if (motion.lookCloseEnough(look, targetYaw, targetPitch)) {
            step = Step.ENSURE_ITEM;
        }
    }

    private void handleEnsureItem(MinecraftClient client, ClientPlayerEntity player, BlockPlacement bp) {
        // A removal needs no material -- just the right tool, then break it.
        if (bp.isRemoval()) {
            if (config.autoSelectTool) selectBestTool(client, player, bp.pos());
            step = Step.BREAK;
            return;
        }
        Item needed = bp.state().getBlock().asItem();
        // Some blocks (fire, fluids that slipped through skipFluids) have no placeable
        // item form. asItem() returns AIR, which would incorrectly match empty hotbar slots.
        if (needed == null || needed == net.minecraft.item.Items.AIR) {
            skipped.add(bp);
            advance(false);
            return;
        }
        int hotbarSlot = findHotbarOrInventorySlot(player, needed);
        if (hotbarSlot >= 0) {
            selectHotbarSlot(player, hotbarSlot);
            boolean occupied = !client.world.getBlockState(bp.pos()).isAir();
            if (config.breakWrongBlocks && occupied) {
                if (config.autoSelectTool) selectBestTool(client, player, bp.pos());
                step = Step.BREAK;
            } else {
                step = Step.PLACE;
            }
            return;
        }

        // Creative mode: fetch the item into the active hotbar slot. No purchase
        // needed, but it opens the inventory and pauses for a beat first rather
        // than the item just appearing mid-air the instant it's needed.
        if (player.getAbilities().creativeMode || config.serverPreset == BuilderConfig.ServerPreset.CREATIVE) {
            fetchItem = needed;
            fetchSlot = player.getInventory().getSelectedSlot();
            waitTicks = motion.dwellTicks();
            step = Step.FETCH_ITEM;
            return;
        }

        // Out of this material.
        if (config.autoBuyMaterials && needed != null && !unbuyable.contains(needed)) {
            if (auctionBuyer.active()) {
                auctionBuyer.tick(client);
                return;
            }
            switch (auctionBuyer.getPhase()) {
                case DONE -> {
                    auctionBuyer.reset();
                    return; // re-check the inventory next tick
                }
                case FAILED -> {
                    unbuyable.add(needed);
                    statusMessage = "couldn't buy " + needed.getName().getString()
                            + ": " + auctionBuyer.getLastError();
                    player.sendMessage(Text.literal("[Auto Builder] " + statusMessage), false);
                    auctionBuyer.reset();
                    return;
                }
                default -> {
                    // Buy enough for the rest of the build, not just this one block --
                    // one shopping trip instead of one per placement.
                    int stillNeeded = remainingNeedFor(needed);
                    int target = (int) Math.ceil(stillNeeded * (1 + config.buyExtraPercent / 100.0));
                    statusMessage = "buying " + target + "x " + needed.getName().getString();
                    auctionBuyer.startShopping(client, needed, target);
                    return;
                }
            }
        }

        if (config.outOfMaterials == BuilderConfig.OutOfMaterialsPolicy.PAUSE_BUILD) {
            resetInputs();
            state = State.PAUSED;
            statusMessage = "out of " + (needed == null ? "materials" : needed.getName().getString());
            player.sendMessage(Text.literal("[Auto Builder] paused: " + statusMessage), false);
            return;
        }
        skipped.add(bp);
        advance(false);
    }

    /**
     * Opens the inventory and holds for a beat before the creative item
     * actually lands in the hotbar, rather than it appearing on the exact
     * tick it was needed with no visible action at all.
     */
    private void handleFetchItem(MinecraftClient client, ClientPlayerEntity player) {
        if (client.currentScreen == null) {
            client.setScreen(new InventoryScreen(player));
        }
        if (waitTicks-- > 0) return;
        player.getInventory().setStack(fetchSlot, new ItemStack(fetchItem, 64));
        selectHotbarSlot(player, fetchSlot);
        if (client.currentScreen instanceof InventoryScreen) client.setScreen(null);
        fetchItem = null;
        step = Step.PLACE;
    }

    /** Blocks until the next break, spread +/-35% around the configured interval. */
    private int jitteredBreakInterval() {
        double jittered = config.breakEveryBlocks * (0.65 + Math.random() * 0.7);
        return Math.max(8, (int) Math.round(jittered));
    }

    /**
     * Is what's in the world what the schematic wanted?
     *
     * Full state equality is too strict in practice: fences, walls, stairs and
     * redstone rewrite their own connection/shape properties from surroundings,
     * so a correctly-placed block legitimately differs from the schematic's
     * recorded state until its neighbours exist. Comparing the block itself
     * catches the case that actually matters -- the wrong material -- without
     * fighting blocks that adjust themselves.
     */
    private boolean matchesTarget(BlockState actual, BlockState wanted) {
        if (wanted == null) return actual.isAir();          // removal
        if (actual.isAir()) return false;
        return config.strictBlockMatch
                ? actual.getBlock() == wanted.getBlock()
                : !actual.isAir();
    }

    /** How many more of this item the rest of the plan calls for. */
    private int remainingNeedFor(Item item) {
        int count = 0;
        for (int i = placementIndex; i < plan.order().size(); i++) {
            BlockPlacement placement = plan.order().get(i);
            if (placement.state().getBlock().asItem() == item) count++;
        }
        return Math.max(1, count);
    }

    /** Clear a block that's in the target position but isn't what the schematic wants. */
    private void handleBreak(MinecraftClient client, BlockPlacement bp) {
        if (client.world.getBlockState(bp.pos()).isAir()) {
            breakTicks = 0;
            if (bp.isRemoval()) {
                // Nothing left to place -- the removal is done.
                consecutiveFailures = 0;
                advance(false);
            } else {
                step = Step.PLACE;
            }
            return;
        }
        // Bedrock, a protected region, or the wrong tool would otherwise keep this
        // swinging forever, so give up after a while and move on.
        if (++breakTicks > MAX_BREAK_TICKS) {
            breakTicks = 0;
            statusMessage = "couldn't break the block at " + bp.pos().toShortString();
            skipped.add(bp);
            consecutiveFailures++;
            advance(false);
            return;
        }
        Direction face = bp.clickFace() != null ? bp.clickFace() : Direction.UP;
        client.interactionManager.updateBlockBreakingProgress(bp.pos(), face);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    private void handlePlace(MinecraftClient client, ClientPlayerEntity player, BlockPlacement bp) {
        if (bp.supportNeighbor() == null || bp.clickFace() == null) {
            // Stranded block with no computed support -- can't place it. Skip.
            statusMessage = "skipping " + bp.pos().toShortString() + " -- nothing solid nearby to build from";
            skipped.add(bp);
            consecutiveFailures++;
            advance(false);
            return;
        }
        Direction face = bp.clickFace();
        Vec3d hitPos = Vec3d.ofCenter(bp.supportNeighbor())
                .add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitPos, face, bp.supportNeighbor(), false);
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        player.swingHand(Hand.MAIN_HAND);
        motion.noteAction();

        // Did the RIGHT block land? Checking only for "not air" would accept a
        // wrong block as success -- exactly the case that makes a build drift
        // away from the schematic. Compare against what was asked for.
        BlockState now = client.world.getBlockState(bp.pos());
        boolean correct = matchesTarget(now, bp.state());
        if (!correct && retriesOnCurrent < config.maxRetriesPerBlock) {
            retriesOnCurrent++;
            consecutiveFailures++;
            // Re-aim and try the same block again rather than moving on --
            // DWELL would advance the index.
            waitTicks = motion.reactionDelayTicks();
            step = Step.RETRY_WAIT;
            return;
        }
        if (correct) {
            placedCount++;
            consecutiveFailures = 0;
        } else {
            // Wrong block sitting in a schematic position is a deviation, so say
            // so rather than quietly counting it.
            if (!now.isAir()) {
                statusMessage = "wrong block at " + bp.pos().toShortString()
                        + ": got " + now.getBlock().getName().getString();
            }
            skipped.add(bp);
            consecutiveFailures++;
        }
        retriesOnCurrent = 0;

        waitTicks = motion.dwellTicks();
        step = Step.DWELL;
    }

    /** The beat after placing a block, before moving on to the next one. */
    private void handleDwell(ClientPlayerEntity player) {
        if (waitTicks-- <= 0) {
            advance(true);
        }
    }

    private void advance(boolean placedOk) {
        placementIndex++;
        path = List.of();
        pathIndex = 0;
        step = Step.NAVIGATE;

        if (placedOk && config.takeBreaks && ++blocksSinceBreak >= nextBreakAt) {
            blocksSinceBreak = 0;
            // Jitter the interval too -- breaks landing on exactly every Nth
            // block would be as telling as a fixed break length.
            nextBreakAt = jitteredBreakInterval();
            waitTicks = motion.breakTicks(config.breakSeconds);
            restGazeTarget = null;
            statusMessage = "taking a break (" + (waitTicks / 20) + "s)";
            resetInputs();
            step = Step.REST;
            return;
        }
        if (placedOk && motion.shouldHesitate()) {
            waitTicks = motion.reactionDelayTicks();
            step = Step.DWELL;
        }
    }

    // -- movement / inventory helpers ------------------------------------

    private void stepToward(MinecraftClient client, ClientPlayerEntity player, BlockPos target) {
        Vec3d targetCenter = Vec3d.ofBottomCenter(target);
        double dx = targetCenter.x - player.getX(), dz = targetCenter.z - player.getZ();
        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;

        look = motion.stepLook(look, targetYaw, Math.max(-25f, Math.min(15f, look.pitch())));
        player.setYaw(look.yaw());
        player.setPitch(look.pitch());

        // Gait isn't perfectly even -- the odd tick of easing off the key reads
        // as a person walking rather than a held-down input.
        client.options.forwardKey.setPressed(!motion.shouldEaseOffThrottle());
        client.options.sprintKey.setPressed(config.allowSprint);
    }

    private void resetInputs() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
    }

    private int countItem(ClientPlayerEntity player, Item item) {
        int count = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).getItem() == item) count += inv.getStack(i).getCount();
        }
        return count;
    }

    /** Returns a hotbar index (0-8) with this item, swapping it in from the main inventory if needed. */
    private int findHotbarOrInventorySlot(ClientPlayerEntity player, Item item) {
        var inv = player.getInventory();
        for (int i = 0; i <= 8; i++) {
            if (inv.getStack(i).getItem() == item) return i;
        }
        for (int i = 9; i < inv.size(); i++) {
            if (inv.getStack(i).getItem() == item) {
                int targetHotbar = inv.getSelectedSlot();
                MinecraftClient client = MinecraftClient.getInstance();
                client.interactionManager.clickSlot(player.currentScreenHandler.syncId, i, targetHotbar, SlotActionType.SWAP, player);
                return targetHotbar;
            }
        }
        return -1;
    }

    private void selectHotbarSlot(ClientPlayerEntity player, int hotbarIndex) {
        player.getInventory().setSelectedSlot(hotbarIndex);
    }
}
