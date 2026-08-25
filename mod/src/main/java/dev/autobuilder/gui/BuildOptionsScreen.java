package dev.autobuilder.gui;

import dev.autobuilder.AutoBuilderClient;
import dev.autobuilder.config.BuilderConfig;
import dev.autobuilder.exec.BuildExecutor;
import dev.autobuilder.schematic.LitematicFileSchematicSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The build options menu, opened with [.
 *
 * Seven tabs, because there are far more options than fit on a page. Switching
 * tabs re-opens the screen carrying the tab index, rather than relying on a
 * re-init method whose name varies between versions.
 *
 * Built from ButtonWidget and TextFieldWidget only. The fancier widgets
 * (CyclingButtonWidget, scroll panes) have version-sensitive signatures that
 * aren't worth the breakage, and a cycling button is one click either way.
 */
public class BuildOptionsScreen extends Screen {

    private static final String[] TABS = {"Build", "Move", "Timing", "Buying", "Safety", "Items", "Status"};

    private static final int PANEL_W = 310;
    private static final int ROW_H = 21;
    private static final int TAB_H = 18;
    private static final int MIN_ROW_H = 13;
    private static final int MIN_WIDGET_H = 12;

    private static final int TITLE = 0xFFFFFF;
    private static final int HEADER = 0xFFD68A;
    private static final int MUTED = 0x9A9A9A;
    private static final int GOOD = 0x8FCF8F;
    private static final int WARN = 0xE8C07A;
    private static final int BAD = 0xE87A7A;
    private static final int PANEL_BG = 0xB0101014;
    private static final int PANEL_EDGE = 0x40FFFFFF;

    private final BuilderConfig config;
    private final LitematicFileSchematicSource schematicSource;
    private final BuildExecutor executor;
    private final int tab;

    /** Text-field values, pushed into config on tab change / start / close. */
    private final List<Runnable> pendingApplies = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();

    private record Label(int x, int y, String text, int color) {}

    /**
     * Rows are collected first and positioned afterwards, so the layout can
     * measure how much room it actually has. Placing them as they were declared
     * overflowed the footer and control buttons at higher GUI scales, where the
     * usable height is barely 270px.
     */
    private interface RowFactory { ClickableWidget make(int x, int y, int height); }
    /** kind: WIDGET has a factory, HEADER has header text, STATUS_LINE reserves a
     *  row for the live Litematica status text drawn in render(). */
    private enum RowKind { WIDGET, HEADER, STATUS_LINE }
    private record Row(RowKind kind, String header, RowFactory factory) {}
    private final List<Row> rows = new ArrayList<>();
    /** Where the laid-out rows ended, so render() can put free text below them. */
    private int contentBottom;
    /** Where the reserved schematic-status row landed, so render() can draw into it. */
    private int schematicStatusY = -1;

    private void head(String text) { rows.add(new Row(RowKind.HEADER, text, null)); }
    private void row(RowFactory factory) { rows.add(new Row(RowKind.WIDGET, null, factory)); }
    private void statusLine() { rows.add(new Row(RowKind.STATUS_LINE, null, null)); }

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

        int left = this.width / 2 - PANEL_W / 2;
        int top = 30;

        int tabW = PANEL_W / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            final int target = i;
            addDrawableChild(ButtonWidget.builder(
                    Text.literal(TABS[i]), b -> switchTo(target))
                    .dimensions(left + i * tabW, top, tabW - 2, TAB_H).build());
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
        layoutRows(left, top + TAB_H + 8);

        if (tab == 6 && executor.isAwaitingPurchaseConfirmation()) {
            int approveY = this.height - 52;
            int halfW = PANEL_W / 2 - 2;
            addDrawableChild(ButtonWidget.builder(Text.literal("Approve purchase"),
                    b -> executor.confirmPurchase()).dimensions(left, approveY, halfW, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Decline"),
                    b -> executor.declinePurchase())
                    .dimensions(left + PANEL_W / 2 + 2, approveY, halfW, 20).build());
        }

        int controlY = this.height - 28;
        addDrawableChild(ButtonWidget.builder(Text.literal("Start"), b -> {
            applyPending();
            // Only close if it actually started -- previously this closed
            // unconditionally, so a schematic that Litematica sync hadn't found
            // yet made Start look like it did nothing at all: the menu just
            // vanished with no error shown anywhere.
            if (schematicSource.isLoaded()) {
                executor.start(MinecraftClient.getInstance());
                close();
            } else {
                AutoBuilderClient.LITEMATICA_SYNC.checkNow();
            }
        }).dimensions(left, controlY, 76, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Pause"), b -> executor.pause())
                .dimensions(left + 78, controlY, 76, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Stop"), b -> executor.stop())
                .dimensions(left + 156, controlY, 76, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(left + 234, controlY, 76, 20).build());
    }


    /**
     * Places the collected rows, shrinking row height to fit the space actually
     * available. At GUI scale 3 or 4 the usable height is only ~270px, and a
     * fixed 21px row pitch pushed the last options underneath the footer and the
     * Start/Pause/Stop buttons, where they could not be clicked.
     */
    private void layoutRows(int x, int top) {
        schematicStatusY = -1;
        int bottom = this.height - 46;          // clear of the footer and controls
        int available = Math.max(40, bottom - top);

        int headers = 0, others = 0;
        for (Row row : rows) {
            if (row.kind() == RowKind.HEADER) headers++; else others++;
        }
        if (headers == 0 && others == 0) return;

        int headerH = 11;
        int rowH = ROW_H;
        while (rowH > MIN_ROW_H && headers * headerH + others * rowH > available) rowH--;
        if (headers * headerH + others * rowH > available) headerH = 9;

        int widgetH = Math.max(MIN_WIDGET_H, rowH - 1);
        int y = top;
        for (Row row : rows) {
            switch (row.kind()) {
                case HEADER -> {
                    labels.add(new Label(x, y, row.header(), HEADER));
                    y += headerH;
                }
                case STATUS_LINE -> {
                    schematicStatusY = y;
                    y += rowH;
                }
                case WIDGET -> {
                    addDrawableChild(row.factory().make(x, y, widgetH));
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
        // No file picker, no coordinates: the Build tab just shows what
        // LitematicaSync currently found. Place and position the schematic in
        // Litematica itself (drag it where you want it) and this follows it --
        // the status line is drawn live in render() so it never goes stale.
        head("Schematic");
        statusLine();

        head("Order");
        row((x, y, h) -> cycler(x, y, h, "Order", BuilderConfig.BuildStrategy.values(),
                () -> config.strategy, v -> config.strategy = v, v -> v.label));
        row((x, y, h) -> cycler(x, y, h, "Direction", BuilderConfig.LayerDirection.values(),
                () -> config.layerDirection, v -> config.layerDirection = v, v -> v.label));
        row((x, y, h) -> toggle(x, y, h, "Finish each layer first",
                () -> config.strictLayers, v -> config.strictLayers = v));
        row((x, y, h) -> cycler(x, y, h, "Scaffold", BuilderConfig.ScaffoldBlock.values(),
                () -> config.scaffoldBlock, v -> config.scaffoldBlock = v, v -> v.label));

        head("Matching the schematic");
        row((x, y, h) -> toggle(x, y, h, "Require exact block match",
                () -> config.strictBlockMatch, v -> config.strictBlockMatch = v));
        row((x, y, h) -> toggle(x, y, h, "Break wrong blocks",
                () -> config.breakWrongBlocks, v -> config.breakWrongBlocks = v));
        row((x, y, h) -> toggle(x, y, h, "Remove extra blocks",
                () -> config.removeExtraBlocks, v -> config.removeExtraBlocks = v));
        row((x, y, h) -> toggle(x, y, h, "Verify pass at end",
                () -> config.verifyPass, v -> config.verifyPass = v));
        row((x, y, h) -> toggle(x, y, h, "Re-check while building",
                () -> config.continuousVerify, v -> config.continuousVerify = v));
    }

    private void moveTab() {
        head("Traversal");
        row((x, y, h) -> toggle(x, y, h, "Pearl climbing",
                () -> config.usePearlClimbing, v -> config.usePearlClimbing = v));
        row((x, y, h) -> cycler(x, y, h, "Pearl reserve", new Integer[]{0, 2, 4, 8, 16},
                () -> config.pearlReserve, v -> config.pearlReserve = v, String::valueOf));
        row((x, y, h) -> toggle(x, y, h, "Allow jumping",
                () -> config.allowJump, v -> config.allowJump = v));
        row((x, y, h) -> toggle(x, y, h, "Sprint", () -> config.allowSprint, v -> config.allowSprint = v));
        row((x, y, h) -> cycler(x, y, h, "Max fall", new Integer[]{0, 2, 3, 5, 10},
                () -> config.maxFallDistance, v -> config.maxFallDistance = v, v -> v + " blocks"));

        head("Placement");
        row((x, y, h) -> cycler(x, y, h, "Reach", new Double[]{3.0, 3.5, 4.0, 4.5},
                () -> config.maxReach, v -> config.maxReach = v, v -> v + " blocks"));
        row((x, y, h) -> cycler(x, y, h, "Pathfinding effort", new Integer[]{2000, 4000, 8000, 16000},
                () -> config.maxPathNodes, v -> config.maxPathNodes = v, String::valueOf));

        head("Care");
        row((x, y, h) -> toggle(x, y, h, "Sneak near edges",
                () -> config.sneakNearEdges, v -> config.sneakNearEdges = v));
        row((x, y, h) -> toggle(x, y, h, "Avoid lava and fire",
                () -> config.avoidHazards, v -> config.avoidHazards = v));
        row((x, y, h) -> toggle(x, y, h, "Return to start when done",
                () -> config.returnHomeWhenDone, v -> config.returnHomeWhenDone = v));
    }

    private void timingTab() {
        head("Speed");
        row((x, y, h) -> cycler(x, y, h, "Pace", BuilderConfig.Pace.values(),
                () -> config.pace, v -> config.pace = v, Enum::name));
        row((x, y, h) -> cycler(x, y, h, "Delay scale", new Integer[]{50, 75, 100, 150, 200, 300},
                () -> config.speedPercent, v -> config.speedPercent = v, v -> v + "%"));
        row((x, y, h) -> cycler(x, y, h, "Randomness", new Integer[]{0, 50, 100, 150, 250},
                () -> config.jitterPercent, v -> config.jitterPercent = v, v -> v + "%"));
        row((x, y, h) -> toggle(x, y, h, "Tire out over time",
                () -> config.simulateFatigue, v -> config.simulateFatigue = v));

        head("Breaks");
        row((x, y, h) -> toggle(x, y, h, "Take breaks",
                () -> config.takeBreaks, v -> config.takeBreaks = v));
        row((x, y, h) -> cycler(x, y, h, "Break every", new Integer[]{32, 64, 128, 256, 512},
                () -> config.breakEveryBlocks, v -> config.breakEveryBlocks = v, v -> v + " blocks"));
        row((x, y, h) -> cycler(x, y, h, "Break length", new Integer[]{5, 15, 30, 60, 120, 300},
                () -> config.breakSeconds, v -> config.breakSeconds = v, v -> v + "s"));
        row((x, y, h) -> toggle(x, y, h, "Look around on breaks",
                () -> config.lookAroundOnBreak, v -> config.lookAroundOnBreak = v));
    }

    private void buyingTab() {
        head("Auction house");
        row((x, y, h) -> toggle(x, y, h, "Buy missing materials",
                () -> config.autoBuyMaterials, v -> config.autoBuyMaterials = v));
        row((x, y, h) -> field(x, y, h, "Command (%s = item)", config.auctionCommandTemplate,
                v -> { if (!v.isBlank()) config.auctionCommandTemplate = v; }));
        row((x, y, h) -> field(x, y, h, "Price regex (group 1 = price)", config.auctionPriceRegex,
                v -> { if (!v.isBlank()) config.auctionPriceRegex = v; }));

        head("Price limits (per item)");
        row((x, y, h) -> field(x, y, h, "Buy without asking under",
                String.format("%.0f", config.autoBuyLimit),
                v -> config.autoBuyLimit = parseDouble(v, config.autoBuyLimit)));
        row((x, y, h) -> field(x, y, h, "Never buy over",
                String.format("%.0f", config.hardMaxPrice),
                v -> config.hardMaxPrice = parseDouble(v, config.hardMaxPrice)));
        row((x, y, h) -> toggle(x, y, h, "Ask before expensive buys",
                () -> config.confirmExpensivePurchases, v -> config.confirmExpensivePurchases = v));

        head("Behaviour");
        row((x, y, h) -> toggle(x, y, h, "Check every page for cheapest",
                () -> config.scanAllPages, v -> config.scanAllPages = v));
        row((x, y, h) -> cycler(x, y, h, "Max pages", new Integer[]{1, 3, 5, 10},
                () -> config.maxAuctionPages, v -> config.maxAuctionPages = v, String::valueOf));
        row((x, y, h) -> toggle(x, y, h, "Needs confirm click",
                () -> config.auctionRequiresConfirmClick, v -> config.auctionRequiresConfirmClick = v));
        row((x, y, h) -> cycler(x, y, h, "Buy extra", new Integer[]{0, 10, 25, 50},
                () -> config.buyExtraPercent, v -> config.buyExtraPercent = v, v -> v + "%"));
        row((x, y, h) -> cycler(x, y, h, "If unavailable", BuilderConfig.OutOfMaterialsPolicy.values(),
                () -> config.outOfMaterials, v -> config.outOfMaterials = v, v -> v.label));
    }

    private void safetyTab() {
        head("Stop conditions");
        row((x, y, h) -> toggle(x, y, h, "Stop on low health",
                () -> config.stopOnLowHealth, v -> config.stopOnLowHealth = v));
        row((x, y, h) -> cycler(x, y, h, "Health floor", new Integer[]{4, 6, 10, 14},
                () -> config.lowHealthThreshold, v -> config.lowHealthThreshold = v,
                v -> (v / 2.0) + " hearts"));
        row((x, y, h) -> toggle(x, y, h, "Stop on low hunger",
                () -> config.stopOnLowHunger, v -> config.stopOnLowHunger = v));
        row((x, y, h) -> toggle(x, y, h, "Stop if player nearby",
                () -> config.stopOnPlayerNearby, v -> config.stopOnPlayerNearby = v));
        row((x, y, h) -> cycler(x, y, h, "Player radius", new Integer[]{16, 32, 64, 128},
                () -> config.stopOnPlayerRadius, v -> config.stopOnPlayerRadius = v, v -> v + " blocks"));
        row((x, y, h) -> toggle(x, y, h, "Stop when inventory full",
                () -> config.stopWhenInventoryFull, v -> config.stopWhenInventoryFull = v));
        row((x, y, h) -> cycler(x, y, h, "Time limit", new Integer[]{0, 15, 30, 60, 120, 480},
                () -> config.maxBuildMinutes, v -> config.maxBuildMinutes = v,
                v -> v == 0 ? "none" : v + " min"));

        head("Other");
        row((x, y, h) -> toggle(x, y, h, "Auto-pick tools",
                () -> config.autoSelectTool, v -> config.autoSelectTool = v));
        row((x, y, h) -> toggle(x, y, h, "Remember settings",
                () -> config.saveSettings, v -> config.saveSettings = v));
    }

    /** The shopping list: body is drawn in render() so the numbers stay live. */
    private void itemsTab() {
        head("Materials");
    }

    private void statusTab() {
        head("Progress");
        // The approve/decline pair is placed in init() at a fixed spot rather
        // than as rows: rows lay out top-down, which would put the buttons above
        // the statistics they refer to.
    }

    // ---------------------------------------------------------------- widgets

    /** A button reading "Label: value" that advances to the next value on click. */
    private <T> ButtonWidget cycler(int x, int y, int h, String label, T[] values,
                                    Supplier<T> getter, Consumer<T> setter, Function<T, String> render) {
        return ButtonWidget.builder(Text.literal(label + ": " + render.apply(getter.get())), btn -> {
            T current = getter.get();
            int index = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(current)) { index = i; break; }
            }
            T next = values[(index + 1) % values.length];
            setter.accept(next);
            btn.setMessage(Text.literal(label + ": " + render.apply(next)));
            AutoBuilderClient.saveConfig();
        }).dimensions(x, y, PANEL_W, h).build();
    }

    private ButtonWidget toggle(int x, int y, int h, String label,
                                Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return cycler(x, y, h, label, new Boolean[]{Boolean.TRUE, Boolean.FALSE},
                getter, setter, v -> v ? "ON" : "OFF");
    }

    /**
     * Text fields don't push on every keystroke -- the apply runs on tab change,
     * start, or close, so a half-typed value never lands in the config.
     */
    private TextFieldWidget field(int x, int y, int h, String placeholder, String initial, Consumer<String> apply) {
        TextFieldWidget widget = new TextFieldWidget(this.textRenderer, x, y, PANEL_W, h, Text.literal(placeholder));
        widget.setMaxLength(256);
        widget.setText(initial);
        widget.setPlaceholder(Text.literal(placeholder));
        pendingApplies.add(() -> apply.accept(widget.getText()));
        return widget;
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

    // ---------------------------------------------------------------- render

    @Override
    public void close() {
        applyPending();
        super.close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int left = this.width / 2 - PANEL_W / 2;
        context.fill(left - 8, 22, left + PANEL_W + 8, this.height - 6, PANEL_BG);
        context.fill(left - 8, 22, left + PANEL_W + 8, 23, PANEL_EDGE);

        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, TITLE);

        // Underline the active tab -- clearer than bracketing its text, and it
        // doesn't make the label jump around as tabs change width.
        int tabW = PANEL_W / TABS.length;
        context.fill(left + tab * tabW, 30 + TAB_H, left + tab * tabW + tabW - 2, 30 + TAB_H + 2, 0xFFFFD68A);

        for (Label label : labels) {
            context.drawTextWithShadow(this.textRenderer, Text.literal(label.text()),
                    label.x(), label.y(), label.color());
        }

        if (tab == 0 && schematicStatusY >= 0) renderSchematicStatus(context, left);
        if (tab == 5) renderItems(context, left);
        if (tab == 6) renderStatus(context, left);

        boolean loaded = schematicSource.isLoaded();
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(loaded ? schematicSource.describe() : "No schematic loaded"),
                this.width / 2, this.height - 40, loaded ? MUTED : BAD);
    }

    /**
     * Drawn fresh every frame rather than baked into a Row at init(), so it
     * reflects LitematicaSync's latest read without needing the screen reopened.
     */
    private void renderSchematicStatus(DrawContext context, int x) {
        boolean loaded = schematicSource.isLoaded();
        String text = loaded
                ? "✓ " + schematicSource.describe()
                : "✗ " + AutoBuilderClient.LITEMATICA_SYNC.getStatus();
        context.drawTextWithShadow(this.textRenderer, Text.literal(text),
                x, schematicStatusY, loaded ? GOOD : WARN);
    }

    private void renderItems(DrawContext context, int x) {
        int y = contentBottom + 2;
        Map<Item, Integer> needed = executor.getMaterials();
        if (needed.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("Press Start to work out what this build needs."), x, y, MUTED);
            return;
        }
        Map<Item, Integer> shortfall = executor.getShortfall(MinecraftClient.getInstance());

        int shown = 0;
        int maxRows = (this.height - 100 - y) / 11;
        for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
            if (shown >= maxRows) {
                context.drawTextWithShadow(this.textRenderer,
                        Text.literal("... and " + (needed.size() - shown) + " more"), x, y, MUTED);
                break;
            }
            int missing = shortfall.getOrDefault(entry.getKey(), 0);
            String name = entry.getKey().getName().getString();
            context.drawTextWithShadow(this.textRenderer, Text.literal(name), x, y, MUTED);
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("need " + entry.getValue()), x + 170, y, MUTED);
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal(missing > 0 ? "short " + missing : "ok"),
                    x + 245, y, missing > 0 ? BAD : GOOD);
            y += 11;
            shown++;
        }
    }

    private void renderStatus(DrawContext context, int x) {
        int y = contentBottom + 2;

        int done = executor.getPlacedCount();
        int total = executor.getTotalCount();
        int percent = total == 0 ? 0 : done * 100 / total;

        // Progress bar: one glance tells you more than the numbers do.
        context.fill(x, y, x + PANEL_W, y + 8, 0x60000000);
        context.fill(x, y, x + (int) (PANEL_W * (percent / 100.0)), y + 8, 0xFF5FA85F);
        y += 14;

        line(context, x, y, "Progress", total == 0 ? "no plan yet"
                : done + " / " + total + "  (" + percent + "%)", GOOD); y += 11;
        line(context, x, y, "State", executor.getState().toString(), GOOD); y += 11;
        line(context, x, y, "Doing", executor.getStatusMessage(), MUTED); y += 11;
        line(context, x, y, "Layer", executor.getLayerProgress(), MUTED); y += 11;
        line(context, x, y, "Rate", String.format("%.0f blocks/min", executor.getBlocksPerMinute()), MUTED); y += 11;
        line(context, x, y, "ETA", executor.getEta(), MUTED); y += 11;
        line(context, x, y, "Skipped", String.valueOf(executor.getSkippedCount()),
                executor.getSkippedCount() > 0 ? WARN : MUTED); y += 11;
        line(context, x, y, "To remove", String.valueOf(executor.getRemovalCount()), MUTED); y += 11;
        line(context, x, y, "Fatigue", executor.getFatiguePercent() + "%", MUTED); y += 11;

        if (executor.isAwaitingPurchaseConfirmation()) {
            y += 6;
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("Waiting for approval:"), x, y, WARN);
            y += 11;
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal(executor.getPurchasePrompt()), x, y, BAD);
        }
    }

    private void line(DrawContext context, int x, int y, String label, String value, int color) {
        context.drawTextWithShadow(this.textRenderer, Text.literal(label), x, y, MUTED);
        context.drawTextWithShadow(this.textRenderer, Text.literal(value), x + 78, y, color);
    }

    @Override
    public boolean shouldPause() { return false; }
}
