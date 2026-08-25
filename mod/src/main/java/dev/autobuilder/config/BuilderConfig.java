package dev.autobuilder.config;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/**
 * All user-tunable behavior, grouped by the GUI tab it appears on (open with [).
 * Not persisted to disk yet -- settings reset each launch.
 */
public class BuilderConfig {

    // ---------------------------------------------------------------- build order

    public enum BuildStrategy {
        BOTTOM_UP_LAYERS("Layer by layer"),
        NEAREST_FIRST("Nearest block first"),
        OUTSIDE_IN("Shell first, then interior"),
        SCAFFOLD_AWARE("Layers + auto-scaffold"),
        BY_MATERIAL("One material at a time"),
        RANDOM("Random order");

        public final String label;
        BuildStrategy(String label) { this.label = label; }
    }

    public enum LayerDirection {
        BOTTOM_UP("Bottom to top"),
        TOP_DOWN("Top to bottom");

        public final String label;
        LayerDirection(String label) { this.label = label; }
    }

    public enum ScaffoldBlock {
        DIRT("Dirt", Blocks.DIRT),
        COBBLESTONE("Cobblestone", Blocks.COBBLESTONE),
        NETHERRACK("Netherrack", Blocks.NETHERRACK),
        STONE("Stone", Blocks.STONE);

        public final String label;
        private final net.minecraft.block.Block block;
        ScaffoldBlock(String label, net.minecraft.block.Block block) {
            this.label = label;
            this.block = block;
        }
        public BlockState state() { return block.getDefaultState(); }
    }

    public BuildStrategy strategy = BuildStrategy.BOTTOM_UP_LAYERS;
    public LayerDirection layerDirection = LayerDirection.BOTTOM_UP;
    public ScaffoldBlock scaffoldBlock = ScaffoldBlock.DIRT;
    /** Break blocks that already occupy a target position but are the wrong type. */
    public boolean breakWrongBlocks = false;

    // ---------------------------------------------------------------- movement

    /** Use ender pearls to bridge vertical gaps the pathfinder can't walk/jump. */
    public boolean usePearlClimbing = true;
    /** Won't throw pearls for climbing if it would drop the stack below this. */
    public int pearlReserve = 4;
    /** How close the builder gets to a block before placing it. */
    public double maxReach = 4.0;
    public boolean allowSprint = false;
    /** A* search budget. Higher copes with mazier terrain, at some CPU cost. */
    public int maxPathNodes = 4000;

    // ---------------------------------------------------------------- timing

    public enum Pace {
        CAREFUL(1.6, 0.35, 900, 1600),
        NORMAL(1.0, 0.55, 450, 950),
        BRISK(0.6, 0.8, 200, 500);

        /** Multiplier on every timing below -- CAREFUL waits longest between actions. */
        public final double actionDelayScale;
        /** Fraction of max look/move speed used, 0..1 (never snaps instantly to target). */
        public final double motionSpeedFraction;
        public final int minDwellMs;
        public final int maxDwellMs;

        Pace(double actionDelayScale, double motionSpeedFraction, int minDwellMs, int maxDwellMs) {
            this.actionDelayScale = actionDelayScale;
            this.motionSpeedFraction = motionSpeedFraction;
            this.minDwellMs = minDwellMs;
            this.maxDwellMs = maxDwellMs;
        }
    }

    public Pace pace = Pace.NORMAL;
    /** Pause periodically, the way a person building for an hour actually would. */
    public boolean takeBreaks = true;
    public int breakEveryBlocks = 128;
    public int breakSeconds = 15;
    /** Glance around while paused rather than standing perfectly still. */
    public boolean lookAroundOnBreak = true;

    // ---------------------------------------------------------------- materials

    public enum OutOfMaterialsPolicy {
        SKIP_BLOCK("Skip the block"),
        PAUSE_BUILD("Pause the build");

        public final String label;
        OutOfMaterialsPolicy(String label) { this.label = label; }
    }

    /**
     * When inventory runs short, buy the missing block from the server's auction
     * house. OFF by default: this spends real in-game currency automatically, and
     * most servers' rules treat automated buying the same as any other bot use --
     * check yours before enabling.
     */
    public boolean autoBuyMaterials = false;
    /** Command sent to open the auction search; %s becomes the item name. */
    public String auctionCommandTemplate = "/ah %s";
    /**
     * Regex over each auction slot's name + lore; group 1 must be the price.
     * Tune to your server's wording, e.g. "\\$([0-9,.]+)" or "Price: ([0-9,.]+)".
     */
    public String auctionPriceRegex = "\\$\\s*([0-9][0-9,.]*)";
    /**
     * Hard ceiling on price PER ITEM (a 64-stack for 320k is 5k each, so it
     * passes). Nothing dearer is ever bought -- listings above this are skipped
     * outright rather than bought as a last resort.
     */
    public double maxUnitPrice = 100_000.0;
    /** Some auction GUIs need a second click to confirm a purchase. */
    public boolean auctionRequiresConfirmClick = false;
    public int auctionConfirmDelayTicks = 4;
    /** Buy this much above the shortfall, so near-misses don't restart shopping. */
    public int buyExtraPercent = 10;
    public OutOfMaterialsPolicy outOfMaterials = OutOfMaterialsPolicy.SKIP_BLOCK;

    // ---------------------------------------------------------------- safety

    public boolean stopOnLowHealth = true;
    /** Half-hearts; 10 = five hearts. */
    public int lowHealthThreshold = 10;
    /** Stop if another player comes within stopOnPlayerRadius blocks. */
    public boolean stopOnPlayerNearby = false;
    public int stopOnPlayerRadius = 32;
    /** 0 = no limit. Otherwise stop after this many minutes. */
    public int maxBuildMinutes = 0;
}
