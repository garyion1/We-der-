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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
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
 * place, restock, repeat. This is the file most likely to need hand-fixing
 * once you build against real 1.21.11 mappings -- method names shift
 * slightly release to release and this was written without a compiler in
 * the loop (no network access to Mojang/Fabric Maven from this environment).
 * The algorithmic pieces (planning order, A*, human-motion easing, the
 * .litematic parser) don't depend on exact Minecraft API surface and are
 * the parts to trust most; this file is glue on top of them.
 */
public class BuildExecutor {

    public enum State { IDLE, PLANNING, RUNNING, PAUSED, DONE, FAILED }
    private enum Step { NAVIGATE, PEARL_THROW, PEARL_WAIT, ALIGN, ENSURE_ITEM, PLACE, DWELL, SKIP }

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

    public BuildExecutor(BuilderConfig config, SchematicSource schematic) {
        this.config = config;
        this.schematic = schematic;
        this.auctionBuyer = new AuctionHouseBuyer(config);
        this.motion = new HumanMotion(config.pace);
    }

    public void start(MinecraftClient client) {
        if (!schematic.isLoaded()) {
            statusMessage = "no schematic loaded";
            return;
        }
        this.motion = new HumanMotion(config.pace); // pick up any pace change from the GUI
        state = State.PLANNING;
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
    public String getStatusMessage() { return statusMessage; }
    public int getPlacedCount() { return placedCount; }
    public int getTotalCount() { return plan == null ? 0 : plan.order().size(); }

    /** Call once per client tick (client.player/world guaranteed non-null by caller). */
    public void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        switch (state) {
            case PLANNING -> doPlan(client, player);
            case RUNNING -> doRun(client, player);
            default -> { /* IDLE / PAUSED / DONE / FAILED: no per-tick work */ }
        }
    }

    private void doPlan(MinecraftClient client, ClientPlayerEntity player) {
        Map<BlockPos, BlockState> target = schematic.getTargetBlocks();
        Map<BlockPos, BlockState> toPlace = new HashMap<>();
        for (var entry : target.entrySet()) {
            BlockState current = client.world.getBlockState(entry.getKey());
            if (!current.equals(entry.getValue())) {
                toPlace.put(entry.getKey(), entry.getValue());
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

        BuildPlanner planner = new BuildPlanner(config.strategy);
        plan = planner.plan(toPlace, worldSolid, player.getBlockPos());
        placementIndex = 0;
        placedCount = 0;
        skipped.clear();

        if (plan.order().isEmpty()) {
            state = State.DONE;
            statusMessage = "nothing to build -- world already matches the schematic";
            return;
        }

        state = State.RUNNING;
        step = Step.NAVIGATE;
        statusMessage = "building " + plan.order().size() + " blocks (" + plan.scaffoldBlocksUsed() + " scaffold)";
    }

    private void doRun(MinecraftClient client, ClientPlayerEntity player) {
        if (placementIndex >= plan.order().size()) {
            resetInputs();
            state = State.DONE;
            statusMessage = "build complete: " + placedCount + " placed, " + skipped.size() + " skipped";
            player.sendMessage(Text.translatable("autobuilder.chat.build_done", placedCount), false);
            return;
        }

        BlockPlacement bp = plan.order().get(placementIndex);

        switch (step) {
            case NAVIGATE -> handleNavigate(client, player, bp);
            case PEARL_THROW -> handlePearlThrow(client, player);
            case PEARL_WAIT -> handlePearlWait(client, player);
            case ALIGN -> handleAlign(client, player, bp);
            case ENSURE_ITEM -> handleEnsureItem(client, player, bp);
            case PLACE -> handlePlace(client, player, bp);
            case DWELL -> handleDwell();
            case SKIP -> advance(false);
        }
    }

    // -- NAVIGATE ------------------------------------------------------

    private void handleNavigate(MinecraftClient client, ClientPlayerEntity player, BlockPlacement bp) {
        if (path.isEmpty() && pathIndex == 0) {
            BlockPos standGoal = computeStandPosition(bp);
            PathFinder finder = new PathFinder(
                    pos -> isStandable(client, pos), config.usePearlClimbing, 32);
            path = finder.findPath(player.getBlockPos(), standGoal, 4000);
            if (path.isEmpty()) {
                // Can't route there at all -- give up on this block rather than stall forever.
                skipped.add(bp);
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

        PathFinder.PathStep current = path.get(pathIndex);
        if (current.type() == PathFinder.StepType.PEARL_CLIMB) {
            pearlLandingTarget = current.pos();
            step = Step.PEARL_THROW;
            return;
        }

        stepToward(client, player, current.pos());
        if (current.type() == PathFinder.StepType.JUMP && player.isOnGround()) {
            client.options.jumpKey.setPressed(true);
        }
        if (player.getBlockPos().isWithinDistance(current.pos(), 0.5)) {
            client.options.jumpKey.setPressed(false);
            pathIndex++;
        }
    }

    private BlockPos computeStandPosition(BlockPlacement bp) {
        Direction away = bp.clickFace() != null ? bp.clickFace() : Direction.UP;
        return bp.supportNeighbor() != null
                ? bp.supportNeighbor().offset(away, 2)
                : bp.pos().down();
    }

    private boolean isStandable(MinecraftClient client, BlockPos feet) {
        var world = client.world;
        return !world.getBlockState(feet).isSolidBlock(world, feet)
                && !world.getBlockState(feet.up()).isSolidBlock(world, feet.up())
                && world.getBlockState(feet.down()).isSolidBlock(world, feet.down());
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
            skipped.add(plan.order().get(placementIndex));
            step = Step.NAVIGATE;
            path = List.of();
            pathIndex++;
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
            // Planner couldn't find support for this one (only happens on non-scaffold
            // strategies for floating sections) -- nothing sensible to click, skip it.
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
        Item needed = bp.state().getBlock().asItem();
        int hotbarSlot = findHotbarOrInventorySlot(player, needed);
        if (hotbarSlot < 0) {
            statusMessage = "missing " + needed.getName().getString() + " for this block";
            player.sendMessage(Text.translatable("autobuilder.chat.materials_needed", 1, needed.getName()), false);
            if (config.autoBuyMaterials && !auctionBuyer.active()) {
                auctionBuyer.startShopping(client, needed.getName().getString());
            }
            if (auctionBuyer.active()) {
                auctionBuyer.tick(client);
                return; // keep waiting on the shop each tick until it resolves
            }
            skipped.add(bp);
            advance(false);
            return;
        }
        selectHotbarSlot(player, hotbarSlot);
        step = Step.PLACE;
    }

    private void handlePlace(MinecraftClient client, ClientPlayerEntity player, BlockPlacement bp) {
        Direction face = bp.clickFace();
        Vec3d hitPos = Vec3d.ofCenter(bp.supportNeighbor())
                .add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitPos, face, bp.supportNeighbor(), false);
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        placedCount++;
        waitTicks = motion.dwellTicks();
        step = Step.DWELL;
    }

    private void handleDwell() {
        if (waitTicks-- <= 0) advance(true);
    }

    private void advance(boolean placedOk) {
        placementIndex++;
        path = List.of();
        pathIndex = 0;
        step = Step.NAVIGATE;
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
        look = motion.stepLook(look, targetYaw, Math.max(-20f, look.pitch()));
        player.setYaw(look.yaw());
        player.setPitch(look.pitch());
        client.options.forwardKey.setPressed(true);
    }

    private void resetInputs() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
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
