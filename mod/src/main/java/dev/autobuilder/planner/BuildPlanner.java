package dev.autobuilder.planner;

import dev.autobuilder.config.BuilderConfig;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.*;

/**
 * Turns "these positions need these block states" into an ordered list of
 * actions that is actually buildable: every block always has an already-solid
 * neighbour to click against by the time its turn comes up, so the builder never
 * needs to place a block floating in mid-air.
 *
 * Performance matters here. The obvious implementation rescans every remaining
 * block each iteration to find which are supported, which is O(n^2) and locks
 * the client solid on a schematic of any real size. Instead a "frontier" of
 * currently-placeable positions is maintained incrementally: placing a block can
 * only ever newly support its six neighbours, so only those are re-examined.
 */
public class BuildPlanner {

    /** A single action. state == null means "break whatever is here". */
    public record BlockPlacement(
            BlockPos pos,
            BlockState state,
            BlockPos supportNeighbor,
            Direction clickFace,
            boolean temporaryScaffold,
            boolean removal
    ) {
        public boolean isRemoval() { return removal; }
    }

    public record Plan(
            List<BlockPlacement> order,
            Map<Item, Integer> materialsNeeded,
            int scaffoldBlocksUsed,
            int removals,
            int minLayer,
            int maxLayer
    ) {}

