package dev.autobuilder.gui;

import dev.autobuilder.AutoBuilderClient;
import dev.autobuilder.config.BuilderConfig;
import dev.autobuilder.exec.BuildExecutor;
import dev.autobuilder.schematic.LitematicFileSchematicSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The build options menu, opened with [.
 *
 * Deliberately not built from vanilla ButtonWidget: this is a flat, self-drawn
 * settings list (label left, value/switch right, hover highlight, no stone-gray
 * bevel) rather than a row of chiseled Minecraft buttons. Every row is data --
 * a label, a live value Supplier, and a click action -- collected in init(),
 * measured to fit whatever height is actually available, then both drawn and
 * hit-tested from that same list. Screen.mouseClicked is the only interaction
 * surface used beyond that (plus real TextFieldWidgets for the few free-text
 * settings), since it has been stable for a decade and needs no version-guessing.
 */
public class BuildOptionsScreen extends Screen {

    private static final String[] TABS = {"Build", "Move", "Timing", "Buying", "Safety", "Items", "Status"};

    private static final int PANEL_W = 400;
    private static final int ROW_H = 26;
    private static final int TAB_H = 24;
    private static final int MIN_ROW_H = 15;
    private static final int MIN_WIDGET_H = 14;
    private static final int BUTTON_H = 24;

    // Flat dark palette -- no vanilla stone-gray/gold, just a small set of
    // neutrals plus one accent color and three semantic status colors.
    private static final int BG_PANEL = 0xF0121620;
    private static final int ACCENT_LINE = 0xFF3E5A8C;
    private static final int BG_FIELD = 0xFF1B212C;
    private static final int HOVER_BG = 0x18FFFFFF;
    private static final int DIVIDER = 0x14FFFFFF;
    private static final int ACCENT = 0xFF6C9BFF;
    private static final int TEXT_PRIMARY = 0xFFE8EAED;
    private static final int TEXT_SECONDARY = 0xFF828A99;
    private static final int TEXT_DIM = 0xFF565C68;
    private static final int GOOD = 0xFF5FD68A;
    private static final int WARN = 0xFFE8B75B;
    private static final int BAD = 0xFFE8697A;
    private static final int TRACK_OFF = 0xFF383E4A;
    // Depth cues on top of the flat palette: a dimmed backdrop so the panel
    // reads as a surface floating over the game rather than a flat overlay,
    // a soft drop shadow and border for edge definition, and a couple of
    // shades for giving flat buttons/switches a slight tactile pop.
    private static final int SCREEN_DIM = 0x9B0A0C12;
    private static final int PANEL_BORDER = 0x33FFFFFF;
    private static final int SHADOW = 0x4C000000;
    private static final int BEVEL_LIGHT = 0x22FFFFFF;
    private static final int BEVEL_DARK = 0x2E000000;

    private void cutCorners(DrawContext context, int x1, int y1, int x2, int y2, int eraseColor) {
        context.fill(x1, y1, x1 + 1, y1 + 1, eraseColor);
        context.fill(x2 - 1, y1, x2, y1 + 1, eraseColor);
        context.fill(x1, y2 - 1, x1 + 1, y2, eraseColor);
        context.fill(x2 - 1, y2 - 1, x2, y2, eraseColor);
    }

    // Footer geometry, stacked bottom-up from the panel edge: the Start/Pause/
    // Stop/Close row, the schematic status line above it, and (only when
    // awaiting a purchase) the approve/decline row above that. Computed once
    // here so init()'s click zones and render()'s drawing never drift apart.
    private int controlY() { return this.height - BUTTON_H - 8; }
    private int statusTextY() { return controlY() - 12; }
    private int approveY() { return statusTextY() - 6 - BUTTON_H; }

    private final BuilderConfig config;
    private final LitematicFileSchematicSource schematicSource;
    private final BuildExecutor executor;
    private final int tab;

    private final List<Runnable> pendingApplies = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private record Label(int x, int y, String text, int color) {}

    private enum RowKind { HEADER, OPTION, FIELD, STATUS_LINE }
    private interface FieldFactory { TextFieldWidget make(int x, int y, int w, int h); }
    private record Row(RowKind kind, String header, String label, Supplier<String> value,
                       BooleanSupplier toggleState, Runnable onClick, FieldFactory fieldFactory) {}
    private record Placed(Row row, int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
    private enum ButtonStyle { PRIMARY, GHOST, DANGER, PLAIN }
    private record ClickZone(int x, int y, int w, int h, String label, ButtonStyle style, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final List<Placed> placedOptions = new ArrayList<>();
    private final List<ClickZone> clickZones = new ArrayList<>();
    private final List<int[]> tabZones = new ArrayList<>(); // {x, w} per tab, for hover/underline
    private int contentBottom;
    private int schematicStatusY = -1;
    private int panelLeft, panelTop;

    private void head(String text) { rows.add(new Row(RowKind.HEADER, text, null, null, null, null, null)); }
    private void statusLine() { rows.add(new Row(RowKind.STATUS_LINE, null, null, null, null, null, null)); }

    public BuildOptionsScreen(BuilderConfig config, LitematicFileSchematicSource source, BuildExecutor executor) {
        this(config, source, executor, 0);
    }

    public BuildOptionsScreen(BuilderConfig config, LitematicFileSchematicSource source,
                              BuildExecutor executor, int tab) {
        super(Text.literal("Auto Builder"));
        this.config = config;
        this.schematicSource = source;
        this.executor = executor;
        this.tab = tab;
    }

    @Override
    protected void init() {
        pendingApplies.clear();
        labels.clear();
        clickZones.clear();
        tabZones.clear();

        int left = this.width / 2 - PANEL_W / 2;
        int top = 28;
        panelLeft = left;
        panelTop = top;

        int tabW = PANEL_W / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            tabZones.add(new int[]{left + i * tabW, tabW - 2});
        }

        rows.clear();
        switch (tab) {
            case 0 -> buildTab();
            case 1 -> moveTab();
            case 2 -> timingTab();
            case 3 -> buyingTab();
            case 4 -> safetyTab();
            case 5 -> itemsTab();
            default -> statusTab();
        }
        layoutRows(left, top + TAB_H + 10);

        if (tab == 6 && executor.isAwaitingPurchaseConfirmation()) {
            int halfW = PANEL_W / 2 - 3;
            clickZones.add(new ClickZone(left, approveY(), halfW, BUTTON_H,
                    "Approve purchase", ButtonStyle.PRIMARY, executor::confirmPurchase));
            clickZones.add(new ClickZone(left + PANEL_W / 2 + 3, approveY(), halfW, BUTTON_H,
                    "Decline", ButtonStyle.DANGER, executor::declinePurchase));
        }

        // Four even buttons spanning the panel width, with a 3px gutter between
        // each -- computed from PANEL_W rather than hardcoded so a wider panel
        // gets wider, easier-to-hit buttons instead of a fixed narrow strip.
        int controlY = controlY();
        int gutter = 3;
        int btnW = (PANEL_W - gutter * 3) / 4;
        clickZones.add(new ClickZone(left, controlY, btnW, BUTTON_H, "Start", ButtonStyle.PRIMARY, () -> {
            applyPending();
            // Only close if it actually started -- closing unconditionally made a
            // schematic Litematica sync hadn't found yet look like Start did
            // nothing at all, with no explanation anywhere.
            if (schematicSource.isLoaded()) {
                executor.start(MinecraftClient.getInstance());
                close();
            } else {
                AutoBuilderClient.LITEMATICA_SYNC.checkNow();
            }
        }));
        clickZones.add(new ClickZone(left + (btnW + gutter), controlY, btnW, BUTTON_H,
                "Pause", ButtonStyle.GHOST, executor::pause));
        clickZones.add(new ClickZone(left + (btnW + gutter) * 2, controlY, btnW, BUTTON_H,
                "Stop", ButtonStyle.GHOST, executor::stop));
        clickZones.add(new ClickZone(left + (btnW + gutter) * 3, controlY, btnW, BUTTON_H,
                "Close", ButtonStyle.PLAIN, this::close));
    }

    /**
     * Places the collected rows, shrinking row height to fit the space actually
     * available -- at GUI scale 3-4 the usable height is barely 270px, and a
     * fixed pitch previously pushed options underneath the footer/controls.
     */
    private void layoutRows(int x, int top) {
        schematicStatusY = -1;
        placedOptions.clear();
        int bottom = statusTextY() - 6;
        int available = Math.max(40, bottom - top);

        int headers = 0, others = 0;
        for (Row row : rows) {
            if (row.kind() == RowKind.HEADER) headers++; else others++;
        }
        if (headers == 0 && others == 0) return;

        int headerH = 14;
        int rowH = ROW_H;
        while (rowH > MIN_ROW_H && headers * headerH + others * rowH > available) rowH--;
        if (headers * headerH + others * rowH > available) headerH = 10;

        int widgetH = Math.max(MIN_WIDGET_H, rowH - 5);
        int y = top;
        for (Row row : rows) {
            switch (row.kind()) {
                case HEADER -> {
                    labels.add(new Label(x, y, row.header(), TEXT_DIM));
                    y += headerH;
                }
                case STATUS_LINE -> {
                    schematicStatusY = y;
                    y += rowH;
                }
                case OPTION -> {
                    placedOptions.add(new Placed(row, x, y, PANEL_W, widgetH));
                    y += rowH;
                }
                case FIELD -> {
                    TextFieldWidget widget = row.fieldFactory().make(x, y, PANEL_W, widgetH);
                    addDrawableChild(widget);
                    placedOptions.add(new Placed(row, x, y, PANEL_W, widgetH));
                    y += rowH;
                }
            }
        }
        contentBottom = y;
    }

    private void switchTo(int target) {
        applyPending();
        MinecraftClient.getInstance().setScreen(
                new BuildOptionsScreen(config, schematicSource, executor, target));
    }

    // ---------------------------------------------------------------- tabs

    private void buildTab() {
        head("Server");
        cycler("Mode", BuilderConfig.ServerPreset.values(),
                () -> config.serverPreset, config::applyPreset, v -> v.label);

        // No file picker, no coordinates: the Build tab just shows what
        // LitematicaSync currently found. Place and position the schematic in
        // Litematica itself and this follows it -- the status line is drawn
        // live in render() so it never goes stale.
        head("Schematic");
        statusLine();

        head("Order");
        cycler("Order", BuilderConfig.BuildStrategy.values(),
                () -> config.strategy, v -> config.strategy = v, v -> v.label);
        cycler("Direction", BuilderConfig.LayerDirection.values(),
                () -> config.layerDirection, v -> config.layerDirection = v, v -> v.label);
        toggle("Finish each layer first", () -> config.strictLayers, v -> config.strictLayers = v);
        cycler("Scaffold", BuilderConfig.ScaffoldBlock.values(),
                () -> config.scaffoldBlock, v -> config.scaffoldBlock = v, v -> v.label);

        head("Matching the schematic");
        toggle("Require exact block match", () -> config.strictBlockMatch, v -> config.strictBlockMatch = v);
        toggle("Break wrong blocks", () -> config.breakWrongBlocks, v -> config.breakWrongBlocks = v);
        toggle("Remove extra blocks", () -> config.removeExtraBlocks, v -> config.removeExtraBlocks = v);
        toggle("Verify pass at end", () -> config.verifyPass, v -> config.verifyPass = v);
        toggle("Re-check while building", () -> config.continuousVerify, v -> config.continuousVerify = v);
    }

    private void moveTab() {
        head("Traversal");
        toggle("Pearl climbing", () -> config.usePearlClimbing, v -> config.usePearlClimbing = v);
        cycler("Pearl reserve", new Integer[]{0, 2, 4, 8, 16},
                () -> config.pearlReserve, v -> config.pearlReserve = v, String::valueOf);
        toggle("Allow jumping", () -> config.allowJump, v -> config.allowJump = v);
        toggle("Sprint", () -> config.allowSprint, v -> config.allowSprint = v);
        cycler("Max fall", new Integer[]{0, 2, 3, 5, 10},
                () -> config.maxFallDistance, v -> config.maxFallDistance = v, v -> v + " blocks");

        head("Placement");
        cycler("Reach", new Double[]{3.0, 3.5, 4.0, 4.5},
                () -> config.maxReach, v -> config.maxReach = v, v -> v + " blocks");
        cycler("Pathfinding effort", new Integer[]{2000, 4000, 8000, 16000},
                () -> config.maxPathNodes, v -> config.maxPathNodes = v, String::valueOf);

        head("Care");
        toggle("Sneak near edges", () -> config.sneakNearEdges, v -> config.sneakNearEdges = v);
        toggle("Avoid lava and fire", () -> config.avoidHazards, v -> config.avoidHazards = v);
        toggle("Return to start when done", () -> config.returnHomeWhenDone, v -> config.returnHomeWhenDone = v);
    }

    private void timingTab() {
        head("Speed");
        cycler("Pace", BuilderConfig.Pace.values(), () -> config.pace, v -> config.pace = v, Enum::name);
        cycler("Delay scale", new Integer[]{50, 75, 100, 150, 200, 300},
                () -> config.speedPercent, v -> config.speedPercent = v, v -> v + "%");
        cycler("Randomness", new Integer[]{0, 50, 100, 150, 250},
                () -> config.jitterPercent, v -> config.jitterPercent = v, v -> v + "%");
        toggle("Tire out over time", () -> config.simulateFatigue, v -> config.simulateFatigue = v);

        head("Breaks");
        toggle("Take breaks", () -> config.takeBreaks, v -> config.takeBreaks = v);
        cycler("Break every", new Integer[]{32, 64, 128, 256, 512},
                () -> config.breakEveryBlocks, v -> config.breakEveryBlocks = v, v -> v + " blocks");
        cycler("Break length", new Integer[]{5, 15, 30, 60, 120, 300},
                () -> config.breakSeconds, v -> config.breakSeconds = v, v -> v + "s");
        toggle("Look around on breaks", () -> config.lookAroundOnBreak, v -> config.lookAroundOnBreak = v);
    }

    private void buyingTab() {
        head("Auction house");
        toggle("Buy missing materials", () -> config.autoBuyMaterials, v -> config.autoBuyMaterials = v);
        field("Command (%s = item)", config.auctionCommandTemplate,
                v -> { if (!v.isBlank()) config.auctionCommandTemplate = v; });
        field("Price regex (group 1 = price)", config.auctionPriceRegex,
                v -> { if (!v.isBlank()) config.auctionPriceRegex = v; });

        head("Price limits (per item)");
        field("Buy without asking under", String.format("%.0f", config.autoBuyLimit),
                v -> config.autoBuyLimit = parseDouble(v, config.autoBuyLimit));
        field("Never buy over", String.format("%.0f", config.hardMaxPrice),
                v -> config.hardMaxPrice = parseDouble(v, config.hardMaxPrice));
        toggle("Ask before expensive buys",
                () -> config.confirmExpensivePurchases, v -> config.confirmExpensivePurchases = v);

        head("Behaviour");
        toggle("Check every page for cheapest", () -> config.scanAllPages, v -> config.scanAllPages = v);
        cycler("Max pages", new Integer[]{1, 3, 5, 10},
                () -> config.maxAuctionPages, v -> config.maxAuctionPages = v, String::valueOf);
        toggle("Needs confirm click",
                () -> config.auctionRequiresConfirmClick, v -> config.auctionRequiresConfirmClick = v);
        cycler("Buy extra", new Integer[]{0, 10, 25, 50},
                () -> config.buyExtraPercent, v -> config.buyExtraPercent = v, v -> v + "%");
        cycler("If unavailable", BuilderConfig.OutOfMaterialsPolicy.values(),
                () -> config.outOfMaterials, v -> config.outOfMaterials = v, v -> v.label);
    }

    private void safetyTab() {
        head("Stop conditions");
        toggle("Stop on low health", () -> config.stopOnLowHealth, v -> config.stopOnLowHealth = v);
        cycler("Health floor", new Integer[]{4, 6, 10, 14},
                () -> config.lowHealthThreshold, v -> config.lowHealthThreshold = v, v -> (v / 2.0) + " hearts");
        toggle("Stop on low hunger", () -> config.stopOnLowHunger, v -> config.stopOnLowHunger = v);
        toggle("Stop if player nearby", () -> config.stopOnPlayerNearby, v -> config.stopOnPlayerNearby = v);
        cycler("Player radius", new Integer[]{16, 32, 64, 128},
                () -> config.stopOnPlayerRadius, v -> config.stopOnPlayerRadius = v, v -> v + " blocks");
        toggle("Stop when inventory full",
                () -> config.stopWhenInventoryFull, v -> config.stopWhenInventoryFull = v);
        cycler("Time limit", new Integer[]{0, 15, 30, 60, 120, 480},
                () -> config.maxBuildMinutes, v -> config.maxBuildMinutes = v, v -> v == 0 ? "none" : v + " min");

        head("Other");
        toggle("Auto-pick tools", () -> config.autoSelectTool, v -> config.autoSelectTool = v);
        toggle("Remember settings", () -> config.saveSettings, v -> config.saveSettings = v);
    }

    /** The shopping list: body is drawn in render() so the numbers stay live. */
    private void itemsTab() { head("Materials"); }

    private void statusTab() { head("Progress"); }

    // ---------------------------------------------------------------- row builders

    private <T> void cycler(String label, T[] values, Supplier<T> getter, Consumer<T> setter, Function<T, String> render) {
        Runnable onClick = () -> {
            T current = getter.get();
            int index = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(current)) { index = i; break; }
            }
            setter.accept(values[(index + 1) % values.length]);
            AutoBuilderClient.saveConfig();
        };
        rows.add(new Row(RowKind.OPTION, null, label, () -> render.apply(getter.get()), null, onClick, null));
    }

    private void toggle(String label, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        Runnable onClick = () -> {
            setter.accept(!getter.get());
            AutoBuilderClient.saveConfig();
        };
        rows.add(new Row(RowKind.OPTION, null, label, null, getter::get, onClick, null));
    }

    /** Text fields don't push on every keystroke -- apply runs on tab change, start, or close. */
    private void field(String placeholder, String initial, Consumer<String> apply) {
        rows.add(new Row(RowKind.FIELD, null, null, null, null, null, (x, y, w, h) -> {
            TextFieldWidget widget = new TextFieldWidget(this.textRenderer, x, y, w, h, Text.literal(placeholder));
            widget.setMaxLength(256);
            widget.setText(initial);
            widget.setPlaceholder(Text.literal(placeholder));
            widget.setDrawsBackground(false); // flat BG_FIELD box is drawn behind it in render() instead
            pendingApplies.add(() -> apply.accept(widget.getText()));
            return widget;
        }));
    }

    private static double parseDouble(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim().replace(",", "").replace("_", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void applyPending() {
        for (Runnable r : pendingApplies) r.run();
        AutoBuilderClient.saveConfig();
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        if (click.button() != 0) return false;
        double mouseX = click.x();
        double mouseY = click.y();
        for (int i = 0; i < tabZones.size(); i++) {
            int[] z = tabZones.get(i);
            if (mouseX >= z[0] && mouseX < z[0] + z[1] && mouseY >= panelTop && mouseY < panelTop + TAB_H) {
                switchTo(i);
                return true;
            }
        }
        for (Placed p : placedOptions) {
            if (p.row().kind() == RowKind.OPTION && p.contains(mouseX, mouseY)) {
                p.row().onClick().run();
                return true;
            }
        }
        for (ClickZone zone : clickZones) {
            if (zone.contains(mouseX, mouseY)) {
                zone.action().run();
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- render

    @Override
    public void close() {
        applyPending();
        super.close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int left = panelLeft;
        int px1 = left - 10, py1 = 20, px2 = left + PANEL_W + 10, py2 = this.height - 4;

        // Dim the game behind the panel so it reads as a surface floating in
        // front rather than options text scattered over the world.
        context.fill(0, 0, this.width, this.height, SCREEN_DIM);

        // Soft drop shadow, offset down-right, faded by drawing it in from the
        // panel edge rather than as a single hard-edged block.
        context.fill(px1 + 4, py2, px2 + 4, py2 + 4, SHADOW);
        context.fill(px2, py1 + 4, px2 + 4, py2 + 4, SHADOW);

        context.fill(px1, py1, px2, py2, BG_PANEL);
        cutCorners(context, px1, py1, px2, py2, SCREEN_DIM);
        drawOutline(context, px1, py1, px2 - px1, py2 - py1, PANEL_BORDER);
        context.fill(px1 + 1, py1 + 1, px2 - 1, py1 + 2, ACCENT_LINE);

        // Field backgrounds drawn before super.render() so the (background-less)
        // TextFieldWidgets paint their text on top of our flat box, not the
        // other way around.
        for (Placed p : placedOptions) {
            if (p.row().kind() == RowKind.FIELD) {
                int fx1 = p.x() - 3, fy1 = p.y() - 2, fx2 = p.x() + p.w() + 3, fy2 = p.y() + p.h() + 2;
                context.fill(fx1, fy1, fx2, fy2, BG_FIELD);
                cutCorners(context, fx1, fy1, fx2, fy2, BG_PANEL);
                drawOutline(context, fx1, fy1, fx2 - fx1, fy2 - fy1, PANEL_BORDER);
                context.fill(fx1, fy2 - 1, fx2, fy2, ACCENT_LINE);
            }
        }

        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, TEXT_PRIMARY);

        renderTabs(context, mouseX, mouseY);

        for (Label label : labels) {
            // A small accent tick and a hairline rule under each section header,
            // rather than dim text sitting bare against the options below it.
            context.fill(label.x(), label.y() + 1, label.x() + 2, label.y() + 7, ACCENT_LINE);
            context.drawTextWithShadow(this.textRenderer, Text.literal(label.text()),
                    label.x() + 6, label.y(), label.color());
            context.fill(label.x() + 6, label.y() + 9, panelLeft + PANEL_W, label.y() + 10, DIVIDER);
        }
        for (Placed p : placedOptions) {
            if (p.row().kind() == RowKind.OPTION) renderOption(context, p, mouseX, mouseY);
        }

        if (tab == 0 && schematicStatusY >= 0) renderSchematicStatus(context, left);
        if (tab == 5) renderItems(context, left);
        if (tab == 6) renderStatus(context, left);

        for (ClickZone zone : clickZones) {
            renderControlButton(context, zone, mouseX, mouseY);
        }

        boolean loaded = schematicSource.isLoaded();
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(loaded ? schematicSource.describe() : "No schematic loaded"),
                this.width / 2, statusTextY(), loaded ? TEXT_SECONDARY : BAD);
    }

    private void renderTabs(DrawContext context, int mouseX, int mouseY) {
        for (int i = 0; i < TABS.length; i++) {
            int[] z = tabZones.get(i);
            boolean active = i == tab;
            boolean hovered = mouseX >= z[0] && mouseX < z[0] + z[1] && mouseY >= panelTop && mouseY < panelTop + TAB_H;
            if (active) {
                context.fill(z[0], panelTop + 1, z[0] + z[1], panelTop + TAB_H - 1, HOVER_BG);
                cutCorners(context, z[0], panelTop + 1, z[0] + z[1], panelTop + TAB_H - 1, BG_PANEL);
            } else if (hovered) {
                context.fill(z[0], panelTop, z[0] + z[1], panelTop + TAB_H, HOVER_BG);
            }
            int color = active ? ACCENT : (hovered ? TEXT_PRIMARY : TEXT_SECONDARY);
            int tw = this.textRenderer.getWidth(TABS[i]);
            context.drawTextWithShadow(this.textRenderer, Text.literal(TABS[i]),
                    z[0] + (z[1] - tw) / 2, panelTop + (TAB_H - 8) / 2, color);
            if (active) context.fill(z[0] + 2, panelTop + TAB_H, z[0] + z[1] - 2, panelTop + TAB_H + 2, ACCENT);
        }
        context.fill(panelLeft, panelTop + TAB_H + 2, panelLeft + PANEL_W, panelTop + TAB_H + 3, DIVIDER);
    }

    private void renderOption(DrawContext context, Placed p, int mouseX, int mouseY) {
        boolean hovered = p.contains(mouseX, mouseY);
        if (hovered) context.fill(p.x() - 4, p.y() - 2, p.x() + p.w() + 4, p.y() + p.h() + 2, HOVER_BG);

        context.drawTextWithShadow(this.textRenderer, Text.literal(p.row().label()),
                p.x() + 2, p.y() + (p.h() - 8) / 2, TEXT_PRIMARY);

        if (p.row().toggleState() != null) {
            renderSwitch(context, p, p.row().toggleState().getAsBoolean());
        } else {
            String text = p.row().value().get() + "  ›"; // trailing "›" hints it's clickable
            int tw = this.textRenderer.getWidth(text);
            context.drawTextWithShadow(this.textRenderer, Text.literal(text),
                    p.x() + p.w() - tw - 2, p.y() + (p.h() - 8) / 2, ACCENT);
        }
    }

    private void renderSwitch(DrawContext context, Placed p, boolean on) {
        int trackW = 24, trackH = 12;
        int tx = p.x() + p.w() - trackW - 2;
        int ty = p.y() + (p.h() - trackH) / 2;
        context.fill(tx, ty, tx + trackW, ty + trackH, on ? ACCENT : TRACK_OFF);
        context.fill(tx, ty + trackH - 1, tx + trackW, ty + trackH, BEVEL_DARK);
        cutCorners(context, tx, ty, tx + trackW, ty + trackH, BG_PANEL);
        int thumb = 8;
        int thumbX = on ? tx + trackW - thumb - 2 : tx + 2;
        int thumbY = ty + (trackH - thumb) / 2;
        context.fill(thumbX, thumbY, thumbX + thumb, thumbY + thumb, TEXT_PRIMARY);
        context.fill(thumbX, thumbY, thumbX + thumb, thumbY + 1, BEVEL_LIGHT);
        context.fill(thumbX, thumbY + thumb - 1, thumbX + thumb, thumbY + thumb, BEVEL_DARK);
    }

    private void renderControlButton(DrawContext context, ClickZone zone, int mouseX, int mouseY) {
        boolean hovered = zone.contains(mouseX, mouseY);

        int zx = zone.x(), zy = zone.y(), zx2 = zone.x() + zone.w(), zy2 = zone.y() + zone.h();
        switch (zone.style()) {
            case PRIMARY -> {
                context.fill(zx, zy, zx2, zy2, hovered ? ACCENT : ACCENT_LINE);
                context.fill(zx, zy, zx2, zy + 1, BEVEL_LIGHT);
                context.fill(zx, zy2 - 1, zx2, zy2, BEVEL_DARK);
                cutCorners(context, zx, zy, zx2, zy2, BG_PANEL);
            }
            case DANGER -> {
                if (hovered) context.fill(zx, zy, zx2, zy2, HOVER_BG);
                drawOutline(context, zx, zy, zone.w(), zone.h(), BAD);
                cutCorners(context, zx, zy, zx2, zy2, BG_PANEL);
            }
            case GHOST -> {
                if (hovered) context.fill(zx, zy, zx2, zy2, HOVER_BG);
                drawOutline(context, zx, zy, zone.w(), zone.h(), TEXT_DIM);
                cutCorners(context, zx, zy, zx2, zy2, BG_PANEL);
            }
            case PLAIN -> {
                if (hovered) context.fill(zx, zy, zx2, zy2, HOVER_BG);
            }
        }

        int textColor = switch (zone.style()) {
            case PRIMARY -> TEXT_PRIMARY;
            case DANGER -> BAD;
            case GHOST, PLAIN -> TEXT_SECONDARY;
        };
        int tw = this.textRenderer.getWidth(zone.label());
        context.drawTextWithShadow(this.textRenderer, Text.literal(zone.label()),
                zone.x() + (zone.w() - tw) / 2, zone.y() + (zone.h() - 8) / 2, textColor);
    }

    private void drawOutline(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** Drawn fresh every frame so it reflects LitematicaSync's latest read without reopening the menu. */
    private void renderSchematicStatus(DrawContext context, int x) {
        boolean loaded = schematicSource.isLoaded();
        String text = loaded
                ? "✓ " + schematicSource.describe()
                : "✗ " + AutoBuilderClient.LITEMATICA_SYNC.getStatus();
        context.drawTextWithShadow(this.textRenderer, Text.literal(text), x, schematicStatusY, loaded ? GOOD : WARN);
    }

    private void renderItems(DrawContext context, int x) {
        int y = contentBottom + 4;
        Map<Item, Integer> needed = executor.getMaterials();
        if (needed.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("Press Start to work out what this build needs."), x, y, TEXT_SECONDARY);
            return;
        }
        Map<Item, Integer> shortfall = executor.getShortfall(MinecraftClient.getInstance());

        int shown = 0;
        int maxRows = (this.height - 100 - y) / 12;
        for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
            if (shown >= maxRows) {
                context.drawTextWithShadow(this.textRenderer,
                        Text.literal("... and " + (needed.size() - shown) + " more"), x, y, TEXT_SECONDARY);
                break;
            }
            int missing = shortfall.getOrDefault(entry.getKey(), 0);
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal(entry.getKey().getName().getString()), x, y, TEXT_PRIMARY);
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("need " + entry.getValue()), x + PANEL_W * 55 / 100, y, TEXT_SECONDARY);
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal(missing > 0 ? "short " + missing : "ok"),
                    x + PANEL_W * 79 / 100, y, missing > 0 ? BAD : GOOD);
            y += 12;
            shown++;
        }
    }

    private void renderStatus(DrawContext context, int x) {
        int y = contentBottom + 4;

        int done = executor.getPlacedCount();
        int total = executor.getTotalCount();
        int percent = total == 0 ? 0 : done * 100 / total;

        int barH = 10;
        context.fill(x, y, x + PANEL_W, y + barH, BG_FIELD);
        int fillW = (int) (PANEL_W * (percent / 100.0));
        if (fillW > 0) context.fill(x, y, x + fillW, y + barH, GOOD);
        cutCorners(context, x, y, x + PANEL_W, y + barH, BG_PANEL);
        drawOutline(context, x, y, PANEL_W, barH, PANEL_BORDER);
        String pctText = percent + "%";
        int ptw = this.textRenderer.getWidth(pctText);
        context.drawTextWithShadow(this.textRenderer, Text.literal(pctText),
                x + PANEL_W - ptw - 3, y + 1, TEXT_PRIMARY);
        y += barH + 6;

        line(context, x, y, "Progress", total == 0 ? "no plan yet" : done + " / " + total + "  (" + percent + "%)", GOOD); y += 12;
        line(context, x, y, "State", executor.getState().toString(), GOOD); y += 12;
        line(context, x, y, "Doing", executor.getStatusMessage(), TEXT_SECONDARY); y += 12;
        line(context, x, y, "Layer", executor.getLayerProgress(), TEXT_SECONDARY); y += 12;
        line(context, x, y, "Rate", String.format("%.0f blocks/min", executor.getBlocksPerMinute()), TEXT_SECONDARY); y += 12;
        line(context, x, y, "ETA", executor.getEta(), TEXT_SECONDARY); y += 12;
        line(context, x, y, "Skipped", String.valueOf(executor.getSkippedCount()),
                executor.getSkippedCount() > 0 ? WARN : TEXT_SECONDARY); y += 12;
        line(context, x, y, "To remove", String.valueOf(executor.getRemovalCount()), TEXT_SECONDARY); y += 12;
        line(context, x, y, "Fatigue", executor.getFatiguePercent() + "%", TEXT_SECONDARY); y += 12;

        if (executor.isAwaitingPurchaseConfirmation()) {
            y += 6;
            context.drawTextWithShadow(this.textRenderer, Text.literal("Waiting for approval:"), x, y, WARN);
            y += 12;
            context.drawTextWithShadow(this.textRenderer, Text.literal(executor.getPurchasePrompt()), x, y, BAD);
        }
    }

    private void line(DrawContext context, int x, int y, String label, String value, int color) {
        context.drawTextWithShadow(this.textRenderer, Text.literal(label), x, y, TEXT_SECONDARY);
        context.drawTextWithShadow(this.textRenderer, Text.literal(value), x + 82, y, color);
    }

    @Override
    public boolean shouldPause() { return false; }
}
