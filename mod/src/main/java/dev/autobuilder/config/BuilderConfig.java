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

    /**
     * SCAFFOLD_AWARE by default: still strictly layer by layer (see isLayered()
     * in BuildPlanner), but any block with no walkable support gets a temporary
     * column dropped under it first so the builder climbs on real placed blocks
     * instead of needing a pearl throw to reach it. BOTTOM_UP_LAYERS with no
     * scaffolding leans on pearl climbing for every upper layer, and pearl
     * landings are only an approximate physics simulation -- an overshoot lands
     * the builder outside the structure with no walkable route back nearby,
     * which is what reads as "running off" and "not building" upper layers.
     */
    public BuildStrategy strategy = BuildStrategy.SCAFFOLD_AWARE;
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
    /**
     * Finish a whole Y layer before starting the next. Without this, support
     * rules let the builder climb early wherever a neighbour happens to exist,
     * and the build stops looking like it's going up a layer at a time.
     */
    public boolean strictLayers = true;
    /**
     * Treat a placement as successful only if the block that appeared is the one
     * the schematic asked for, rather than merely "something is there now".
     */
    public boolean strictBlockMatch = true;
    /** Break blocks standing inside the schematic's bounds that it doesn't want. */
    public boolean removeExtraBlocks = false;
    /**
     * Re-check finished layers every so often and repair anything that has
     * changed -- water spreading, mobs, another player, a failed placement.
     */
    public boolean continuousVerify = false;
    /** Seconds between those re-checks. */
    public int verifyIntervalSeconds = 120;
    /** Pick the fastest tool in the inventory before breaking a block. */
    public boolean autoSelectTool = true;

    // ================================================================ movement

    /**
     * Use ender pearls to bridge vertical gaps the pathfinder can't walk or
     * jump. OFF by default: the landing spot is only an approximate physics
     * simulation, and a bad throw puts the builder somewhere unpredictable,
     * possibly well outside the structure. With the default SCAFFOLD_AWARE
     * strategy, scaffolding covers the same gaps by climbing on real blocks
     * instead, so this is only worth turning on for a build with genuinely
     * unreachable floating sections and no scaffold path to them.
     */
    public boolean usePearlClimbing = false;
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
    /**
     * A* search budget. Higher copes with mazier terrain, at some CPU cost.
     * The fenced area around a build (see BUILD_AREA_MARGIN in BuildExecutor)
     * can be a few thousand blocks across for anything of real size, so the
     * old default of 4000 could run out before finding a route through
     * anything but simple terrain -- a search that quits early looks
     * identical to "there's no way there" even when one exists.
     */
    public int maxPathNodes = 8000;
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

    public Pace pace = Pace.BRISK;
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
     * Buy missing blocks from the server's auction house as the build actually
     * needs them -- no need to carry every material up front. ON by default,
     * since a build with this off just skips every block it isn't carrying;
     * the price caps below (autoBuyLimit/hardMaxPrice) are what keep spending
     * in check, not this toggle. Turn it off on the Buying tab if you'd rather
     * stock the inventory yourself, or if your server's rules prohibit
     * automated buying.
     */
    public boolean autoBuyMaterials = true;
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

    // ================================================================ server mode

    public enum ServerPreset {
        NORMAL("Normal (Survival)"),
        CREATIVE("Creative Mode"),
        DONUT_SMP("DonutSMP");

        public final String label;
        ServerPreset(String label) { this.label = label; }
    }

    /**
     * Server / game mode you're playing on.
     * CREATIVE: fills the hotbar automatically rather than buying from /ah.
     * DONUT_SMP: tunes timing, disables features that flag Donut's anti-cheat.
     */
    public ServerPreset serverPreset = ServerPreset.NORMAL;

    /** Apply the recommended defaults for the chosen preset (called from the GUI). */
    public void applyPreset(ServerPreset preset) {
        this.serverPreset = preset;
        switch (preset) {
            case CREATIVE -> {
                autoBuyMaterials = false;
            }
            case DONUT_SMP -> {
                allowSprint = false;
                usePearlClimbing = false;
                pace = Pace.CAREFUL;
                takeBreaks = true;
                simulateFatigue = true;
                avoidHazards = true;
                jitterPercent = 120;
                stopOnPlayerNearby = true;
            }
            default -> {}
        }
    }

    // ================================================================ persistence

    /** Remember these settings between launches. */
    public boolean saveSettings = true;
}