    private static final Direction[] SUPPORT_PRIORITY = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };

    private final BuilderConfig config;
    private final BuildStrategyRef strategy;
    private final Random random = new Random();

    /** Small indirection so the switch below reads cleanly. */
    private record BuildStrategyRef(BuilderConfig.BuildStrategy value) {}

    public BuildPlanner(BuilderConfig config) {
        this.config = config;
        this.strategy = new BuildStrategyRef(config.strategy);
    }

    /**
     * @param targetBlocks positions -> desired state, already filtered to what's
     *                     missing or wrong (diffing is the caller's job).
     * @param toRemove     positions holding a block the schematic doesn't want.
     * @param worldSolid   positions already solid that can be clicked against.
     * @param origin       where the builder is standing now.
     */
    public Plan plan(Map<BlockPos, BlockState> targetBlocks,
                     Set<BlockPos> toRemove,
                     Set<BlockPos> worldSolid,
                     BlockPos origin) {

        List<BlockPlacement> order = new ArrayList<>(targetBlocks.size() + toRemove.size());

        // Removals go first: clearing obstructions before building avoids
        // placing a block that then has to be broken to reach the next one.
        for (BlockPos pos : sortedForRemoval(toRemove)) {
            order.add(new BlockPlacement(pos, null, pos, Direction.UP, false, true));
        }

        Set<BlockPos> placed = new HashSet<>(worldSolid);
        Map<BlockPos, BlockState> remaining = new HashMap<>(targetBlocks);
        int scaffoldCount = 0;

        int minLayer = Integer.MAX_VALUE, maxLayer = Integer.MIN_VALUE;
        for (BlockPos p : remaining.keySet()) {
            minLayer = Math.min(minLayer, p.getY());
            maxLayer = Math.max(maxLayer, p.getY());
        }
        if (remaining.isEmpty()) {
            minLayer = maxLayer = 0;
        }

        // The frontier is every remaining position that currently has support.
        // Maintained incrementally rather than rebuilt each pass.
        Set<BlockPos> frontier = new HashSet<>();
        for (Map.Entry<BlockPos, BlockState> e : remaining.entrySet()) {
            if (findSupport(e.getKey(), placed) != null) frontier.add(e.getKey());
        }

        BoundingBox bbox = BoundingBox.of(targetBlocks.keySet());
        BlockPos cursor = origin;
        Item currentMaterial = null;

        boolean topDown = config.layerDirection == BuilderConfig.LayerDirection.TOP_DOWN;
        int layer = topDown ? maxLayer : minLayer;

        while (!remaining.isEmpty()) {
            // Strict layers: only consider the layer being worked on, and don't
            // move up until nothing in it can be placed. Without this, support
            // rules let the builder wander upward early and the build stops
            // looking like it's being built a layer at a time.
            Collection<BlockPos> candidates = frontier;
            if (config.strictLayers && isLayered()) {
                final int activeLayer = layer;
                List<BlockPos> inLayer = new ArrayList<>();
                for (BlockPos p : frontier) {
                    if (p.getY() == activeLayer) inLayer.add(p);
                }
                if (inLayer.isEmpty()) {
                    // Nothing placeable here. If blocks remain in this layer they
                    // are unreachable; deal with them, otherwise move on.
                    if (!advanceLayerOrResolve(remaining, placed, frontier, order, layer, topDown)) {
                        layer += topDown ? -1 : 1;
                        if (layer < minLayer - 1 || layer > maxLayer + 1) {
                            // Ran past the ends without placing anything: emit the
                            // rest unsupported rather than looping forever.
                            drainRemaining(remaining, placed, frontier, order);
                            break;
                        }
                        continue;
                    }
                    continue;
                }
                candidates = inLayer;
            }

            if (candidates.isEmpty()) {
                if (config.strategy == BuilderConfig.BuildStrategy.SCAFFOLD_AWARE) {
                    BlockPos lowest = lowestRemaining(remaining, topDown);
                    int added = buildScaffoldColumn(lowest, placed, order, remaining, frontier);
                    scaffoldCount += added;
                    if (added == 0) {
                        drainRemaining(remaining, placed, frontier, order);
                        break;
                    }
                    continue;
                }
                drainRemaining(remaining, placed, frontier, order);
                break;
            }

            BlockPos next = choose(candidates, remaining, bbox, cursor, currentMaterial, topDown);

            if (config.strategy == BuilderConfig.BuildStrategy.BY_MATERIAL) {
                Item here = itemOf(remaining.get(next));
                if (here != null) currentMaterial = here;
            }

            Direction support = findSupport(next, placed);
            BlockPos neighbor = support != null ? next.offset(support) : null;
            Direction face = support != null ? support.getOpposite() : null;
            order.add(new BlockPlacement(next, remaining.get(next), neighbor, face, false, false));

            commit(next, remaining, placed, frontier);
            cursor = next;
        }

        return new Plan(order, tallyMaterials(order), scaffoldCount, toRemove.size(),
                minLayer == Integer.MAX_VALUE ? 0 : minLayer,
                maxLayer == Integer.MIN_VALUE ? 0 : maxLayer);
    }

    private boolean isLayered() {
        return config.strategy == BuilderConfig.BuildStrategy.BOTTOM_UP_LAYERS
                || config.strategy == BuilderConfig.BuildStrategy.SCAFFOLD_AWARE
                || config.strategy == BuilderConfig.BuildStrategy.BY_MATERIAL;
    }

    /**
     * Marks a position built: removes it from the work set and re-examines only
     * its neighbours for newly-gained support. This is what keeps planning linear
     * rather than quadratic.
     */
    private void commit(BlockPos pos, Map<BlockPos, BlockState> remaining,
                        Set<BlockPos> placed, Set<BlockPos> frontier) {
        remaining.remove(pos);
        frontier.remove(pos);
        placed.add(pos);
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            if (remaining.containsKey(n) && !frontier.contains(n)) {
                frontier.add(n);   // it now has `pos` as support by definition
            }
        }
    }

    /**
     * Handles a layer with nothing placeable left. Returns true if it resolved
     * something (so the caller should loop again), false to move to the next layer.
     */
    private boolean advanceLayerOrResolve(Map<BlockPos, BlockState> remaining, Set<BlockPos> placed,
                                          Set<BlockPos> frontier, List<BlockPlacement> order,
                                          int layer, boolean topDown) {
        List<BlockPos> stranded = new ArrayList<>();
        for (BlockPos p : remaining.keySet()) {
            if (p.getY() == layer) stranded.add(p);
        }
        if (stranded.isEmpty()) return false;

        if (config.strategy == BuilderConfig.BuildStrategy.SCAFFOLD_AWARE) {
            BlockPos target = stranded.get(0);
            if (buildScaffoldColumn(target, placed, order, remaining, frontier) > 0) return true;
        }
        // Unsupported and unscaffoldable: emit them anyway so the executor can
        // report them as needing manual help, rather than silently dropping them.
        for (BlockPos p : stranded) {
            order.add(new BlockPlacement(p, remaining.get(p), null, null, false, false));
            commit(p, remaining, placed, frontier);
        }
        return true;
    }

    /** Emits everything still left, unsupported, so nothing is silently dropped. */
    private void drainRemaining(Map<BlockPos, BlockState> remaining, Set<BlockPos> placed,
                                Set<BlockPos> frontier, List<BlockPlacement> order) {
        List<BlockPos> left = new ArrayList<>(remaining.keySet());
        left.sort(Comparator.comparingInt(BlockPos::getY));
        for (BlockPos p : left) {
            order.add(new BlockPlacement(p, remaining.get(p), null, null, false, false));
        }
        remaining.clear();
        frontier.clear();
    }

    private BlockPos choose(Collection<BlockPos> candidates, Map<BlockPos, BlockState> remaining,
                            BoundingBox bbox, BlockPos cursor, Item material, boolean topDown) {
        final BlockPos from = cursor;
        Comparator<BlockPos> byDistance = Comparator.comparingDouble(
                p -> p.getSquaredDistance(from.getX(), from.getY(), from.getZ()));
        Comparator<BlockPos> byLayer = topDown
                ? Comparator.comparingInt(BlockPos::getY).reversed()
                : Comparator.comparingInt(BlockPos::getY);

        return switch (strategy.value()) {
            case NEAREST_FIRST -> candidates.stream().min(byDistance).orElseThrow();
            case OUTSIDE_IN -> candidates.stream()
                    .min(Comparator.<BlockPos>comparingInt(p -> bbox.isShell(p) ? 0 : 1)
                            .thenComparing(byLayer).thenComparing(byDistance))
                    .orElseThrow();
            case BY_MATERIAL -> candidates.stream()
                    .min(Comparator.<BlockPos>comparingInt(
                                    p -> itemOf(remaining.get(p)) == material ? 0 : 1)
                            .thenComparing(byLayer).thenComparing(byDistance))
                    .orElseThrow();
            case RANDOM -> {
                List<BlockPos> list = candidates instanceof List<BlockPos> l
                        ? l : new ArrayList<>(candidates);
                yield list.get(random.nextInt(list.size()));
            }
            case BOTTOM_UP_LAYERS, SCAFFOLD_AWARE -> candidates.stream()
                    .min(byLayer.thenComparing(byDistance)).orElseThrow();
        };
    }

    private BlockPos lowestRemaining(Map<BlockPos, BlockState> remaining, boolean topDown) {
        Comparator<BlockPos> byY = topDown
                ? Comparator.comparingInt(BlockPos::getY).reversed()
                : Comparator.comparingInt(BlockPos::getY);
        return remaining.keySet().stream().min(byY).orElseThrow();
    }

    /** Removals run top-down, so nothing is left floating as its support is taken away. */
    private List<BlockPos> sortedForRemoval(Set<BlockPos> toRemove) {
        List<BlockPos> list = new ArrayList<>(toRemove);
        list.sort(Comparator.comparingInt(BlockPos::getY).reversed());
        return list;
    }

    /** Returns the direction FROM pos TO an already-placed neighbour, or null. */
    private Direction findSupport(BlockPos pos, Set<BlockPos> placed) {
        for (Direction d : SUPPORT_PRIORITY) {
            if (placed.contains(pos.offset(d))) return d;
        }
        return null;
    }

    /** Drops a temporary column from an unsupported block down to solid ground. */
    private int buildScaffoldColumn(BlockPos unsupported, Set<BlockPos> placed,
                                    List<BlockPlacement> order,
                                    Map<BlockPos, BlockState> remaining, Set<BlockPos> frontier) {
        List<BlockPos> column = new ArrayList<>();
        BlockPos p = unsupported.down();
        while (!placed.contains(p) && p.getY() > unsupported.getY() - 64) {
            column.add(p);
            p = p.down();
        }
        if (!placed.contains(p)) return 0;   // no ground within range

        Collections.reverse(column);         // build from the ground up
        BlockState scaffold = config.scaffoldBlock.state();
        for (BlockPos col : column) {
            Direction support = findSupport(col, placed);
            BlockPos neighbor = support != null ? col.offset(support) : col.down();
            Direction face = support != null ? support.getOpposite() : Direction.UP;
            order.add(new BlockPlacement(col, scaffold, neighbor, face, true, false));
            placed.add(col);
            for (Direction d : Direction.values()) {
                BlockPos n = col.offset(d);
                if (remaining.containsKey(n)) frontier.add(n);
            }
        }
        return column.size();
    }

    /** The item a player would hold to place this state, or null if it has none. */
    private static Item itemOf(BlockState state) {
        return state == null ? null : state.getBlock().asItem();
    }

    public static Map<Item, Integer> tallyMaterials(List<BlockPlacement> order) {
        Map<Item, Integer> tally = new LinkedHashMap<>();
        for (BlockPlacement bp : order) {
            if (bp.isRemoval() || bp.temporaryScaffold()) continue;
            Item item = itemOf(bp.state());
            if (item == null) continue;
            tally.merge(item, 1, Integer::sum);
        }
        return tally;
    }

    private record BoundingBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static BoundingBox of(Collection<BlockPos> positions) {
            if (positions.isEmpty()) return new BoundingBox(0, 0, 0, 0, 0, 0);
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : positions) {
                minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
                minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
                minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
            }
            return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        }

        boolean isShell(BlockPos p) {
            return p.getX() == minX || p.getX() == maxX
                    || p.getY() == minY || p.getY() == maxY
                    || p.getZ() == minZ || p.getZ() == maxZ;
        }
    }
}
