package dev.autobuilder.config;

/**
 * All user-tunable behavior lives here so the GUI (opened with the ' key)
 * has one object to read/write. Not persisted to disk yet -- wire up
 * malilib's JSON config helpers (or your own Gson file) in loadFromDisk()/
 * saveToDisk() once you're happy with the defaults.
 */
public class BuilderConfig {

    public enum BuildStrategy {
        BOTTOM_UP_LAYERS("Bottom-up, layer by layer"),
        NEAREST_FIRST("Nearest block first (least walking)"),
        OUTSIDE_IN("Outer walls first, then interior"),
        SCAFFOLD_AWARE("Bottom-up + auto-scaffold under floating sections");

        public final String label;
        BuildStrategy(String label) { this.label = label; }
    }

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

    public BuildStrategy strategy = BuildStrategy.BOTTOM_UP_LAYERS;
    public Pace pace = Pace.NORMAL;

    /** Use ender pearls to bridge vertical gaps the pathfinder can't walk/jump. */
    public boolean usePearlClimbing = true;
    /** Minimum pearls to keep in reserve; won't throw the last N for climbing. */
    public int pearlReserve = 4;

    /**
     * When true, and inventory is short on a needed block, the executor will
     * open the server's auction house and buy the cheapest available stack.
     * OFF by default: this automates a purchase against a live server economy,
     * which most servers' rules treat the same as any other bot/macro use --
     * read your server's rules before turning this on, and only use it
     * somewhere you're allowed to automate play (singleplayer, a server you
     * administer, or one whose rules explicitly permit it).
     */
    public boolean autoBuyMaterials = false;
    /** Command template sent to shop; %s is replaced with the item search term. */
    public String auctionCommandTemplate = "/ah %s";
    /** Max price-per-item willing to pay before skipping a purchase and just reporting the shortfall. */
    public double maxUnitPrice = Double.MAX_VALUE;
    /**
     * Regex applied to each auction slot's display name + lore lines, first
     * capture group = the numeric price. Tune this to match your server's
     * actual GUI wording -- e.g. "\\$([0-9,.]+)" or "Price: ([0-9,.]+) coins".
     */
    public String auctionPriceRegex = "\\$\\s*([0-9][0-9,.]*)";
    /** Some servers need a second click (a confirm dialog / double-click-to-buy) after the first. */
    public boolean auctionRequiresConfirmClick = false;
    public int auctionConfirmDelayTicks = 4;
}
