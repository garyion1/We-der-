package dev.autobuilder.config;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/**
 * Everything the builder can be told to do, grouped by the GUI tab it lives on
 * (open with [). Not persisted to disk yet -- settings reset each launch.
 */
public class BuilderConfig {

    // ================================================================ build

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
    /** Skip water/lava in the schematic -- placing those needs buckets and rarely helps. */
    public boolean skipFluids = true;
    /** Skip chests, signs, etc. Their contents/text aren't in scope, so they'd be placed empty. */
    public boolean skipBlockEntities = false;
    /** After the last block, re-scan the schematic and place anything still missing. */
    public boolean verifyPass = true;
    /** How many times to re-attempt a placement that didn't take before giving up. */
    public int maxRetriesPerBlock = 2;

    // ================================================================ movement

    /** Use ender pearls to bridge vertical gaps the pathfinder can't walk or jump. */
    public boolean usePearlClimbing = true;
    /** Won't throw pearls for climbing if it would drop the stack below this. */
    public int pearlReserve = 4;
    /** How close the builder gets to a block before placing it. */
    public double maxReach = 4.0;
    public boolean allowSprint = false;
    public boolean allowJump = true;
    /** Sneak when standing next to a drop, so a mistimed step doesn't fall. */
    public boolean sneakNearEdges = true;
    /** Refuse to path through or stand next to lava/fire. */
    public boolean avoidHazards = true;
    /** Won't path down drops taller than this. */
    public int maxFallDistance = 3;
    /** A* search budget. Higher copes with mazier terrain, at some CPU cost. */
    public int maxPathNodes = 4000;
    /** Walk back to where the build started once it's finished. */
    public boolean returnHomeWhenDone = false;

    // ================================================================ timing

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
    /** Pause periodically, the way someone building for an hour actually would. */
    public boolean takeBreaks = true;
    public int breakEveryBlocks = 128;
    public int breakSeconds = 15;
    /** Glance around while paused rather than standing perfectly still. */
    public boolean lookAroundOnBreak = true;
    /** Slow down and pause more as the session wears on. */
    public boolean simulateFatigue = true;
    /** Scales all randomness in timing and aim. 100 = normal. */
    public int jitterPercent = 100;
    /** Global speed dial on top of pace. 100 = normal, 200 = twice the delays. */
    public int speedPercent = 100;

    // ================================================================ materials

    public enum OutOfMaterialsPolicy {
        SKIP_BLOCK("Skip the block"),
        PAUSE_BUILD("Pause the build");

        public final String label;
        OutOfMaterialsPolicy(String label) { this.label = label; }
    }

    /**
     * Buy missing blocks from the server's auction house. OFF by default: this
     * spends real in-game currency with nobody at the controls, and most servers'
     * rules treat automated buying the same as any other bot use.
     */
    public boolean autoBuyMaterials = false;
    /** Command that opens the auction search; %s becomes the item name. */
    public String auctionCommandTemplate = "/ah %s";
    /**
     * Regex over each listing's name + lore; group 1 must be the price.
     * Tune to your server, e.g. "\\$([0-9,.]+)" or "Price: ([0-9,.]+) coins".
     */
    public String auctionPriceRegex = "\\$\\s*([0-9][0-9,.]*)";
    /**
     * Buys at or under this per item without asking. Above it, the build pauses
     * and waits for you to confirm in the GUI rather than spending quietly.
     */
    public double autoBuyLimit = 100_000.0;
    /** Never bought at any price, confirmed or not. */
    public double hardMaxPrice = 5_000_000.0;
    /** If false, anything over autoBuyLimit is skipped instead of prompting. */
    public boolean confirmExpensivePurchases = true;
    /** Some auction GUIs need a second click to confirm a purchase. */
    public boolean auctionRequiresConfirmClick = false;
    public int auctionConfirmDelayTicks = 4;
    /** Buy this much above the shortfall, so near-misses don't restart shopping. */
    public int buyExtraPercent = 10;
    public OutOfMaterialsPolicy outOfMaterials = OutOfMaterialsPolicy.SKIP_BLOCK;
    /**
     * Page through the whole auction listing before buying, so the cheapest
     * listing overall wins rather than the cheapest on page one.
     */
    public boolean scanAllPages = true;
    public int maxAuctionPages = 5;

    // ================================================================ safety

    public boolean stopOnLowHealth = true;
    /** Half-hearts; 10 = five hearts. */
    public int lowHealthThreshold = 10;
    public boolean stopOnLowHunger = false;
    public int lowHungerThreshold = 6;
    /** Stop if another player comes within stopOnPlayerRadius blocks. */
    public boolean stopOnPlayerNearby = false;
    public int stopOnPlayerRadius = 32;
    /** 0 = no limit. Otherwise stop after this many minutes. */
    public int maxBuildMinutes = 0;
    public boolean stopWhenInventoryFull = false;
    /** Stop rather than carry on if this many placements in a row fail. */
    public int stopAfterConsecutiveFailures = 25;
}
