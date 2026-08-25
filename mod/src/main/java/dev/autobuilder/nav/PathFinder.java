package dev.autobuilder.nav;

import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Grid A* over block positions, decoupled from the live Minecraft world via
 * the WalkableCheck callback so the search itself stays testable. Two edge
 * types beyond plain walking/stepping:
 *  - JUMP: a 1-block rise the player can just jump onto.
 *  - PEARL_CLIMB: used only when the walkable graph has no ordinary route up
 *    within reach (a sheer wall, a tower) -- an edge straight up to the
 *    nearest standable spot, representing "throw a pearl, land there".
 * Pearl edges are deliberately expensive relative to walking so the search
 * only reaches for one when there's genuinely no ground path, not as a
 * shortcut past normal stairs/ramps.
 */
public class PathFinder {

    public interface WalkableCheck {
        /** True if the player could stand with their feet at this position (solid block below, 2 air/passable above). */
        boolean isStandable(BlockPos feetPos);
    }

    public enum StepType { WALK, JUMP, DROP, PEARL_CLIMB }

    public record PathStep(BlockPos pos, StepType type) {}

    private static final int[][] HORIZONTAL = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private final WalkableCheck walkable;
    private final boolean allowPearlClimb;
    private final int maxPearlRise;
    private final boolean allowJump;
    private final int maxFallDistance;

    public PathFinder(WalkableCheck walkable, boolean allowPearlClimb, int maxPearlRise,
                      boolean allowJump, int maxFallDistance) {
        this.walkable = walkable;
        this.allowPearlClimb = allowPearlClimb;
        this.maxPearlRise = maxPearlRise;
        this.allowJump = allowJump;
        this.maxFallDistance = Math.max(0, maxFallDistance);
    }

    public List<PathStep> findPath(BlockPos start, BlockPos goal, int maxExpandedNodes) {
        record Node(BlockPos pos, double g, double f) {}

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        Map<BlockPos, Double> bestG = new HashMap<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, StepType> cameVia = new HashMap<>();

        open.add(new Node(start, 0, heuristic(start, goal)));
        bestG.put(start, 0.0);

        int expanded = 0;
        while (!open.isEmpty() && expanded++ < maxExpandedNodes) {
            Node current = open.poll();
            if (current.pos().equals(goal)) {
                return reconstruct(cameFrom, cameVia, start, goal);
            }
            if (current.g() > bestG.getOrDefault(current.pos(), Double.MAX_VALUE)) continue;

            for (Edge edge : neighbors(current.pos())) {
                double tentativeG = current.g() + edge.cost();
                if (tentativeG < bestG.getOrDefault(edge.to(), Double.MAX_VALUE)) {
                    bestG.put(edge.to(), tentativeG);
                    cameFrom.put(edge.to(), current.pos());
                    cameVia.put(edge.to(), edge.type());
                    open.add(new Node(edge.to(), tentativeG, tentativeG + heuristic(edge.to(), goal)));
                }
            }
        }
        return List.of(); // no path found within budget -- caller should surface "can't reach this block"
    }

    private record Edge(BlockPos to, StepType type, double cost) {}

    private List<Edge> neighbors(BlockPos from) {
        List<Edge> edges = new ArrayList<>();

        for (int[] d : HORIZONTAL) {
            BlockPos flat = from.add(d[0], 0, d[1]);
            double dist = (d[0] != 0 && d[1] != 0) ? 1.41421356 : 1.0;
            if (walkable.isStandable(flat)) {
                edges.add(new Edge(flat, StepType.WALK, dist));
            } else if (allowJump && walkable.isStandable(flat.up())) {
                edges.add(new Edge(flat.up(), StepType.JUMP, dist * 1.2));
            } else {
                // Look for a landing within the allowed fall height rather than
                // only one block down -- and never propose a drop taller than the
                // configured limit, which is what stops it walking off cliffs.
                for (int drop = 1; drop <= maxFallDistance; drop++) {
                    BlockPos landing = flat.down(drop);
                    if (walkable.isStandable(landing)) {
                        // Taller drops cost more, so a gentle route wins when both exist.
                        edges.add(new Edge(landing, StepType.DROP, dist * (1.1 + drop * 0.25)));
                        break;
                    }
                }
            }
        }

        if (allowPearlClimb) {
            for (int rise = 3; rise <= maxPearlRise; rise++) {
                BlockPos target = from.up(rise);
                if (walkable.isStandable(target)) {
                    // Cost dominated by a flat "wind-up" cost so the search only
                    // takes this when walking genuinely can't reach, plus a small
                    // per-block term so it still prefers the shortest usable pearl.
                    edges.add(new Edge(target, StepType.PEARL_CLIMB, 6.0 + rise * 0.08));
                    break; // nearest reachable landing above is enough as a candidate edge
                }
            }
        }

        return edges;
    }

    /**
     * Must never overestimate the true remaining cost, or A* stops being
     * reliable at finding a path at all -- not just stops being optimal.
     * Edge costs above are ~1 per block moved, so squared distance (the
     * previous version of this) overestimates quadratically once the goal is
     * more than a couple of blocks away, which starves the search of budget
     * on anything but a straight unobstructed line to the goal. Plain
     * (non-squared) distance keeps it admissible.
     */
    private double heuristic(BlockPos a, BlockPos b) {
        return Math.sqrt(a.getSquaredDistance(b.getX(), b.getY(), b.getZ()));
    }

    private List<PathStep> reconstruct(Map<BlockPos, BlockPos> cameFrom, Map<BlockPos, StepType> cameVia,
                                        BlockPos start, BlockPos goal) {
        LinkedList<PathStep> path = new LinkedList<>();
        BlockPos cur = goal;
        while (!cur.equals(start)) {
            path.addFirst(new PathStep(cur, cameVia.get(cur)));
            cur = cameFrom.get(cur);
        }
        return path;
    }
}
