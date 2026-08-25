package dev.autobuilder.planner;

import dev.autobuilder.config.BuilderConfig;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.*;

/**
 * Turns "these positions need these block states" into an ordered list of
 * placements that's actually buildable: every block (except temporary
 * scaffold) always has at least one already-solid neighbor to click against
 * by the time its turn comes up, so the player never needs to place a block
 * floating in mid-air.
 */
public class BuildPlanner {

    public record BlockPlacement(
            BlockPos pos,
            BlockState state,
            BlockPos supportNeighbor,
            Direction clickFace,
            boolean temporaryScaffold
    ) {}

    public record Plan(List<BlockPlacement> order, Map<Item, Integer> materialsNeeded, int scaffoldBlocksUsed) {}

    private static final Direction[] SUPPORT_PRIORITY = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };

    private final BuilderConfig.BuildStrategy strategy;
    private final BuilderConfig.LayerDirection layerDirection;
    private final BlockState scaffoldState;
    private final Random random = new Random();

    public BuildPlanner(BuilderConfig config) {
        this.strategy = config.strategy;
        this.layerDirection = config.layerDirection;
        this.scaffoldState = config.scaffoldBlock.state();
    }

    /**
     * @param targetBlocks positions -> desired state, already filtered down to
     *                      only what's missing/wrong in the world (diffing the
     *                      schematic against reality is the caller's job).
     * @param worldSolid    predicate: is this position currently a solid,
     *                      already-placed block we can click against (ground,
     *                      existing terrain, previously-placed schematic
     *                      blocks outside this run, etc).
     * @param origin        player's starting position, used by NEAREST_FIRST.
     */
    public Plan plan(Map<BlockPos, BlockState> targetBlocks, Set<BlockPos> worldSolid, BlockPos origin) {
        Set<BlockPos> placed = new HashSet<>(worldSolid);
        Map<BlockPos, BlockState> remaining = new HashMap<>(targetBlocks);
        List<BlockPlacement> order = new ArrayList<>(targetBlocks.size());
        BlockPos cursor = origin;
        int scaffoldCount = 0;
        Item currentMaterial = null; // BY_MATERIAL: the material being worked through

        BoundingBox bbox = BoundingBox.of(targetBlocks.keySet());

        while (!remaining.isEmpty()) {
            List<BlockPos> candidates = new ArrayList<>();
            for (BlockPos p : remaining.keySet()) {
                if (findSupport(p, placed) != null) {
                    candidates.add(p);
                }
            }

            if (candidates.isEmpty()) {
                // Nothing left is reachable/supported -- either we're done with
                // real support and only floating sections remain, or this
                // strategy doesn't scaffold. Handle both.
                if (strategy == BuilderConfig.BuildStrategy.SCAFFOLD_AWARE) {
                    BlockPos lowest = remaining.keySet().stream()
                            .min(Comparator.comparingInt(BlockPos::getY))
                            .orElseThrow();
                    scaffoldCount += buildScaffoldColumn(lowest, placed, order);
                    continue;
                } else {
                    // Best-effort: place lowest-y remaining anyway. The executor
                    // will find no valid click target for it and should log +
                    // skip, surfacing it to the player as "needs manual support"
                    // rather than silently failing.
                    BlockPos fallback = remaining.keySet().stream()
                            .min(Comparator.comparingInt(BlockPos::getY))
                            .orElseThrow();
                    order.add(new BlockPlacement(fallback, remaining.get(fallback), null, null, false));
                    placed.add(fallback);
                    remaining.remove(fallback);
                    continue;
                }
            }

            // cursor is reassigned each iteration, so it cannot be captured by a
            // lambda directly -- snapshot it. Built per-iteration rather than once
            // up front, so "nearest" is measured from where the builder actually
            // is now rather than from the starting position.
            final BlockPos from = cursor;
            Comparator<BlockPos> byDistance = Comparator.comparingDouble(
                    p -> p.getSquaredDistance(from.getX(), from.getY(), from.getZ()));
            // TOP_DOWN flips the layer ordering. Support is still required either
            // way, so top-down picks the highest *supported* block rather than
            // literally starting at the roof of an unsupported schematic.
            Comparator<BlockPos> byLayer = layerDirection == BuilderConfig.LayerDirection.TOP_DOWN
                    ? Comparator.comparingInt(BlockPos::getY).reversed()
                    : Comparator.comparingInt(BlockPos::getY);

            // BY_MATERIAL sticks with one block type until no supported candidate
            // uses it, which keeps the hotbar stable and mirrors how a person
            // builds from a stack of one thing.
            if (strategy == BuilderConfig.BuildStrategy.BY_MATERIAL) {
                final Item material = currentMaterial;
                boolean stillAvailable = material != null && candidates.stream()
                        .anyMatch(p -> itemOf(remaining.get(p)) == material);
                if (!stillAvailable) {
                    currentMaterial = candidates.stream()
                            .map(p -> itemOf(remaining.get(p)))
                            .filter(Objects::nonNull)
                            .findFirst().orElse(null);
                }
            }

            final Item material = currentMaterial;
            BlockPos next = switch (strategy) {
                case NEAREST_FIRST -> candidates.stream()
                        .min(byDistance)
                        .orElseThrow();
                case OUTSIDE_IN -> candidates.stream()
                        .min(Comparator.<BlockPos>comparingInt(p -> bbox.isShell(p) ? 0 : 1)
                                .thenComparing(byLayer)
                                .thenComparing(byDistance))
                        .orElseThrow();
                case BOTTOM_UP_LAYERS, SCAFFOLD_AWARE -> candidates.stream()
                        .min(byLayer.thenComparing(byDistance))
                        .orElseThrow();
                case BY_MATERIAL -> candidates.stream()
                        .min(Comparator.<BlockPos>comparingInt(
                                        p -> itemOf(remaining.get(p)) == material ? 0 : 1)
                                .thenComparing(byLayer)
                                .thenComparing(byDistance))
                        .orElseThrow();
                case RANDOM -> candidates.get(random.nextInt(candidates.size()));
            };

            Direction support = findSupport(next, placed);
            BlockPos neighbor = next.offset(support);
            order.add(new BlockPlacement(next, remaining.get(next), neighbor, support.getOpposite(), false));
            placed.add(next);
            remaining.remove(next);
            cursor = next;
        }

        return new Plan(order, tallyMaterials(order), scaffoldCount);
    }

    /** Returns the direction FROM pos TO an already-placed neighbor, or null if unsupported. */
    private Direction findSupport(BlockPos pos, Set<BlockPos> placed) {
        for (Direction d : SUPPORT_PRIORITY) {
            if (placed.contains(pos.offset(d))) return d;
        }
        return null;
    }

    /** Drops a temporary scaffold column straight down from an unsupported block until it hits solid ground. */
    private int buildScaffoldColumn(BlockPos unsupported, Set<BlockPos> placed, List<BlockPlacement> order) {
        int count = 0;
        BlockPos p = unsupported.down();
        List<BlockPos> column = new ArrayList<>();
        while (!placed.contains(p) && p.getY() > unsupported.getY() - 64) {
            column.add(p);
            p = p.down();
        }
        if (!placed.contains(p)) {
            // Never found ground within range; bail rather than building forever downward.
            return 0;
        }
        Collections.reverse(column); // build from the ground up
        for (BlockPos col : column) {
            Direction support = findSupport(col, placed);
            BlockPos neighbor = support != null ? col.offset(support) : col.down();
            order.add(new BlockPlacement(col, scaffoldState, neighbor, support != null ? support.getOpposite() : Direction.UP, true));
            placed.add(col);
            count++;
        }
        return count;
    }

    /** The item a player would hold to place this state, or null if it has none (air, fluids). */
    private static Item itemOf(BlockState state) {
        return state == null ? null : state.getBlock().asItem();
    }

    public static Map<Item, Integer> tallyMaterials(List<BlockPlacement> order) {
        Map<Item, Integer> tally = new LinkedHashMap<>();
        for (BlockPlacement bp : order) {
            if (bp.temporaryScaffold()) continue; // scaffold materials tracked separately by the executor
            Item item = bp.state().getBlock().asItem();
            if (item == null) continue; // e.g. air/fluid states shouldn't reach here
            tally.merge(item, 1, Integer::sum);
        }
        return tally;
    }

    private record BoundingBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static BoundingBox of(Collection<BlockPos> positions) {
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
