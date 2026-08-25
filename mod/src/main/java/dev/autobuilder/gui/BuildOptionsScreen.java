package dev.autobuilder.gui;

import dev.autobuilder.AutoBuilderClient;
import dev.autobuilder.config.BuilderConfig;
import dev.autobuilder.exec.BuildExecutor;
import dev.autobuilder.schematic.LitematicFileSchematicSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;
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

        int y = top + TAB_H + 8;
        switch (tab) {
            case 0 -> buildTab(left, y);
            case 1 -> moveTab(left, y);
            case 2 -> timingTab(left, y);
            case 3 -> buyingTab(left, y);
            case 4 -> safetyTab(left, y);
            case 5 -> itemsTab(left, y);
            default -> statusTab(left, y);
        }

        int controlY = this.height - 28;
        addDrawableChild(ButtonWidget.builder(Text.literal("Start"), b -> {
            applyPending();
            executor.start(MinecraftClient.getInstance());
            close();
        }).dimensions(left, controlY, 76, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Pause"), b -> executor.pause())
                .dimensions(left + 78, controlY, 76, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Stop"), b -> executor.stop())
                .dimensions(left + 156, controlY, 76, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(left + 234, controlY, 76, 20).build());
    }

    private void switchTo(int target) {
        applyPending();
        MinecraftClient.getInstance().setScreen(
                new BuildOptionsScreen(config, schematicSource, executor, target));
    }

    // ---------------------------------------------------------------- tabs

    private void buildTab(int x, int y) {
        y = header(x, y, "Schematic");
        addDrawableChild(field(x, y, "File name (config/litematica/schematics)",
                schematicSource.getFile() != null ? schematicSource.getFile().getFileName().toString() : "",
                this::applySchematicFile));
        y += ROW_H;

        BlockPos origin = schematicSource.getOrigin();
        addDrawableChild(field(x, y, "Origin: x y z",
                origin.getX() + " " + origin.getY() + " " + origin.getZ(), this::applyOrigin));
        y += ROW_H;

        y = header(x, y, "Order");
        addDrawableChild(cycler(x, y, "Order", BuilderConfig.BuildStrategy.values(),
                () -> config.strategy, v -> config.strategy = v, v -> v.label));
        y += ROW_H;
        addDrawableChild(cycler(x, y, "Direction", BuilderConfig.LayerDirection.values(),
                () -> config.layerDirection, v -> config.layerDirection = v, v -> v.label));
        y += ROW_H;
        addDrawableChild(toggle(x, y, "Finish each layer first",
                () -> config.strictLayers, v -> config.strictLayers = v));
        y += ROW_H;
        addDrawableChild(cycler(x, y, "Scaffold", BuilderConfig.ScaffoldBlock.values(),
                () -> config.scaffoldBlock, v -> config.scaffoldBlock = v, v -> v.label));
        y += ROW_H;

        y = header(x, y, "Matching the schematic");
        addDrawableChild(toggle(x, y, "Require exact block match",
                () -> config.strictBlockMatch, v -> config.strictBlockMatch = v));
        y += ROW_H;
        addDrawableChild(toggle(x, y, "Break wrong blocks",
                () -> config.breakWrongBlocks, v -> config.breakWrongBlocks = v));
        y += ROW_H;
        addDrawableChild(toggle(x, y, "Remove extra blocks",
                () -> config.removeExtraBlocks, v -> config.removeExtraBlocks = v));
        y += ROW_H;
        addDrawableChild(toggle(x, y, "Verify pass at end",
                () -> config.verifyPass, v -> config.verifyPass = v));
        y += ROW_H;
        addDrawableChild(toggle(x, y, "Re-check while building",
                () -> config.continuousVerify, v -> config.continuousVerify = v));
    }

    private void moveTab(int x, int y) {
        y = header(x, y, "Traversal");
        addDrawableChild(toggle(x, y, "Pearl climbing",
                () -> config.usePearlClimbing, v -> config.usePearlClimbing = v));
        y += ROW_H;
        addDrawableChild(cycler(x, y, "Pearl reserve", new Integer[]{0, 2, 4, 8, 16},
                () -> config.pearlReserve, v -> config.pearlReserve = v, String::valueOf));
        y += ROW_H;
        addDrawableChild(toggle(x, y, "Allow jumping", () -> config.allowJump, v -> config.allowJump = v));
        y += ROW_H;
        addDrawableChild(toggle(x, y, "Sprint", () -> config.allowSprint, v -> config.allowSprint = v));
        y += ROW_H;
        addDrawableChild(cycler(x, y, "Max fall", new Integer[]{0, 2, 3, 5, 10},
                () -> config.maxFallDistance, v -> config.maxFallDistance = v, v -> v + " blocks"));
        y += ROW_H;

        y = header(x, y, "Placement");
        addDrawableChild(cycler(x, y, "Reach", new Double[]{3.0, 3.5, 4.0, 4.5},
                () -> config.maxReach, v -> config.maxReach = v, v -> v + " blocks"));
        y += ROW_H;
        addDrawableChild(cycler(x, y, "Pathfinding effort", new Integer[]{2000, 4000, 8000, 16000},
                () -> config.maxPathNodes, v -> config.maxPathNodes = v, String::valueOf));
        y += ROW_H;

        y = header(x, y, "Care");
        addDrawableChild(toggle(x, y, "Sneak near edges",
                () -> config.sneakNearEdges, v -> config.sneakNearEdges = v));
        y += ROW_H;
        addDrawableChild(toggle(x, y, "Avoid lava and fire",
                () -> config.avoidHazards, v -> config.avoidHazards = v));
        y += ROW_H;
        addDrawableChild(toggle(x, y, "Return to start when done",
                () -> config.returnHomeWhenDone, v -> config.returnHomeWhenDone = v));
    }

    private void timingTab(int x, int y) {
        y = header(x, y, "Speed");
        addDrawableChild(cycler(x, y, "Pace", BuilderConfig.Pace.values(),
                () -> config.pace, v -> config.pace = v, Enum::name));
        y += ROW_H;
        addDrawableChild(cycler(x, y, "Delay scale", new Integer[]{50, 75, 100, 150, 200, 300},
                () -> config.speedPercent, v -> config.speedPercent = v, v -> v + "%"));
        y += ROW_H;
        addDrawableChild(cycler(x, y, "Randomness", new Integer[]{0, 50, 100, 150, 250},
                () -> config.jitterPercent, v -> config.jitterPercent = v, v -> v + "%"));
        y += ROW_H;
        addDrawableChild(toggle(x, y, "Tire out over time",
                () -> config.simulateFatigue, v -> config.simulateFatigue = v));
        y += ROW_H;

        y = header(x, y, "Breaks");
        addDrawableChild(toggle(x, y, "Take breaks", () -> config.takeBreaks, v -> config.takeBreaks = v));
        y += ROW_H;
        addDrawableChild(cycler(x, y, "Break every", new Integer[]{32, 64, 128, 256, 512},
                () -> config.breakEveryBlocks, v -> config.breakEveryBlocks = v, v -> v + " blocks"));
        y += ROW_H;
        addDrawableChild(cycler(x, y, "Break length", new Integer[]{5, 15, 30, 60, 120, 300},
                () -> config.breakSeconds, v -> config.breakSeconds = v, v -> v + "s"));
        y += ROW_H;
        addDrawableChild(toggle(x, y, "Look around on breaks",
                () -> config.lookAroundOnBreak, v -> config.lookAroundOnBreak = v));
    }

    private void buyingTab(int x, int y) {
        y = header(x, y, "Auction house");
        addDrawableChild(toggle(x, y, "Buy missing materials",
                () -> config.autoBuyMaterials, v -> config.autoBuyMaterials = v));
        y += ROW_H;
        addDrawableChild(field(x, y, "Command (%s = item)", config.auctionCommandTemplate,
                s -> { if (!s.isBlank()) config.auctionCommandTemplate = s; }));
        y += ROW_H;
        addDrawableChild(field(x, y, "Price regex (group 1 = price)", config.auctionPriceRegex,
                s -> { if (!s.isBlank()) config.auctionPriceRegex = s; }));
        y += ROW_H;

        y = header(x, y, "Price limits (per item)");
        addDrawableChild(field(x, y, "Buy without asking under",
                String.format("%.0f", config.autoBuyLimit),
                s -> config.autoBuyLimit = parseDouble(s, config.autoBuyLimit)));
        y += ROW_H;
        addDrawableChild(field(x, y, "Never buy over",
                String.format("%.0f", config.hardMaxPrice),
                s -> config.hardMaxPrice = parseDouble(s, config.hardMaxPrice)));
        y += ROW_H;
        addDrawableChild(toggle(x, y, "Ask before expensive buys",
                () -> config.confirmExpensivePurchases, v -> config.confirmExpensivePurchases = v));
        y += ROW_H;

        y = header(x, y, "Behaviour");
        addDrawableChild(toggle(x, y, "Check every page for cheapest",
                () -> config.scanAllPages, v -> config.scanAllPages = v));
        y += ROW_H;
        addDrawableChild(cycler(x, y, "Max pages", new Integer[]{1, 3, 5, 10},
                () -> config.maxAuctionPages, v -> config.maxAuctionPages = v, String::valueOf));
        y += ROW_H;
        addDrawableChild(toggle(x, y, "Needs confirm click",
                () -> config.auctionRequiresConfirmClick, v -> config.auctionRequiresConfirmClick = v));
        y += ROW_H;
        addDrawableChild(cycler(x, y, "Buy extra", new Integer[]{0, 10, 25, 50},
                () -> config.buyExtraPercent, v -> config.buyExtraPercent = v, v -> v + "%"));
        y += ROW_H;
        addDrawableChild(cycler(x, y, "If unavailable", BuilderConfig.OutOfMaterialsPolicy.values(),
                () -> config.outOfMaterials, v -> config.outOfMaterials = v, v -> v.label));
    }

    private void safetyTab(int x, int y) {
        y = header(x, y, "Stop conditions");
        addDrawableChild(toggle(x, y, "Stop on low health",
                () -> config.stopOnLowHealth, v -> config.stopOnLowHealth = v));
        y += ROW_H;
        addDrawableChild(cycler(x, y, "Health floor", new Integer[]{4, 6, 10, 14},
                () -> config.lowHealthThreshold, v -> config.lowHealthThreshold = v,
                v -> (v / 2.0) + " hearts"));
        y += ROW_H;
        addDrawableChild(toggle(x, y, "Stop on low hunger",
                () -> config.stopOnLowHunger, v -> config.stopOnLowHunger = v));
        y += ROW_H;
        addDrawableChild(toggle(x, y, "Stop if player nearby",
                () -> config.stopOnPlayerNearby, v -> config.stopOnPlayerNearby = v));
        y += ROW_H;
        addDrawableChild(cycler(x, y, "Player radius", new Integer[]{16, 32, 64, 128},
                () -> config.stopOnPlayerRadius, v -> config.stopOnPlayerRadius = v, v -> v + " blocks"));
        y += ROW_H;
        addDrawableChild(toggle(x, y, "Stop when inventory full",
                () -> config.stopWhenInventoryFull, v -> config.stopWhenInventoryFull = v));
        y += ROW_H;
        addDrawableChild(cycler(x, y, "Time limit", new Integer[]{0, 15, 30, 60, 120, 480},
                () -> config.maxBuildMinutes, v -> config.maxBuildMinutes = v,
                v -> v == 0 ? "none" : v + " min"));
        y += ROW_H;

        y = header(x, y, "Other");
        addDrawableChild(toggle(x, y, "Auto-pick tools",
                () -> config.autoSelectTool, v -> config.autoSelectTool = v));
        y += ROW_H;
        addDrawableChild(toggle(x, y, "Remember settings",
                () -> config.saveSettings, v -> config.saveSettings = v));
    }

    /** The shopping list: what the plan needs and what's still missing. */
    private void itemsTab(int x, int y) {
        header(x, y, "Materials");
        // Body is drawn in render() so the numbers stay live while the build runs.
    }

    private void statusTab(int x, int y) {
        header(x, y, "Progress");
        if (executor.isAwaitingPurchaseConfirmation()) {
            int buttonY = this.height - 76;
            addDrawableChild(ButtonWidget.builder(Text.literal("Approve purchase"),
                    b -> executor.confirmPurchase()).dimensions(x, buttonY, 153, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Decline"),
                    b -> executor.declinePurchase()).dimensions(x + 157, buttonY, 153, 20).build());
        }
    }

    // ---------------------------------------------------------------- widgets

    private int header(int x, int y, String text) {
        labels.add(new Label(x, y, text, HEADER));
        return y + 11;
    }

    /** A button reading "Label: value" that advances to the next value on click. */
    private <T> ButtonWidget cycler(int x, int y, String label, T[] values,
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
        }).dimensions(x, y, PANEL_W, 20).build();
    }

    private ButtonWidget toggle(int x, int y, String label,
                                Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return cycler(x, y, label, new Boolean[]{Boolean.TRUE, Boolean.FALSE},
                getter, setter, v -> v ? "ON" : "OFF");
    }

    /**
     * Text fields don't push on every keystroke -- the apply runs on tab change,
     * start, or close, so a half-typed value never lands in the config.
     */
    private TextFieldWidget field(int x, int y, String placeholder, String initial, Consumer<String> apply) {
        TextFieldWidget widget = new TextFieldWidget(this.textRenderer, x, y, PANEL_W, 20, Text.literal(placeholder));
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

    private void applySchematicFile(String name) {
        if (name.isBlank()) return;
        Path dir = MinecraftClient.getInstance().runDirectory.toPath()
                .resolve("config").resolve("litematica").resolve("schematics");
        schematicSource.load(dir.resolve(name.trim()), schematicSource.getOrigin());
    }

    private void applyOrigin(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 3) return;
        try {
            BlockPos origin = new BlockPos(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            if (schematicSource.getFile() != null) {
                schematicSource.load(schematicSource.getFile(), origin);
            }
        } catch (NumberFormatException ignored) {
            // Keep the previous origin rather than reset it to something wrong.
        }
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

        if (tab == 5) renderItems(context, left);
        if (tab == 6) renderStatus(context, left);

        boolean loaded = schematicSource.isLoaded();
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(loaded ? schematicSource.describe() : "No schematic loaded"),
                this.width / 2, this.height - 40, loaded ? MUTED : BAD);
    }

    private void renderItems(DrawContext context, int x) {
        int y = 30 + TAB_H + 8 + 11;
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
        int y = 30 + TAB_H + 8 + 11;

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
